package tech.anl.library.oci

import java.io.IOException

/**
 * A container image reference such as `ghcr.io/cypherpunkarmory/userland-ubuntu:20260921`.
 *
 * [tag] holds either a tag (`latest`) or a content digest (`sha256:...`); see [isDigest].
 */
data class OciImageReference(val registry: String, val repository: String, val tag: String) {

    val isDigest: Boolean get() = tag.contains(':')

    /** The same image addressed through another registry (e.g. a mirror). */
    fun withRegistry(registry: String): OciImageReference = copy(registry = registry)

    /** The same repository, addressed by [digest] instead of a tag. */
    fun withDigest(digest: String): OciImageReference = copy(tag = digest)

    override fun toString(): String = "$registry/$repository${if (isDigest) "@" else ":"}$tag"

    companion object {
        const val DOCKER_HUB = "docker.io"
        private val repositoryPattern = Regex("[a-z0-9]+(?:(?:[._]|__|-+)[a-z0-9]+)*(?:/[a-z0-9]+(?:(?:[._]|__|-+)[a-z0-9]+)*)*")
        private val tagPattern = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}")
        private val digestPattern = Regex("[a-z0-9]+(?:[.+_-][a-z0-9]+)*:[a-zA-Z0-9=_-]+")

        /**
         * Parses `[registry/]repository[:tag][@digest]`. The registry defaults to Docker Hub
         * (with the implicit `library/` namespace) and the tag to `latest`; a digest wins over a tag.
         */
        fun parse(ref: String): OciImageReference {
            val trimmed = ref.trim()
            require(trimmed.isNotEmpty()) { "Empty image reference" }

            var rest = trimmed
            var digest: String? = null
            rest.indexOf('@').takeIf { it >= 0 }?.let { at ->
                digest = rest.substring(at + 1)
                rest = rest.substring(0, at)
            }

            // The first component is a registry host only if it looks like one.
            val firstSlash = rest.indexOf('/')
            val first = if (firstSlash >= 0) rest.substring(0, firstSlash) else ""
            val hasRegistry = firstSlash >= 0 && (first.contains('.') || first.contains(':') || first == "localhost")
            val registry = if (hasRegistry) first.lowercase() else DOCKER_HUB
            rest = if (hasRegistry) rest.substring(firstSlash + 1) else rest

            // A ':' after the last '/' separates the tag (a ':' before it would be a port, handled above).
            var tag = "latest"
            val colon = rest.lastIndexOf(':')
            if (colon > rest.lastIndexOf('/')) {
                tag = rest.substring(colon + 1)
                rest = rest.substring(0, colon)
            }

            var repository = rest
            if (registry == DOCKER_HUB && !repository.contains('/')) repository = "library/$repository"

            require(repositoryPattern.matches(repository)) { "Invalid repository name in image reference '$ref'" }
            digest?.let {
                require(digestPattern.matches(it)) { "Invalid digest in image reference '$ref'" }
                return OciImageReference(registry, repository, it)
            }
            require(tagPattern.matches(tag)) { "Invalid tag in image reference '$ref'" }
            return OciImageReference(registry, repository, tag)
        }
    }
}

/** Progress reported by [OciFilesystemInstaller.install]. Layer numbers are 1-based. */
sealed class OciInstallProgress {
    data class Resolving(val reference: String) : OciInstallProgress()
    data class Downloading(val layer: Int, val layerCount: Int, val percent: Int) : OciInstallProgress()
    data class Extracting(val layer: Int, val layerCount: Int, val percent: Int) : OciInstallProgress()
    /** A one-off message worth showing, e.g. switching registries away from a slow mirror. */
    data class Notice(val message: String) : OciInstallProgress()
    object Finalizing : OciInstallProgress() {
        override fun toString() = "Finalizing"
    }
}

/**
 * Any failure while pulling or installing an image. [retryable] marks transient failures (network
 * errors, 5xx, a truncated or corrupt download) as opposed to permanent ones (404, unsupported format).
 */
open class OciException(
    message: String,
    cause: Throwable? = null,
    val retryable: Boolean = false,
    val httpStatus: Int? = null,
) : IOException(message, cause)

/** A content descriptor from a manifest: what to fetch, how big it is, and how it is encoded. */
data class OciDescriptor(val mediaType: String, val digest: String, val size: Long)

/** An OS/architecture pair as used in image indexes, e.g. `linux/arm/v7`. */
data class OciPlatform(val os: String, val architecture: String, val variant: String? = null) {

    /** Whether an index entry's platform satisfies this one (a missing variant is accepted). */
    fun matches(os: String?, architecture: String?, variant: String?): Boolean {
        if (os != this.os || architecture != this.architecture) return false
        if (variant == null || this.variant == null) return true
        return variant == this.variant
    }

    override fun toString() = listOfNotNull(os, architecture, variant).joinToString("/")

    companion object {
        /** Maps an Android ABI (Build.SUPPORTED_ABIS entry) to the image platform to pull. */
        fun forAbi(abi: String): OciPlatform = when (abi) {
            "arm64-v8a" -> OciPlatform("linux", "arm64", "v8")
            "armeabi-v7a" -> OciPlatform("linux", "arm", "v7")
            "x86_64" -> OciPlatform("linux", "amd64")
            "x86" -> throw OciException("32-bit x86 devices are not supported: the filesystem images are only published for arm64, arm/v7 and amd64")
            else -> throw OciException("Unsupported device ABI '$abi'")
        }
    }
}

/** A manifest resolved for one platform: the layers to apply, bottom-most first. */
data class ResolvedImage(
    /** The reference as requested (registry not rewritten to a mirror). */
    val reference: OciImageReference,
    /** Registries to fetch blobs from, in order: the one that served the manifest first. */
    val registries: List<String>,
    /** Digest of the platform-specific image manifest (not the index). */
    val manifestDigest: String,
    val config: OciDescriptor,
    val layers: List<OciDescriptor>,
    val platform: OciPlatform?,
)
