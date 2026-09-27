package tech.anl.library.companion

import android.content.Context
import android.os.Build
import android.provider.Settings
import tech.anl.library.utils.defaultSharedPreferences

/**
 * Android 12+ kills "phantom" (non-app child) processes beyond a small budget, which is exactly
 * what proot, the SSH server and everything a user runs inside a proot session are. The symptom is the
 * session silently dying mid-work. It can be turned off once through ADB; see
 * [tech.anl.library.adb.AdbJobs.disablePhantomProcessKiller].
 */
object PhantomProcessKiller {
    private const val FLAG = "settings_enable_monitor_phantom_procs"
    private const val PREF_FIX_APPLIED = "phantom_killer_fix_applied"
    private const val PREF_DONT_ASK = "phantom_killer_dont_ask"
    private const val PREF_LAST_ASKED = "phantom_killer_last_asked"

    /** Whether the killer is (probably) active for this app's processes. */
    fun isActive(context: Context): Boolean {
        val flag = Settings.Global.getString(context.contentResolver, FLAG)
        val maxPhantom = readMaxPhantomProcesses()
        return isActive(Build.VERSION.SDK_INT, flag, maxPhantom, context.defaultSharedPreferences.getBoolean(PREF_FIX_APPLIED, false))
    }

    /**
     * Pure decision, unit tested.
     * [monitorFlag] is Settings.Global settings_enable_monitor_phantom_procs ("true"/"false"/"1"/"0"
     * or null = default on); [maxPhantom] is activity_manager/max_phantom_processes when readable.
     */
    fun isActive(sdk: Int, monitorFlag: String?, maxPhantom: Int?, fixRecorded: Boolean): Boolean {
        if (sdk < 31) return false
        if (maxPhantom != null && maxPhantom >= 1_000_000) return false
        if (sdk >= 32) {
            // Android 12L added the developer toggle "Disable child process restrictions".
            val v = monitorFlag?.trim()?.lowercase()
            // Our fix sets this flag, so a recorded fix with the flag back on means it was reset
            // (e.g. by an OS update) and the killer is active again.
            return !(v == "false" || v == "0")
        }
        // Android 12 (API 31): only device_config helps, and apps usually can't read it.
        return !fixRecorded
    }

    fun markFixApplied(context: Context) {
        context.defaultSharedPreferences.edit().putBoolean(PREF_FIX_APPLIED, true).apply()
    }

    fun setDontAskAgain(context: Context) {
        context.defaultSharedPreferences.edit().putBoolean(PREF_DONT_ASK, true).apply()
    }

    /** Whether a "did your session crash?" question may be asked now (throttled to once a day). */
    fun mayAsk(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val prefs = context.defaultSharedPreferences
        if (prefs.getBoolean(PREF_DONT_ASK, false)) return false
        val last = prefs.getLong(PREF_LAST_ASKED, 0L)
        return last <= 0 || nowMs - last >= ASK_INTERVAL_MS || nowMs < last
    }

    fun markAsked(context: Context, nowMs: Long = System.currentTimeMillis()) {
        context.defaultSharedPreferences.edit().putLong(PREF_LAST_ASKED, nowMs).apply()
    }

    const val ASK_INTERVAL_MS = 24L * 60 * 60 * 1000

    /** DeviceConfig is a system API; this only works where the platform lets apps read it. */
    private fun readMaxPhantomProcesses(): Int? = try {
        val dc = Class.forName("android.provider.DeviceConfig")
        val getString = dc.getMethod("getString", String::class.java, String::class.java, String::class.java)
        (getString.invoke(null, "activity_manager", "max_phantom_processes", null) as String?)?.trim()?.toIntOrNull()
    } catch (e: Throwable) {
        null
    }
}
