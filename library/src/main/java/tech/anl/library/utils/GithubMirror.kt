package tech.anl.library.utils

import java.util.TimeZone

/**
 * Download proxies for GitHub release and raw URLs, used from Chinese time zones where
 * github.com downloads are slow or blocked (see [RegistryMirror] for the same idea applied to
 * container images). Each proxy takes the full original URL appended to its own, serves the
 * same bytes and supports Range requests. They are community-run, so callers try every
 * candidate in order and finish with github.com itself.
 */
object GithubMirror {
    val chinaProxies = listOf(
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://ghproxy.net/"
    )

    private val proxiableHosts = setOf(
        "github.com",
        "raw.githubusercontent.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com"
    )

    fun isProxiable(url: String): Boolean {
        val host = url.removePrefix("https://").substringBefore('/')
        return url.startsWith("https://") && host in proxiableHosts
    }

    /** URLs to try for [url], preferred first. */
    fun candidates(url: String, timeZoneId: String = TimeZone.getDefault().id): List<String> {
        if (!isProxiable(url) || !RegistryMirror.isLikelyInChina(timeZoneId)) return listOf(url)
        return chinaProxies.map { it + url } + url
    }

    /** The single URL to hand to something that can't fall back itself, like DownloadManager. */
    fun preferred(url: String, timeZoneId: String = TimeZone.getDefault().id): String =
        candidates(url, timeZoneId).first()
}
