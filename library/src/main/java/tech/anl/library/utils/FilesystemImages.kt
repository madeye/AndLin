package tech.anl.library.utils

import tech.anl.customlibrary.BuildConfig
import tech.anl.library.model.entities.FilesystemFlavor
import java.util.Locale

/**
 * Maps a distribution and flavor to the OCI image its filesystem is built from.
 *
 * Headless server images are ServerBox's own (github.com/madeye/AndLin-Images). The desktop
 * flavors keep using the upstream UserLAnd images, which carry the X server, VNC server and
 * desktop environments. Callers pass the result through [RegistryMirror] to pick a registry.
 */
object FilesystemImages {
    const val SERVER_IMAGE_NAMESPACE = "ghcr.io/madeye"
    const val DESKTOP_IMAGE_NAMESPACE = "ghcr.io/cypherpunkarmory"

    fun imageRef(distribution: String, flavor: String, ociTag: String = BuildConfig.DEFAULT_OCI_TAG): String {
        val distro = distribution.lowercase(Locale.ENGLISH)
        val tag = tagFor(ociTag, distro)
        return when (flavor.lowercase(Locale.ENGLISH)) {
            FilesystemFlavor.SERVER, "" -> "$SERVER_IMAGE_NAMESPACE/andlin-$distro:$tag"
            FilesystemFlavor.DEFAULT -> "$DESKTOP_IMAGE_NAMESPACE/userland-$distro:$tag"
            else -> "$DESKTOP_IMAGE_NAMESPACE/userland-${distro}_${flavor.lowercase(Locale.ENGLISH)}:$tag"
        }
    }

    /** DEFAULT_OCI_TAG is either one tag ("latest") or per-distribution pairs ("ubuntu:20260626,..."). */
    internal fun tagFor(ociTag: String, distribution: String): String {
        if (!ociTag.contains(':')) return ociTag.ifBlank { "latest" }
        return ociTag.split(',').map { it.trim() }
            .firstOrNull { it.substringBefore(':').equals(distribution, ignoreCase = true) }
            ?.substringAfter(':')
            ?: "latest"
    }
}
