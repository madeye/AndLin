package tech.anl.library.companion

import android.content.Context
import android.content.SharedPreferences
import tech.anl.library.utils.defaultSharedPreferences

enum class CompanionState {
    NOT_INSTALLED,
    /** Installed but a different major version than the release: blocking, must update first. */
    UPDATE_REQUIRED,
    /** A newer minor/patch release exists: offer it, throttled and non-blocking. */
    UPDATE_AVAILABLE,
    READY
}

/** Pure version logic, unit tested. */
object CompanionVersions {
    /** "2.10.1" -> [2, 10, 1]; any non-digit run separates components; null when there are no digits. */
    fun parse(version: String?): List<Int>? {
        if (version == null) return null
        val parts = Regex("""\d+""").findAll(version).map { it.value.toBigInteger().min(Int.MAX_VALUE.toBigInteger()).toInt() }.toList()
        return parts.ifEmpty { null }
    }

    fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /**
     * Classifies an installed companion against the release's version.txt. [latest] may hold a
     * versionName ("2.2") or, unverified for QEMU, a bare versionCode ("26"); a bare integer
     * compared against a dotted versionName is matched against [installedCode] instead.
     */
    fun classify(installedName: String?, installedCode: Long?, latest: String?): CompanionState {
        if (installedName == null && installedCode == null) return CompanionState.NOT_INSTALLED
        val latestParts = parse(latest?.trim()) ?: return CompanionState.READY
        val latestIsBareCode = latest!!.trim().all { it.isDigit() }
        val installedParts = parse(installedName)

        if (latestIsBareCode && (installedParts == null || installedParts.size > 1) && installedCode != null) {
            return if (installedCode < latestParts[0].toLong()) CompanionState.UPDATE_AVAILABLE else CompanionState.READY
        }
        if (installedParts == null) return CompanionState.UPDATE_REQUIRED
        if (compare(installedParts, latestParts) >= 0) return CompanionState.READY
        return if (installedParts[0] != latestParts[0]) CompanionState.UPDATE_REQUIRED else CompanionState.UPDATE_AVAILABLE
    }

    /** Throttle for the optional-update nudge. */
    fun shouldNudge(lastNudgedAtMs: Long, nowMs: Long, intervalMs: Long): Boolean =
        lastNudgedAtMs <= 0 || nowMs - lastNudgedAtMs >= intervalMs || nowMs < lastNudgedAtMs
}

/**
 * Install/update state of the companion apps. The latest version comes from the release's
 * version.txt (through GitHub mirrors in China), cached briefly so screens can call [state] freely.
 */
class CompanionInstaller(context: Context, private val downloader: CompanionDownloader = CompanionDownloader()) {
    private val context = context.applicationContext
    private val prefs: SharedPreferences get() = context.defaultSharedPreferences

    fun installedVersionName(app: CompanionApp): String? = CompanionControlClient.installedVersion(context, app)?.first

    suspend fun latestVersion(app: CompanionApp, channel: String): String? {
        val key = "companion_latest_${app.name.lowercase()}_$channel"
        val atKey = "${key}_at"
        val cached = prefs.getString(key, null)
        val cachedAt = prefs.getLong(atKey, 0L)
        val now = System.currentTimeMillis()
        if (cached != null && now - cachedAt in 0 until VERSION_CACHE_MS) return cached
        val fetched = downloader.fetchText(app.versionUrl(channel))?.trim()?.lineSequence()?.firstOrNull()?.trim()
        if (!fetched.isNullOrEmpty() && CompanionVersions.parse(fetched) != null) {
            prefs.edit().putString(key, fetched).putLong(atKey, now).apply()
            return fetched
        }
        return cached
    }

    suspend fun state(app: CompanionApp, channel: String): CompanionState {
        val installed = CompanionControlClient.installedVersion(context, app) ?: return CompanionState.NOT_INSTALLED
        return CompanionVersions.classify(installed.first, installed.second, latestVersion(app, channel))
    }

    /** True at most once per [NUDGE_INTERVAL_MS] per companion; call [markUpdateNudged] when shown. */
    fun shouldNudgeForUpdate(app: CompanionApp): Boolean =
        CompanionVersions.shouldNudge(prefs.getLong(nudgeKey(app), 0L), System.currentTimeMillis(), NUDGE_INTERVAL_MS)

    fun markUpdateNudged(app: CompanionApp) {
        prefs.edit().putLong(nudgeKey(app), System.currentTimeMillis()).apply()
    }

    /** Clears the cached version so the next [state] refetches (e.g. right after installing). */
    fun invalidate(app: CompanionApp) {
        val editor = prefs.edit()
        CompanionChannel.KNOWN_CHANNELS.forEach {
            val key = "companion_latest_${app.name.lowercase()}_$it"
            editor.remove(key).remove("${key}_at")
        }
        editor.apply()
    }

    private fun nudgeKey(app: CompanionApp) = "companion_update_nudged_at_${app.name.lowercase()}"

    companion object {
        const val VERSION_CACHE_MS = 6L * 60 * 60 * 1000
        const val NUDGE_INTERVAL_MS = 3L * 24 * 60 * 60 * 1000

        /**
         * Whether [app] can run on this device at all: the VM needs API 34 and the virtualization
         * framework feature; QEMU ships arm64-only native code and needs API 28.
         */
        fun isSupportedOnThisDevice(context: Context, app: CompanionApp): Boolean =
            app.executionType.isSupportedOnThisDevice(context)
    }
}
