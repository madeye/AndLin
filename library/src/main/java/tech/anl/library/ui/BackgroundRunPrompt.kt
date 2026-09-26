package tech.anl.library.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.fragment.app.FragmentActivity
import tech.anl.library.R
import tech.anl.library.ServerService
import tech.anl.library.utils.defaultSharedPreferences

/**
 * Servers have to keep answering with the screen off, which Android's battery optimization (and
 * vendor power managers on top of it, like HyperOS's) get in the way of. Asks once, the first time
 * the app is open while a session is running and ServerBox isn't exempt yet.
 */
object BackgroundRunPrompt {
    private const val ASKED_KEY = "serverbox_background_prompt_asked"

    fun maybeOffer(activity: FragmentActivity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val prefs = activity.defaultSharedPreferences
        if (prefs.getBoolean(ASKED_KEY, false)) return
        if (!prefs.getBoolean("pref_keep_sessions_running", true)) return
        if (ServerService.autostartSessionIds(activity).isEmpty()) return // nothing running
        if (isIgnoringBatteryOptimizations(activity)) return
        prefs.edit().putBoolean(ASKED_KEY, true).apply()

        val message = activity.getString(R.string.background_prompt_message) +
            if (isXiaomi()) "\n\n" + activity.getString(R.string.background_prompt_xiaomi) else ""
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.background_prompt_title)
            .setMessage(message)
            .setPositiveButton(R.string.background_prompt_allow) { _, _ -> requestExemption(activity) }
            .setNegativeButton(R.string.background_prompt_later, null)
        if (isXiaomi()) {
            dialog.setNeutralButton(R.string.background_prompt_app_settings) { _, _ -> openAppSettings(activity) }
        }
        dialog.show()
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)

    private fun isXiaomi() = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)

    @SuppressLint("BatteryLife") // A background server is the use case this exemption exists for.
    fun requestExemption(activity: FragmentActivity) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}"))
        try {
            activity.startActivity(intent)
        } catch (err: ActivityNotFoundException) {
            activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun openAppSettings(activity: FragmentActivity) {
        activity.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null))
        )
    }
}
