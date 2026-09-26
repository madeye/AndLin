package tech.anl.library.oci

import androidx.annotation.RequiresApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * Installs an OCI image as a proot rootfs: resolves the image for the device ABI, downloads its
 * layers into [cacheDir] and applies them in order with [extractor].
 *
 * Layer N+1 downloads while layer N extracts (at most one layer ahead, so at most two layer files
 * sit in [cacheDir] at once). After each layer, `rootfsDir/support/.oci_layers` records the
 * manifest and the layers applied so far; an interrupted install resumes after the last fully
 * extracted layer, pinned to the same manifest digest even if the tag has moved since.
 */
@RequiresApi(26) // java.nio.file
class OciFilesystemInstaller(
    private val client: OciRegistryClient,
    private val extractor: LayerExtractor,
    private val cacheDir: File,
) {
    companion object {
        /** Install state, relative to the rootfs. */
        const val STATE_FILE = "support/.oci_layers"
    }

    /**
     * Installs [imageRef] into [rootfsDir] (created if needed; should be empty or hold an
     * interrupted install of the same image). [onProgress] may be called from background threads.
     *
     * Throws [OciException] on failure; calling again resumes.
     */
    suspend fun install(imageRef: String, abi: String, rootfsDir: File, onProgress: (OciInstallProgress) -> Unit) {
        val ref = OciImageReference.parse(imageRef)
        onProgress(OciInstallProgress.Resolving(ref.toString()))

        val stateFile = File(rootfsDir, STATE_FILE)
        val previous = InstallState.read(stateFile)
        if (previous != null && previous.image != ref.toString()) {
            throw OciException(
                "${rootfsDir.path} holds a partial install of ${previous.image}, not $ref; delete it to start over",
            )
        }

        val image = resolve(ref, abi, previous)
        val digests = image.layers.map { it.digest }
        val done = previous?.layers.orEmpty()
        if (digests.take(done.size) != done) {
            throw OciException("${rootfsDir.path} holds layers that don't belong to $ref; delete it to start over")
        }

        val state = InstallState(ref.toString(), image.manifestDigest, done.toMutableList())
        rootfsDir.mkdirs()
        state.write(stateFile)
        cacheDir.mkdirs()

        val layerCount = image.layers.size
        coroutineScope {
            // Rendezvous channel: the downloader blocks on send until the extractor takes the
            // previous layer, which keeps downloads exactly one layer ahead. A download failure
            // travels through the channel, so the layer being extracted still finishes (and is
            // recorded) before the install fails.
            val downloaded = Channel<Result<Pair<Int, File>>>()
            launch {
                try {
                    for (index in done.size until layerCount) {
                        val layer = image.layers[index]
                        val file = File(cacheDir, layer.digest.replace(':', '-') + ".layer")
                        var lastPercent = -1
                        val result = try {
                            client.downloadBlob(ref, layer, file, onBytes = { bytes ->
                                val percent = if (layer.size > 0) (bytes * 100 / layer.size).toInt() else 100
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(OciInstallProgress.Downloading(index + 1, layerCount, percent))
                                }
                            }, registries = image.registries, onNotice = { onProgress(OciInstallProgress.Notice(it)) })
                            Result.success(index to file)
                        } catch (e: IOException) {
                            Result.failure(e)
                        }
                        downloaded.send(result)
                        if (result.isFailure) break
                    }
                } finally {
                    downloaded.close()
                }
            }

            for (result in downloaded) {
                val (index, file) = result.getOrThrow()
                extractor.extract(file, rootfsDir) { percent ->
                    onProgress(OciInstallProgress.Extracting(index + 1, layerCount, percent))
                }
                state.layers += image.layers[index].digest
                state.write(stateFile)
                file.delete()
            }
        }

        onProgress(OciInstallProgress.Finalizing)
        RootfsFiles.makeOwnerWritable(rootfsDir.absoluteFile.toPath().resolve("support"))
        state.write(stateFile) // a layer may have removed it (e.g. an opaque /support)
    }

    /**
     * Resolves the image. When resuming, the recorded manifest digest is used so the remaining
     * layers match the ones already applied; if that manifest is gone, the tag is used and must
     * still start with the same layers.
     */
    private suspend fun resolve(ref: OciImageReference, abi: String, previous: InstallState?): ResolvedImage {
        if (previous != null) {
            try {
                return client.resolveManifest(ref.withDigest(previous.manifestDigest), abi).copy(reference = ref)
            } catch (e: OciException) {
                if (e.httpStatus != 404) throw e
            }
        }
        return client.resolveManifest(ref, abi)
    }

    /**
     * Contents of [STATE_FILE]:
     * ```
     * image ghcr.io/cypherpunkarmory/userland-alpine:latest
     * manifest sha256:...
     * layer sha256:...   (one line per applied layer, in order)
     * ```
     */
    private class InstallState(val image: String, val manifestDigest: String, val layers: MutableList<String>) {
        fun write(file: File) {
            file.parentFile?.mkdirs()
            val text = buildString {
                append("image ").append(image).append('\n')
                append("manifest ").append(manifestDigest).append('\n')
                layers.forEach { append("layer ").append(it).append('\n') }
            }
            val temp = File(file.path + ".tmp")
            temp.writeText(text)
            if (!temp.renameTo(file)) {
                file.delete()
                if (!temp.renameTo(file)) throw OciException("Could not write ${file.path}")
            }
        }

        companion object {
            fun read(file: File): InstallState? {
                if (!file.isFile) return null
                var image: String? = null
                var manifest: String? = null
                val layers = ArrayList<String>()
                file.readLines().forEach { line ->
                    val key = line.substringBefore(' ')
                    val value = line.substringAfter(' ', "").trim()
                    when (key) {
                        "image" -> image = value
                        "manifest" -> manifest = value
                        "layer" -> layers += value
                    }
                }
                return InstallState(image ?: return null, manifest ?: return null, layers)
            }
        }
    }
}
