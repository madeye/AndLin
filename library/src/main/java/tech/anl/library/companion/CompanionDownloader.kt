package tech.anl.library.companion

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import tech.anl.library.utils.GithubMirror
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Downloads companion release assets. Every GitHub URL is tried through [GithubMirror]'s
 * candidates in order (proxies first in China, github.com last), each with its own retries.
 * HTTP/1.1 only: GitHub's release CDN has reset long HTTP/2 transfers mid-stream.
 */
class CompanionDownloader(
    private val http: OkHttpClient = defaultClient(),
    private val candidates: (String) -> List<String> = { GithubMirror.candidates(it) },
    private val attemptsPerCandidate: Int = 2,
    private val retryDelayMs: Long = 1_500
) {
    class DownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

    /** The body of a small text asset (version.txt), or null if every candidate failed. */
    suspend fun fetchText(url: String): String? = withContext(Dispatchers.IO) {
        for (candidate in candidates(url)) {
            repeat(attemptsPerCandidate) { attempt ->
                ensureActive()
                try {
                    http.newCall(Request.Builder().url(candidate).build()).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val body = resp.body?.string()
                            // Some proxies answer errors with an HTML page and 200.
                            if (body != null && body.length < 256 && !body.contains('<')) return@withContext body
                        }
                        Log.w(TAG, "fetchText $candidate: HTTP ${resp.code}")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    Log.w(TAG, "fetchText $candidate attempt ${attempt + 1} failed: $e")
                }
                if (attempt < attemptsPerCandidate - 1) delay(retryDelayMs)
            }
        }
        null
    }

    /**
     * Downloads [url] into [dest], trying each mirror candidate. [accept] validates a finished
     * download (e.g. APK package/signature checks) and returns an error message to reject it and
     * move on to the next candidate. [onProgress] gets (bytesSoFar, totalOrMinus1).
     */
    suspend fun download(
        url: String,
        dest: File,
        onProgress: (Long, Long) -> Unit,
        accept: (file: File, fromCandidate: String) -> String? = { _, _ -> null }
    ): File = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".part")
        var lastError: Exception? = null
        for (candidate in candidates(url)) {
            for (attempt in 0 until attemptsPerCandidate) {
                ensureActive()
                try {
                    fetchTo(candidate, tmp, onProgress)
                    val rejection = accept(tmp, candidate)
                    if (rejection != null) {
                        Log.w(TAG, "Rejected download from $candidate: $rejection")
                        lastError = DownloadException(rejection)
                        tmp.delete()
                        break // a bad file from this candidate won't get better by retrying it
                    }
                    dest.delete()
                    if (!tmp.renameTo(dest)) throw DownloadException("Could not move the download into place")
                    return@withContext dest
                } catch (e: CancellationException) {
                    tmp.delete()
                    throw e
                } catch (e: IOException) {
                    Log.w(TAG, "download $candidate attempt ${attempt + 1} failed: $e")
                    lastError = e
                    tmp.delete()
                    if (attempt < attemptsPerCandidate - 1) delay(retryDelayMs)
                }
            }
        }
        throw lastError as? DownloadException ?: DownloadException(lastError?.message ?: "Download failed", lastError)
    }

    private fun fetchTo(url: String, file: File, onProgress: (Long, Long) -> Unit) {
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw DownloadException("HTTP ${resp.code} from ${hostOf(url)}")
            val body = resp.body ?: throw DownloadException("Empty response from ${hostOf(url)}")
            val total = body.contentLength()
            var done = 0L
            var lastReport = 0L
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 200) { lastReport = now; onProgress(done, total) }
                    }
                }
            }
            if (total > 0 && done != total) throw DownloadException("Download was cut short ($done of $total bytes)")
            onProgress(done, total)
        }
    }

    companion object {
        private const val TAG = "CompanionDownloader"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/')
    }
}

/**
 * Checks a downloaded companion APK before it is installed. The China download proxies are
 * third-party, so an APK that didn't come straight from github.com must be signed by a known
 * companion signing key (or by whoever signed the copy already installed).
 */
object CompanionApkVerifier {
    /** SHA-256 of the signing certificates of the published companion releases. */
    val KNOWN_SIGNERS: Map<CompanionApp, Set<String>> = mapOf(
        CompanionApp.VM to setOf("2c8cf827e3bea035ca991be2617e56e421e1aae3581e88d530dd6ed07ba5f1f2"),
        CompanionApp.QEMU to setOf("61aeed5516d93a6d475126dd1d8501a91e58e96763ce66beb5b7c80dcf9c4465")
    )

    /** Returns null when [apk] is acceptable, otherwise a user-presentable reason. */
    fun verify(context: Context, apk: File, app: CompanionApp, fromCandidate: String): String? {
        val pm = context.packageManager
        val info = archiveInfo(pm, apk) ?: return "The downloaded file is not a valid app package."
        if (info.packageName != app.packageName) {
            return "The downloaded package is ${info.packageName}, expected ${app.packageName}."
        }
        val signers = signerDigests(info)
        if (signers.isEmpty()) return "The downloaded package is not signed."
        val installedSigners = try {
            signerDigests(installedInfo(pm, app.packageName))
        } catch (e: PackageManager.NameNotFoundException) {
            emptySet()
        }
        if (installedSigners.isNotEmpty() && signers.intersect(installedSigners).isEmpty()) {
            return "The downloaded ${app.releaseAssetName} is signed differently from the installed one. " +
                "Uninstall the installed ${app.packageName} first."
        }
        val direct = CompanionDownloader.hostOf(fromCandidate).let { it == "github.com" || it.endsWith(".githubusercontent.com") }
        if (!direct && installedSigners.isEmpty() && signers.intersect(KNOWN_SIGNERS[app].orEmpty()).isEmpty()) {
            return "The package from ${CompanionDownloader.hostOf(fromCandidate)} has an unknown signature."
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, apk: File): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return pm.getPackageArchiveInfo(apk.absolutePath, flags)
    }

    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager, pkg: String): PackageInfo {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return pm.getPackageInfo(pkg, flags)
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val si = info.signingInfo
            when {
                si == null -> info.signatures?.toList().orEmpty()
                si.hasMultipleSigners() -> si.apkContentsSigners.toList()
                else -> si.signingCertificateHistory.toList()
            }
        } else {
            info.signatures?.toList().orEmpty()
        }
        return certs.map { sha256Hex(it.toByteArray()) }.toSet()
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
