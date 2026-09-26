package tech.anl.library.model.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import tech.anl.customlibrary.BuildConfig
import tech.anl.library.utils.GithubMirror
import tech.anl.library.utils.Logger
import tech.anl.library.utils.LogcatLogger
import tech.anl.library.utils.AnlFiles
import java.io.IOException
import java.net.UnknownHostException

class UrlProvider {
    fun getBaseUrl(): String {
        return "https://api.github.com/"
    }
}

class GithubApiClient(
    val anlFiles: AnlFiles,
    private val urlProvider: UrlProvider = UrlProvider(),
    private val logger: Logger = LogcatLogger(),
    private val defaultRelease: String = BuildConfig.DEFAULT_RELEASE
) {
    private val client = OkHttpClient()
    private val latestResults: HashMap<String, ReleasesResponse?> = hashMapOf()

    /**
     * DEFAULT_RELEASE is either one release for every repo ("tags/v7.7.9", "latest") or
     * per-distribution entries ("ubuntu:tags/v0.0.21,debian:tags/v0.0.15").
     */
    private fun getReleaseToUseForRepo(repo: String): String = releaseForRepo(defaultRelease, repo)

    private fun pinnedTag(repo: String): String? =
        getReleaseToUseForRepo(repo).takeIf { it.startsWith("tags/") }?.removePrefix("tags/")

    // Pinned releases need no API call: their download URLs are predictable, and skipping
    // api.github.com avoids its rate limit and works through GitHub download mirrors.
    private fun releaseDownloadUrl(repo: String, tag: String, assetName: String): String =
        GithubMirror.preferred("https://github.com/CypherpunkArmory/UserLAnd-Assets-$repo/releases/download/$tag/$assetName")

    @Throws(IOException::class)
    suspend fun getAssetsListDownloadUrl(repo: String): String = withContext(Dispatchers.IO) {
        pinnedTag(repo)?.let { return@withContext releaseDownloadUrl(repo, it, "${anlFiles.getArchType()}-assets.txt") }
        val result = latestResults[repo] ?: queryLatestRelease(repo)

        return@withContext GithubMirror.preferred(result.assets.find { it.name == "${anlFiles.getArchType()}-assets.txt" }!!.downloadUrl)
    }

    @Throws(IOException::class)
    suspend fun getLatestReleaseVersion(repo: String): String = withContext(Dispatchers.IO) {
        pinnedTag(repo)?.let { return@withContext it }
        val result = latestResults[repo] ?: queryLatestRelease(repo)

        return@withContext result.tag
    }

    @Throws(IOException::class)
    suspend fun getAssetEndpoint(assetType: String, repo: String): String = withContext(Dispatchers.IO) {
        val assetName = "${anlFiles.getArchType()}-$assetType"
        pinnedTag(repo)?.let { return@withContext releaseDownloadUrl(repo, it, assetName) }
        val result = latestResults[repo] ?: queryLatestRelease(repo)

        return@withContext GithubMirror.preferred(result.assets.find { it.name == assetName }!!.downloadUrl)
    }

    // Query latest release data and memoize results.
    @Throws(IOException::class, UnknownHostException::class)
    private suspend fun queryLatestRelease(repo: String): ReleasesResponse = withContext(Dispatchers.IO) {
        val releaseToUse = getReleaseToUseForRepo(repo)
        val base = urlProvider.getBaseUrl()
        val url = base + "repos/CypherpunkArmory/UserLAnd-Assets-$repo/releases/$releaseToUse"
        val moshi = Moshi.Builder().build()
        val adapter = moshi.adapter(ReleasesResponse::class.java)
        val request = Request.Builder()
                .url(url)
                .build()
        val response = try {
            client.newCall(request).execute()
        } catch (err: UnknownHostException) {
            logger.addExceptionBreadcrumb(err)
            throw err
        }
        if (!response.isSuccessful) {
            val err = IOException("Unexpected code: $response")
            logger.addExceptionBreadcrumb(err)
            throw err
        }

        val result = adapter.fromJson(response.body!!.source())!!
        latestResults[repo] = result
        return@withContext result
    }

    @JsonClass(generateAdapter = true)
    internal data class ReleasesResponse(
        val url: String,
        val name: String,
        @Json(name = "tag_name") val tag: String,
        val assets: List<GithubAsset>
    )

    @JsonClass(generateAdapter = true)
    internal data class GithubAsset(
        val url: String,
        val name: String,
        @Json(name = "browser_download_url") val downloadUrl: String
    )
}
internal fun releaseForRepo(defaultRelease: String, repo: String): String {
    if (!defaultRelease.contains(':')) return defaultRelease
    return defaultRelease.split(',')
        .map { it.trim() }
        .firstOrNull { it.substringBefore(':').equals(repo, ignoreCase = true) }
        ?.substringAfter(':')
        ?: "latest"
}
