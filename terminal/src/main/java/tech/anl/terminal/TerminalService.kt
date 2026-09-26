package tech.anl.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import tech.anl.terminal.TerminalLauncher.getSpec

/** Keeps the app process alive while terminal sessions run, and shows their notification. */
class TerminalService : Service(), TerminalSessions.Observer {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        TerminalSessions.addObserver(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must enter the foreground promptly on every start, even if we stop right after.
        enterForeground()
        if (intent?.action == ACTION_EXIT) {
            TerminalLauncher.closeAll(this)
            return START_NOT_STICKY
        }
        intent?.getSpec()?.let { TerminalSessions.getOrCreate(it) }
        if (TerminalSessions.isEmpty) stopSelfNow() else updateNotification()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        TerminalSessions.removeObserver(this)
        super.onDestroy()
    }

    override fun onSessionsChanged() {
        if (TerminalSessions.isEmpty) stopSelfNow() else updateNotification()
    }

    private fun stopSelfNow() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun enterForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val sessions = TerminalSessions.all
        val latest = sessions.lastOrNull()
        val openIntent = Intent(this, TerminalActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Only the key: opening from the notification must never restart an exited session.
        latest?.let { openIntent.putExtra(TerminalLauncher.EXTRA_SESSION_KEY, it.spec.sessionKey) }
        val open = PendingIntent.getActivity(this, 0, openIntent, PENDING_FLAGS)
        val exit = PendingIntent.getService(
            this, 1, Intent(this, TerminalService::class.java).setAction(ACTION_EXIT), PENDING_FLAGS,
        )
        val text = resources.getQuantityString(R.plurals.terminal_sessions_running, sessions.size, sessions.size)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_terminal_notification)
            .setContentTitle(latest?.spec?.title ?: getString(R.string.terminal_notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.terminal_action_open), open)
            .addAction(0, getString(R.string.terminal_action_exit), exit)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.terminal_channel_name), NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "tech.anl.terminal.sessions"
        private const val NOTIFICATION_ID = 0x7e12
        private const val ACTION_EXIT = "tech.anl.terminal.action.EXIT"
        private const val PENDING_FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    }
}
