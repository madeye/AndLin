package tech.anl.library.oci

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * An in-memory OCI registry on a [MockWebServer], shaped like GHCR: anonymous Bearer tokens,
 * a multi-arch index with attestation manifests, and blobs that can fail mid-stream.
 */
class FakeRegistry(
    val repository: String = "cypherpunkarmory/userland-test",
    private val requireToken: Boolean = true,
) {
    val server = MockWebServer()
    val requests: MutableList<RecordedRequest> = Collections.synchronizedList(ArrayList())
    private val manifests = ConcurrentHashMap<String, Pair<String, ByteArray>>() // reference -> (type, body)
    private val blobs = ConcurrentHashMap<String, ByteArray>()
    private val blobCounts = ConcurrentHashMap<String, AtomicInteger>()

    /** Blob digests whose first N requests are cut off halfway through the body. */
    val disconnectFirst = ConcurrentHashMap<String, Int>()
    /** When set, every request answers with this status. */
    @Volatile var failEverythingWith: Int? = null
    /** When set, blob requests answer with this status. */
    @Volatile var failBlobsWith: Int? = null
    /** When set, blob requests redirect to this base URL (a "CDN"). */
    @Volatile var redirectBlobsTo: String? = null

    val token = "anon-token-${System.nanoTime()}"

    val registry: String get() = "${server.hostName}:${server.port}"
    val reference: OciImageReference get() = OciImageReference(registry, repository, "latest")

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return handle(request)
            }
        }
        server.start()
    }

    fun blobRequests(digest: String) = blobCounts[digest]?.get() ?: 0

    fun addBlob(content: ByteArray): OciDescriptor {
        val digest = sha256(content)
        blobs[digest] = content
        return OciDescriptor(OciRegistryClient.OCI_LAYER_GZIP, digest, content.size.toLong())
    }

    /** Publishes an image manifest (and its config) and returns its digest. */
    fun addImage(layers: List<OciDescriptor>, architecture: String = "arm64"): String {
        val config = addBlob("""{"architecture":"$architecture","os":"linux"}""".toByteArray())
        val manifest = """
            {"schemaVersion":2,"mediaType":"${OciRegistryClient.OCI_MANIFEST}",
             "config":{"mediaType":"application/vnd.oci.image.config.v1+json","digest":"${config.digest}","size":${config.size}},
             "layers":[${layers.joinToString(",") { """{"mediaType":"${it.mediaType}","digest":"${it.digest}","size":${it.size}}""" }}]}
        """.trimIndent().toByteArray()
        val digest = sha256(manifest)
        manifests[digest] = OciRegistryClient.OCI_MANIFEST to manifest
        return digest
    }

    /** Publishes a multi-arch index under [tag], with an attestation manifest listed first. */
    fun tagIndex(tag: String, platforms: Map<String, String>) {
        val attestation = addImage(emptyList(), "unknown")
        val entries = mutableListOf(
            """{"mediaType":"${OciRegistryClient.OCI_MANIFEST}","digest":"$attestation","size":100,
                "platform":{"architecture":"unknown","os":"unknown"},
                "annotations":{"vnd.docker.reference.digest":"sha256:0","vnd.docker.reference.type":"attestation-manifest"}}""",
        )
        for ((platform, digest) in platforms) {
            val parts = platform.split('/')
            val variant = parts.getOrNull(2)?.let { ""","variant":"$it"""" } ?: ""
            entries += """{"mediaType":"${OciRegistryClient.OCI_MANIFEST}","digest":"$digest","size":100,
                "platform":{"architecture":"${parts[1]}","os":"${parts[0]}"$variant}}"""
        }
        val index = """{"schemaVersion":2,"mediaType":"${OciRegistryClient.OCI_INDEX}","manifests":[${entries.joinToString(",")}]}"""
        manifests[tag] = OciRegistryClient.OCI_INDEX to index.toByteArray()
    }

    fun shutdown() = server.shutdown()

    private fun handle(request: RecordedRequest): MockResponse {
        failEverythingWith?.let { return MockResponse().setResponseCode(it) }
        val path = request.requestUrl!!.encodedPath

        if (path == "/token") {
            return MockResponse().setBody("""{"token":"$token","expires_in":300}""")
        }
        if (requireToken && request.getHeader("Authorization") != "Bearer $token") {
            return MockResponse().setResponseCode(401).setHeader(
                "WWW-Authenticate",
                """Bearer realm="${server.url("/token")}",service="${server.hostName}",scope="repository:$repository:pull"""",
            )
        }

        val manifestPrefix = "/v2/$repository/manifests/"
        val blobPrefix = "/v2/$repository/blobs/"
        return when {
            path.startsWith(manifestPrefix) -> {
                val (type, body) = manifests[path.removePrefix(manifestPrefix)]
                    ?: return MockResponse().setResponseCode(404)
                MockResponse().setHeader("Content-Type", type).setBody(Buffer().write(body))
            }
            path.startsWith(blobPrefix) -> {
                val digest = path.removePrefix(blobPrefix)
                blobCounts.getOrPut(digest) { AtomicInteger() }.incrementAndGet()
                failBlobsWith?.let { return MockResponse().setResponseCode(it) }
                redirectBlobsTo?.let { return MockResponse().setResponseCode(307).setHeader("Location", "$it/blob/$digest") }
                val body = blobs[digest] ?: return MockResponse().setResponseCode(404)
                serveBlob(body, request.getHeader("Range"), digest)
            }
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun serveBlob(body: ByteArray, range: String?, digest: String): MockResponse {
        val remainingDisconnects = disconnectFirst[digest] ?: 0
        if (remainingDisconnects > 0) {
            disconnectFirst[digest] = remainingDisconnects - 1
            return MockResponse().setBody(Buffer().write(body))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        }
        return blobResponse(body, range)
    }

    companion object {
        fun sha256(bytes: ByteArray) =
            "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** A full (200) or ranged (206) blob response. */
        fun blobResponse(body: ByteArray, range: String?): MockResponse {
            val start = range?.let { Regex("""bytes=(\d+)-""").find(it)!!.groupValues[1].toInt() } ?: 0
            if (start == 0) return MockResponse().setBody(Buffer().write(body))
            return MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes $start-${body.size - 1}/${body.size}")
                .setBody(Buffer().write(body.copyOfRange(start, body.size)))
        }
    }
}
