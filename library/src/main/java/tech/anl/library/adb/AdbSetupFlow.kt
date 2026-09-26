package tech.anl.library.adb

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import io.github.muntashirakon.adb.AdbPairingRequiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import tech.anl.library.R
import tech.anl.library.ui.InstallPairingReceiver

/**
 * Gets this app an ADB connection to its own device through Wireless debugging, pairing first if
 * needed. Reusable for any job that needs shell-user rights once (installing a companion app,
 * granting it development permissions, turning off the phantom process killer).
 *
 * While the user is in Settings with the pairing dialog open, a notification with a RemoteInput
 * "Enter pairing code" action lets them type the six digits without leaving Settings; the reply
 * arrives at [InstallPairingReceiver], which calls [submitPairingCode].
 */
object AdbSetupFlow {
    private const val TAG = "AdbSetupFlow"
    const val CHANNEL_ID = "serverbox_adb_setup"
    const val NOTIFICATION_ID = 4711
    const val ACTION_INSTALL_PAIR = "tech.anl.library.INSTALL_PAIR"
    const val KEY_PAIRING_CODE = "pairing_code"

    sealed class Stage {
        object Idle : Stage()
        /** Developer options / Wireless debugging must be switched on (or Wi-Fi joined). */
        object NeedWirelessDebugging : Stage()
        /** Waiting for the user to open "Pair device with pairing code". */
        object WaitingForPairingDialog : Stage()
        /** The pairing dialog is open; waiting for the six-digit code. */
        data class WaitingForCode(val wrongCodeBefore: Boolean) : Stage()
        object Pairing : Stage()
        object Connecting : Stage()
        object Connected : Stage()
        data class Failed(val message: String) : Stage()
    }

    private val _stage = MutableStateFlow<Stage>(Stage.Idle)
    val stage: StateFlow<Stage> = _stage.asStateFlow()

    private val codes = Channel<String>(Channel.CONFLATED)

    /** Delivers a pairing code typed in the notification or in the app. */
    fun submitPairingCode(code: String) {
        val digits = code.filter { it.isDigit() }
        if (digits.isNotEmpty()) codes.trySend(digits)
    }

    /**
     * Returns a connected [AdbClient], guiding the user through enabling wireless debugging and
     * pairing when necessary. Suspends until connected; cancel the calling coroutine to abort.
     * Throws [AdbException] on unrecoverable errors.
     */
    suspend fun connect(context: Context): AdbClient {
        val ctx = context.applicationContext
        val adb = AdbClient.get(ctx)
        try {
            if (adb.isConnected) return adb.also { _stage.value = Stage.Connected }
            while (true) {
                awaitWirelessDebugging(ctx)
                // Already paired from an earlier run? Connecting is enough then.
                _stage.value = Stage.Connecting
                if (tryConnect(ctx, adb, discoveryTimeoutMs = 4_000)) break
                pair(ctx, adb)
                _stage.value = Stage.Connecting
                // adbd re-advertises the connect service after pairing; give it a moment.
                var connected = false
                for (i in 0 until 5) {
                    if (tryConnect(ctx, adb, discoveryTimeoutMs = 5_000)) { connected = true; break }
                    delay(1_000)
                }
                if (connected) break
                // Paired but could not connect: usually wireless debugging got switched off. Loop.
                Log.w(TAG, "Paired but could not connect; starting over")
            }
            _stage.value = Stage.Connected
            cancelNotification(ctx)
            return adb
        } catch (e: CancellationException) {
            _stage.value = Stage.Idle
            cancelNotification(ctx)
            throw e
        } catch (e: Exception) {
            _stage.value = Stage.Failed(e.message ?: e.javaClass.simpleName)
            cancelNotification(ctx)
            throw if (e is AdbException) e else AdbException(e.message ?: "ADB setup failed", e)
        }
    }

    fun reset() {
        _stage.value = Stage.Idle
        while (codes.tryReceive().isSuccess) { /* drain stale codes */ }
    }

    private suspend fun awaitWirelessDebugging(ctx: Context) {
        if (AdbDiscovery.isWirelessDebuggingEnabled(ctx)) return
        _stage.value = Stage.NeedWirelessDebugging
        showNotification(ctx, ctx.getString(R.string.companion_adb_notif_enable_wireless), withInput = false)
        while (!AdbDiscovery.isWirelessDebuggingEnabled(ctx)) delay(1_000)
    }

