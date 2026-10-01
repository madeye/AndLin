package tech.anl.library.utils

import tech.anl.customlibrary.BuildConfig
import tech.anl.library.model.entities.FilesystemFlavor
import java.util.Locale

/**
 * Maps a distribution and flavor to the OCI image its filesystem is built from.
 *
 * New filesystems always get ServerBox's own headless server images
 * (github.com/madeye/ServerBox-Images). Filesystems created before desktop support was removed may
 * still record a desktop flavor ("default", "xfce", "lxde"); those keep resolving to the upstream
 * UserLAnd image they were built from, because the image is resolved again after creation (every
 * VM session start passes it to the companion, which re-pulls it when layers are missing, and an
 * interrupted PRoot install is retried from it). Swapping in a different image there would
 * silently rebuild the filesystem from something else. Callers pass the result through
 * [RegistryMirror] to pick a registry.
 */
object FilesystemImages {
    const val SERVER_IMAGE_NAMESPACE = "ghcr.io/madeye"
    const val LEGACY_DESKTOP_IMAGE_NAMESPACE = "ghcr.io/cypherpunkarmory"
    private const val LEGACY_DESKTOP_DEFAULT_FLAVOR = "default"

    fun imageRef(distribution: String, flavor: String, ociTag: String = BuildConfig.DEFAULT_OCI_TAG): String {
        val distro = distribution.lowercase(Locale.ENGLISH)
        val tag = tagFor(ociTag, distro)
        return when (flavor.lowercase(Locale.ENGLISH)) {
            FilesystemFlavor.SERVER, "" -> "$SERVER_IMAGE_NAMESPACE/serverbox-$distro:$tag"
            // Legacy desktop flavors, only found on filesystems created by older builds.
            LEGACY_DESKTOP_DEFAULT_FLAVOR -> "$LEGACY_DESKTOP_IMAGE_NAMESPACE/userland-$distro:$tag"
            else -> "$LEGACY_DESKTOP_IMAGE_NAMESPACE/userland-${distro}_${flavor.lowercase(Locale.ENGLISH)}:$tag"
        }
    }

    /**
     * Images published only for 64-bit platforms (amd64, arm64): the coding agents ship no 32-bit
     * binaries. Keep in sync with the platforms in ServerBox-Images' build-<name>.yml.
     */
    private val SIXTY_FOUR_BIT_ONLY = setOf("claude", "codex")
    private val SIXTY_FOUR_BIT_ABIS = setOf("arm64-v8a", "x86_64")

    /** Whether [distribution]'s image has a build for [abi], the ABI images are pulled for. */
    fun isAvailableFor(distribution: String, abi: String): Boolean =
        distribution.lowercase(Locale.ENGLISH) !in SIXTY_FOUR_BIT_ONLY || abi.trim() in SIXTY_FOUR_BIT_ABIS

    /** DEFAULT_OCI_TAG is either one tag ("latest") or per-distribution pairs ("ubuntu:20260626,..."). */
    internal fun tagFor(ociTag: String, distribution: String): String {
        if (!ociTag.contains(':')) return ociTag.ifBlank { "latest" }
        return ociTag.split(',').map { it.trim() }
            .firstOrNull { it.substringBefore(':').equals(distribution, ignoreCase = true) }
            ?.substringAfter(':')
            ?: "latest"
    }
}
