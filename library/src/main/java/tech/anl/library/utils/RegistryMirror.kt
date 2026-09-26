package tech.anl.library.utils

import java.util.TimeZone

/**
 * Picks the container registry to pull filesystem images from.
 *
 * ghcr.io is slow or unreachable from mainland China, so devices set to a Chinese time zone pull
 * through Nanjing University's GHCR mirror instead. The mirror serves the same manifests and
 * blobs by digest, so the digests we verify are unchanged. The time zone is only a hint, so
 * callers try the returned registries in order and fall back to the next on failure.
 */
object RegistryMirror {
    const val GHCR = "ghcr.io"
    const val GHCR_CHINA_MIRROR = "ghcr.nju.edu.cn"

    private val chinaTimeZones = setOf(
        "Asia/Shanghai", "Asia/Chongqing", "Asia/Chungking", "Asia/Harbin",
        "Asia/Urumqi", "Asia/Kashgar", "PRC"
    )

    fun isLikelyInChina(timeZoneId: String = TimeZone.getDefault().id): Boolean =
        timeZoneId in chinaTimeZones

    /** Registries to try for an image hosted on [registry], preferred first. */
    fun candidates(registry: String, timeZoneId: String = TimeZone.getDefault().id): List<String> {
        if (registry != GHCR) return listOf(registry)
        return if (isLikelyInChina(timeZoneId)) listOf(GHCR_CHINA_MIRROR, GHCR) else listOf(GHCR, GHCR_CHINA_MIRROR)
    }

    /** Rewrites an image reference like "ghcr.io/org/name:tag" to use the preferred registry. */
    fun preferredImageRef(imageRef: String, timeZoneId: String = TimeZone.getDefault().id): String {
        val registry = imageRef.substringBefore('/')
        val preferred = candidates(registry, timeZoneId).first()
        return if (preferred == registry) imageRef else preferred + imageRef.removePrefix(registry)
    }
}
