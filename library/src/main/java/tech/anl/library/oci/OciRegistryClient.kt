package tech.anl.library.oci

import com.squareup.moshi.Moshi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal anonymous client for the OCI distribution API: resolves an image tag to the manifest
 * for one platform and downloads blobs, verifying every digest.
 *
 * Mirrors: [registryCandidates] maps an image's registry to the registries to try, preferred
 * first (e.g. `{ RegistryMirror.candidates(it) }` to prefer a GHCR mirror in China). The manifest
 * comes from the first registry that serves it; each blob falls back through the remaining
 * registries if one keeps failing. Mirrors are safe because content is addressed by digest.
 *
 * Authentication follows whatever `WWW-Authenticate: Bearer` challenge a registry sends (GHCR
 * wants an anonymous token; some mirrors need none). Blob downloads may redirect to a CDN; OkHttp
 * drops the Authorization header when the redirect changes host.
 */
class OciRegistryClient(
    private val http: OkHttpClient,
    private val registryCandidates: (String) -> List<String> = { listOf(it) },
    private val baseUrlFor: (String) -> String = ::defaultRegistryBaseUrl,
    /** Attempts per registry for each blob (manifests get at most 2, to fall back quickly). */
    private val maxAttempts: Int = 4,
    /** First retry delay; doubled for each further attempt. */
    private val retryDelayMillis: Long = 1_000,
) {
    companion object {
        const val OCI_INDEX = "application/vnd.oci.image.index.v1+json"
        const val OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json"
        const val DOCKER_MANIFEST_LIST = "application/vnd.docker.distribution.manifest.list.v2+json"
        const val DOCKER_MANIFEST = "application/vnd.docker.distribution.manifest.v2+json"

        const val OCI_LAYER_GZIP = "application/vnd.oci.image.layer.v1.tar+gzip"
        const val OCI_LAYER_TAR = "application/vnd.oci.image.layer.v1.tar"
        const val OCI_LAYER_ZSTD = "application/vnd.oci.image.layer.v1.tar+zstd"
        const val DOCKER_LAYER_GZIP = "application/vnd.docker.image.rootfs.diff.tar.gzip"

        private val SUPPORTED_LAYERS = setOf(OCI_LAYER_GZIP, OCI_LAYER_TAR, DOCKER_LAYER_GZIP)
        private const val MAX_MANIFEST_BYTES = 4L * 1024 * 1024
        private const val MANIFEST_ATTEMPTS = 2
        private const val BUFFER_SIZE = 64 * 1024

        /** `https://<registry>`, except Docker Hub's API host and plain HTTP for loopback registries. */
        fun defaultRegistryBaseUrl(registry: String): String = when {
            registry == OciImageReference.DOCKER_HUB -> "https://registry-1.docker.io"
            registry.startsWith("localhost") || registry.startsWith("127.0.0.1") -> "http://$registry"
            else -> "https://$registry"
        }

        fun sha256Hex(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().toHex()
        }

        private const val HEX = "0123456789abcdef"

        private fun ByteArray.toHex(): String {
            val out = StringBuilder(size * 2)
            for (b in this) out.append(HEX[(b.toInt() shr 4) and 0xf]).append(HEX[b.toInt() and 0xf])
            return out.toString()
        }
    }

    private val jsonAdapter = Moshi.Builder().build().adapter(Any::class.java)
    private val tokens = ConcurrentHashMap<String, String>()

    /** The registries to try for [ref], preferred first (never empty). */
    fun candidatesFor(ref: OciImageReference): List<String> =
        registryCandidates(ref.registry).distinct().ifEmpty { listOf(ref.registry) }

    /**
     * Resolves [ref] (a tag or digest) to the image manifest for the Android [abi], trying each
     * candidate registry in turn. Fails without trying further registries when the image itself
     * is unusable (no build for this platform, unsupported layer format).
     */
    suspend fun resolveManifest(ref: OciImageReference, abi: String): ResolvedImage {
        val platform = OciPlatform.forAbi(abi)
        val candidates = candidatesFor(ref)
        val failures = ArrayList<IOException>()
        for (registry in candidates) {
            try {
                val (digest, manifest) = resolveFrom(ref.withRegistry(registry), platform)
                return ResolvedImage(
                    reference = ref,
                    registries = listOf(registry) + (candidates - registry),
                    manifestDigest = digest,
                    config = manifest.config,
                    layers = manifest.layers,
                    platform = platform,
                )
            } catch (e: ImageContentException) {
                throw e
            } catch (e: IOException) {
                failures += e
            }
        }
        throw OciException(
            "Could not fetch the manifest of $ref from ${candidates.joinToString()}: " +
                failures.joinToString("; ") { it.message.orEmpty() },
            failures.lastOrNull(),
            retryable = failures.any { it !is OciException || it.retryable },
            // e.g. 404 when every registry agrees the manifest doesn't exist
            httpStatus = failures.map { (it as? OciException)?.httpStatus }.distinct().singleOrNull(),
        ).apply { failures.forEach { addSuppressed(it) } }
    }

    /**
     * Downloads the blob [descriptor] of [ref] to [dest], verifying size and sha256 digest.
     * [onBytes] receives the number of bytes of the blob present so far.
     *
     * Data goes to `<dest>.partial` first. Failed attempts are retried with exponential backoff,
     * resuming with an HTTP Range request; once a registry has used up [maxAttempts], the next of
     * [registries] continues from the same partial file. An existing, valid [dest] is reused.
     * Cancellation deletes the partial file.
     */
    suspend fun downloadBlob(
        ref: OciImageReference,
        descriptor: OciDescriptor,
        dest: File,
        onBytes: (Long) -> Unit,
        registries: List<String> = candidatesFor(ref),
    ) {
        val expectedHex = descriptor.digest.removePrefix("sha256:")
        if (!descriptor.digest.startsWith("sha256:") || expectedHex.length != 64) {
            throw OciException("Unsupported digest '${descriptor.digest}' (only sha256 is supported)")
        }
        if (dest.isFile && dest.length() == descriptor.size && sha256Hex(dest) == expectedHex) {
            onBytes(descriptor.size)
            return
        }
        dest.absoluteFile.parentFile?.mkdirs()
        val partial = File(dest.path + ".partial")

        try {
            val failures = ArrayList<IOException>()
            for (registry in registries) {
                try {
                    withRetries(maxAttempts) {
                        downloadAttempt(ref.withRegistry(registry), descriptor, expectedHex, partial, onBytes)
                    }
                    dest.delete()
                    if (!partial.renameTo(dest)) throw OciException("Could not move ${partial.name} to ${dest.name}")
                    return
                } catch (e: IOException) {
                    failures += e
                }
            }
            throw OciException(
                "Failed to download ${descriptor.digest} from ${registries.joinToString()}: " +
                    failures.joinToString("; ") { it.message.orEmpty() },
                failures.lastOrNull(),
            ).apply { failures.forEach { addSuppressed(it) } }
        } catch (e: CancellationException) {
            partial.delete()
            throw e
        }
    }

    // --- manifests ---------------------------------------------------------------------------

    private class ImageManifest(val config: OciDescriptor, val layers: List<OciDescriptor>)

    /** The image is unusable no matter which registry serves it; don't try mirrors. */
    private class ImageContentException(message: String) : OciException(message)

    private suspend fun resolveFrom(ref: OciImageReference, platform: OciPlatform): Pair<String, ImageManifest> {
        val (topBytes, topType) = withRetries(MANIFEST_ATTEMPTS) { fetchManifest(ref, ref.tag) }
        if (ref.isDigest) verifyDigest(topBytes, ref.tag, "manifest")
        val top = parseJson(topBytes)
        val mediaType = top["mediaType"] as? String ?: topType

        val manifests = top["manifests"] as? List<*>
        if (manifests == null) {
            // A single-platform image: trust that it matches (there's no index to consult).
            return sha256Digest(topBytes) to parseImageManifest(top, mediaType)
        }

        val childDigest = selectPlatform(ref, manifests, platform)
        val (childBytes, childType) = withRetries(MANIFEST_ATTEMPTS) { fetchManifest(ref, childDigest) }
        verifyDigest(childBytes, childDigest, "manifest")
        val child = parseJson(childBytes)
        return childDigest to parseImageManifest(child, child["mediaType"] as? String ?: childType)
    }

    private fun selectPlatform(ref: OciImageReference, manifests: List<*>, platform: OciPlatform): String {
        val entries = manifests.mapNotNull { it as? Map<*, *> }.filterNot { entry ->
            val annotations = entry["annotations"] as? Map<*, *>
            val entryPlatform = entry["platform"] as? Map<*, *>
            annotations?.get("vnd.docker.reference.type") == "attestation-manifest" ||
                entryPlatform?.get("os") == "unknown"
        }
        fun Map<*, *>.platformField(name: String) = (this["platform"] as? Map<*, *>)?.get(name) as? String
        val matching = entries.filter {
            platform.matches(it.platformField("os"), it.platformField("architecture"), it.platformField("variant"))
        }
        val best = matching.firstOrNull { it.platformField("variant") == platform.variant } ?: matching.firstOrNull()
        if (best == null) {
            val available = entries.joinToString {
                listOfNotNull(it.platformField("os"), it.platformField("architecture"), it.platformField("variant")).joinToString("/")
            }
            throw ImageContentException("$ref has no image for $platform (available: $available)")
        }
        return best["digest"] as? String ?: throw ImageContentException("Malformed image index for $ref")
    }

    private fun parseImageManifest(json: Map<String, Any?>, mediaType: String?): ImageManifest {
        if (mediaType != null && mediaType != OCI_MANIFEST && mediaType != DOCKER_MANIFEST) {
            throw ImageContentException("Unsupported manifest type '$mediaType'")
        }
        val config = parseDescriptor(json["config"]) ?: throw ImageContentException("Manifest has no config")
        val layers = (json["layers"] as? List<*>)?.map {
            parseDescriptor(it) ?: throw ImageContentException("Malformed layer descriptor")
        } ?: throw ImageContentException("Manifest has no layers")
        for (layer in layers) {
            when (layer.mediaType) {
                in SUPPORTED_LAYERS -> Unit
                OCI_LAYER_ZSTD -> throw ImageContentException("zstd-compressed layers are not supported (${layer.digest})")
                else -> throw ImageContentException("Unsupported layer type '${layer.mediaType}' (${layer.digest})")
            }
        }
        return ImageManifest(config, layers)
    }

    private fun parseDescriptor(value: Any?): OciDescriptor? {
        val map = value as? Map<*, *> ?: return null
        return OciDescriptor(
            mediaType = map["mediaType"] as? String ?: return null,
            digest = map["digest"] as? String ?: return null,
            size = (map["size"] as? Number)?.toLong() ?: return null,
        )
    }

    private suspend fun fetchManifest(ref: OciImageReference, reference: String): Pair<ByteArray, String?> {
        val url = "${baseUrlFor(ref.registry)}/v2/${ref.repository}/manifests/$reference"
        return blockingCall { tracker ->
            execute(ref, url, tracker) {
                header("Accept", listOf(OCI_INDEX, DOCKER_MANIFEST_LIST, OCI_MANIFEST, DOCKER_MANIFEST).joinToString(", "))
            }.use { response ->
                checkStatus(response, "manifest $reference of ${ref.registry}/${ref.repository}")
                val body = response.body ?: throw OciException("Empty manifest response", retryable = true)
                if (body.contentLength() > MAX_MANIFEST_BYTES) throw OciException("Manifest too large")
                val bytes = body.source().use { source ->
                    source.request(MAX_MANIFEST_BYTES + 1)
                    if (source.buffer.size > MAX_MANIFEST_BYTES) throw OciException("Manifest too large")
                    source.readByteArray()
                }
                bytes to response.header("Content-Type")?.substringBefore(';')?.trim()
            }
        }
    }

    // --- blobs -------------------------------------------------------------------------------

    private suspend fun downloadAttempt(
        ref: OciImageReference,
        descriptor: OciDescriptor,
        expectedHex: String,
        partial: File,
        onBytes: (Long) -> Unit,
    ) {
        var offset = if (partial.isFile) partial.length() else 0L
        if (offset > descriptor.size) {
            partial.delete()
            offset = 0
        }
        val url = "${baseUrlFor(ref.registry)}/v2/${ref.repository}/blobs/${descriptor.digest}"

        blockingCall { tracker ->
            if (offset < descriptor.size) {
                execute(ref, url, tracker) { if (offset > 0) header("Range", "bytes=$offset-") }.use { response ->
                    val append = when {
                        offset > 0 && response.code == 206 && contentRangeStart(response) == offset -> true
                        response.code == 206 -> {
                            partial.delete() // a range we didn't ask for; start over
                            throw OciException("Unexpected Content-Range from ${ref.registry}", retryable = true)
                        }
                        response.code == 416 -> {
                            partial.delete()
                            throw OciException("Range not satisfiable; restarting download", retryable = true)
                        }
                        else -> {
                            checkStatus(response, "blob ${descriptor.digest}")
                            false
                        }
                    }
                    if (!append) offset = 0
                    val body = response.body ?: throw OciException("Empty blob response", retryable = true)
                    FileOutputStream(partial, append).use { out ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var total = offset
                            onBytes(total)
                            while (true) {
                                ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                if (total + n > descriptor.size) {
                                    partial.delete()
                                    throw OciException("Blob ${descriptor.digest} is larger than advertised", retryable = true)
                                }
                                out.write(buffer, 0, n)
                                total += n
                                onBytes(total)
                            }
                        }
                    }
                }
            }

            val length = partial.length()
            if (length != descriptor.size) {
                throw OciException("Download of ${descriptor.digest} ended at $length of ${descriptor.size} bytes", retryable = true)
            }
            // Hash the finished file rather than the stream: resumed downloads span several responses.
            val actual = sha256Hex(partial)
            if (actual != expectedHex) {
                partial.delete()
                throw OciException("Digest mismatch for ${descriptor.digest} (got sha256:$actual)", retryable = true)
            }
        }
    }

    private fun contentRangeStart(response: Response): Long? =
        response.header("Content-Range")?.let { Regex("""bytes (\d+)-""").find(it)?.groupValues?.get(1)?.toLongOrNull() }

    // --- HTTP plumbing -----------------------------------------------------------------------

    /** Lets a coroutine cancellation abort whichever HTTP call is in flight. */
    private class CallTracker {
        @Volatile private var call: Call? = null
        @Volatile private var cancelled = false

        fun track(call: Call) {
            this.call = call
            if (cancelled) call.cancel()
        }

        fun cancel() {
            cancelled = true
            call?.cancel()
        }
    }

    private suspend fun <T> blockingCall(block: kotlinx.coroutines.CoroutineScope.(CallTracker) -> T): T {
        val tracker = CallTracker()
        return runCancellableIo(onCancel = tracker::cancel) { block(tracker) }
    }

    /** Sends a request, answering a Bearer challenge with an anonymous token once. */
    private fun execute(ref: OciImageReference, url: String, tracker: CallTracker, configure: Request.Builder.() -> Unit): Response {
        val tokenKey = "${ref.registry}/${ref.repository}"
        fun send(token: String?): Response {
            val request = Request.Builder().url(url).apply(configure).apply {
                if (token != null) header("Authorization", "Bearer $token")
            }.build()
            val call = http.newCall(request)
            tracker.track(call)
            return call.execute()
        }

        val response = send(tokens[tokenKey])
        if (response.code != 401) return response
        val challenge = response.header("WWW-Authenticate")
        response.close()

        val token = fetchToken(ref, challenge, tracker)
        tokens[tokenKey] = token
        val retried = send(token)
        if (retried.code == 401) {
            retried.close()
            throw OciException("${ref.registry} refused anonymous access to ${ref.repository}", httpStatus = 401)
        }
        return retried
    }

    private fun fetchToken(ref: OciImageReference, challenge: String?, tracker: CallTracker): String {
        if (challenge == null || !challenge.trim().startsWith("Bearer", ignoreCase = true)) {
            throw OciException("${ref.registry} requires authentication (${challenge ?: "no challenge"})", httpStatus = 401)
        }
        val params = Regex("""(\w+)="([^"]*)"""").findAll(challenge).associate { it.groupValues[1] to it.groupValues[2] }
        val realm = params["realm"] ?: throw OciException("Bearer challenge from ${ref.registry} has no realm")
        val url = realm.toHttpUrl().newBuilder().apply {
            params["service"]?.let { addQueryParameter("service", it) }
            addQueryParameter("scope", params["scope"] ?: "repository:${ref.repository}:pull")
        }.build()

        val call = http.newCall(Request.Builder().url(url).build())
        tracker.track(call)
        call.execute().use { response ->
            checkStatus(response, "token from $realm")
            val json = parseJson(response.body?.bytes() ?: ByteArray(0))
            return (json["token"] as? String ?: json["access_token"] as? String)
                ?: throw OciException("Token response from $realm has no token")
        }
    }

    private fun checkStatus(response: Response, what: String) {
        if (response.isSuccessful) return
        val code = response.code
        val retryable = code == 408 || code == 429 || code >= 500
        throw OciException("HTTP $code fetching $what", retryable = retryable, httpStatus = code)
    }

    /** Runs [block], retrying retryable failures (and plain I/O errors) with exponential backoff. */
    private suspend fun <T> withRetries(attempts: Int, block: suspend () -> T): T {
        var attempt = 1
        while (true) {
            try {
                return block()
            } catch (e: IOException) {
                val retryable = e !is OciException || e.retryable
                if (!retryable || attempt >= attempts) throw e
                delay(retryDelayMillis shl (attempt - 1))
                attempt++
            }
        }
    }

    private fun parseJson(bytes: ByteArray): Map<String, Any?> {
        val parsed = try {
            jsonAdapter.fromJson(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw OciException("Malformed JSON from registry: ${e.message}", e, retryable = true)
        }
        @Suppress("UNCHECKED_CAST")
        return parsed as? Map<String, Any?> ?: throw OciException("Expected a JSON object from registry", retryable = true)
    }

    private fun sha256Digest(bytes: ByteArray) = "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun verifyDigest(bytes: ByteArray, expected: String, what: String) {
        if (!expected.startsWith("sha256:")) throw ImageContentException("Unsupported digest algorithm in '$expected'")
        val actual = sha256Digest(bytes)
        if (actual != expected) throw OciException("$what digest mismatch: expected $expected, got $actual", retryable = true)
    }
}
