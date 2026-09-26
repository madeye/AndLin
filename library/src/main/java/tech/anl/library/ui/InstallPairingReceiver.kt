package tech.anl.library.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import tech.anl.library.adb.AdbSetupFlow

/**
 * Receives the pairing code typed into the "Enter pairing code" RemoteInput action of the
 * Wireless-debugging setup notification (see [AdbSetupFlow]), so the user never has to leave the
 * Settings pairing dialog. Unexported: only our own PendingIntent targets it.
 */
class InstallPairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AdbSetupFlow.ACTION_INSTALL_PAIR) return
        val code = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(AdbSetupFlow.KEY_PAIRING_CODE)
            ?.toString()
            .orEmpty()
        if (code.isBlank()) return
        AdbSetupFlow.submitPairingCode(code)
        // Replace the notification so the system stops showing the reply spinner.
        AdbSetupFlow.acknowledgeCode(context)
    }
}