    private suspend fun tryConnect(ctx: Context, adb: AdbClient, discoveryTimeoutMs: Long): Boolean {
        val endpoint = AdbDiscovery.findOnce(ctx, AdbDiscovery.CONNECT, discoveryTimeoutMs) ?: return false
        for (host in AdbDiscovery.connectHosts(endpoint)) {
            try {
                if (adb.connect(host, endpoint.port)) return true
            } catch (e: AdbPairingRequiredException) {
                return false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "connect $host:${endpoint.port} failed: ${e.message}")
            }
        }
        return false
    }

    /** Waits for the pairing dialog and a code, and pairs. Loops on wrong codes. */
    private suspend fun pair(ctx: Context, adb: AdbClient) = coroutineScope {
        var latest: AdbEndpoint? = null
        val watcher = launch {
            AdbDiscovery.services(ctx, AdbDiscovery.PAIRING).collect { ep ->
                latest = ep
                if (_stage.value == Stage.WaitingForPairingDialog) {
                    _stage.value = Stage.WaitingForCode(wrongCodeBefore = false)
                    showNotification(ctx, ctx.getString(R.string.companion_adb_notif_enter_code), withInput = true)
                }
            }
        }
        try {
            _stage.value = Stage.WaitingForPairingDialog
            showNotification(ctx, ctx.getString(R.string.companion_adb_notif_open_pairing), withInput = true)
            while (true) {
                val code = codes.receive()
                val endpoint = latest ?: run {
                    // Code typed before the dialog was seen: wait a bit for discovery.
                    var waited = 0
                    while (latest == null && waited < 10_000) { delay(250); waited += 250 }
                    latest
                }
                if (endpoint == null) {
                    _stage.value = Stage.WaitingForPairingDialog
                    showNotification(ctx, ctx.getString(R.string.companion_adb_notif_open_pairing), withInput = true)
                    continue
                }
                _stage.value = Stage.Pairing
                showNotification(ctx, ctx.getString(R.string.companion_adb_notif_pairing), withInput = false)
                if (pairWith(adb, endpoint, code)) return@coroutineScope
                _stage.value = Stage.WaitingForCode(wrongCodeBefore = true)
                showNotification(ctx, ctx.getString(R.string.companion_adb_notif_wrong_code), withInput = true)
            }
        } finally {
            watcher.cancel()
        }
    }

    /** Tries the pairing port on loopback, then on the advertised address. */
    private suspend fun pairWith(adb: AdbClient, endpoint: AdbEndpoint, code: String): Boolean {
        for (host in AdbDiscovery.connectHosts(endpoint)) {
            try {
                return adb.pair(host, endpoint.port, code)
            } catch (e: AdbException) {
                Log.w(TAG, "pair via $host failed: ${e.message}")
                // A rejected code won't be accepted on another address either.
                if (e.message?.contains("wrong pairing code") == true) return false
            }
        }
        return false
    }

    // ------------------------------------------------------------------ notification

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.companion_adb_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = ctx.getString(R.string.companion_adb_channel_description) }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun showNotification(ctx: Context, text: String, withInput: Boolean, autoCancel: Boolean = false) {
        ensureChannel(ctx)
        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_companion_key)
            .setContentTitle(ctx.getString(R.string.companion_adb_notif_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOnlyAlertOnce(true)
            .setOngoing(!autoCancel)
            .setAutoCancel(autoCancel)
        ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let { launch ->
            builder.setContentIntent(PendingIntent.getActivity(ctx, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()))
        }
        if (withInput) {
            val remoteInput = RemoteInput.Builder(KEY_PAIRING_CODE)
                .setLabel(ctx.getString(R.string.companion_adb_code_hint))
                .build()
            val intent = Intent(ACTION_INSTALL_PAIR).setClass(ctx, InstallPairingReceiver::class.java)
            // RemoteInput needs a mutable PendingIntent so the system can add the typed text.
            val pi = PendingIntent.getBroadcast(ctx, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag())
            val action = NotificationCompat.Action.Builder(
                R.drawable.ic_companion_key, ctx.getString(R.string.companion_adb_enter_code), pi
            ).addRemoteInput(remoteInput).setAllowGeneratedReplies(false).build()
            builder.addAction(action)
        }
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS denied: the in-app code field still works.
            Log.w(TAG, "Cannot post the pairing notification", e)
        }
    }

    /** Called by [InstallPairingReceiver] to stop the RemoteInput spinner. */
    fun acknowledgeCode(ctx: Context) {
        showNotification(ctx, ctx.getString(R.string.companion_adb_notif_pairing), withInput = false)
    }

    fun cancelNotification(ctx: Context) {
        NotificationManagerCompat.from(ctx).cancel(NOTIFICATION_ID)
    }

    private fun mutableFlag() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    private fun immutableFlag() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
}
