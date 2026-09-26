package tech.anl.library

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tech.anl.library.model.repositories.AnlDatabase
import tech.anl.library.utils.defaultSharedPreferences

/** Restarts the sessions that were running before a reboot, when "Start on boot" is on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        if (!context.defaultSharedPreferences.getBoolean("pref_start_on_boot", false)) return
        if (ServerService.autostartSessionIds(context).isEmpty()) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Nothing survived the reboot, whatever the database last recorded.
                AnlDatabase.getInstance(context).sessionDao().resetSessionActivity()
                val serviceIntent = Intent(context, ServerService::class.java)
                    .putExtra("type", "startAutostartSessions")
                ContextCompat.startForegroundService(context, serviceIntent)
            } finally {
                pending.finish()
            }
        }
    }
}
