package tech.anl.library.utils

import java.util.TimeZone

/**
 * Picks the container registry to pull filesystem images from.
 *
 * ghcr.io is slow or unreachable from mainland China, so devices set to a Chinese time zone pull
 * through public GHCR mirrors first, in [GHCR_CHINA_MIRRORS] order (fastest when measured from
 * China in 2026-09). Mirrors serve the same manifests and blobs by digest, so the digests we
 * verify are unchanged; a mirror can only be slow or missing. The time zone is only a hint, so
 * callers try the returned registries in order and move on when one fails or is too slow.
 */
object RegistryMirror {
    const val GHCR = "ghcr.io"
    val GHCR_CHINA_MIRRORS = listOf("ghcr.linkos.org", "ghcr.chenby.cn", "ghcr.nju.edu.cn")

    private val chinaTimeZones = setOf(
        "Asia/Shanghai", "Asia/Chongqing", "Asia/Chungking", "Asia/Harbin",
        "Asia/Urumqi", "Asia/Kashgar", "PRC"
    )

    fun isLikelyInChina(timeZoneId: String = TimeZone.getDefault().id): Boolean =
        timeZoneId in chinaTimeZones

    /** Registries to try for an image hosted on [registry], preferred first. */
    fun candidates(registry: String, timeZoneId: String = TimeZone.getDefault().id): List<String> {
        if (registry != GHCR) return listOf(registry)
        return if (isLikelyInChina(timeZoneId)) GHCR_CHINA_MIRRORS + GHCR else listOf(GHCR) + GHCR_CHINA_MIRRORS
    }

    /** [imageRef] on each of its registry's [candidates], preferred first. */
    fun imageRefCandidates(imageRef: String, timeZoneId: String = TimeZone.getDefault().id): List<String> {
        val registry = imageRef.substringBefore('/')
        return candidates(registry, timeZoneId).map { it + imageRef.removePrefix(registry) }
    }

    /** Rewrites an image reference like "ghcr.io/org/name:tag" to use the preferred registry. */
    fun preferredImageRef(imageRef: String, timeZoneId: String = TimeZone.getDefault().id): String {
        val registry = imageRef.substringBefore('/')
        val preferred = candidates(registry, timeZoneId).first()
        return if (preferred == registry) imageRef else preferred + imageRef.removePrefix(registry)
    }
}
