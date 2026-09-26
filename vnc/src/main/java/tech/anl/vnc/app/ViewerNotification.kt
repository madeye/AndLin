package tech.anl.vnc.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import tech.anl.vnc.R
import tech.anl.vnc.rfb.JpegDecoder

/** The "desktop is open" notification: tap to return, action to close the viewer. */
internal object ViewerNotification {
    private const val CHANNEL_ID = "vnc_viewer"
    private const val NOTIFICATION_ID = 0x564e43 // "VNC"

    fun show(context: Context, title: String) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.vnc_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, VncActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags
        )
        val close = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, VncCloseReceiver::class.java),
            flags
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.vnc_ic_desktop)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.vnc_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, context.getString(R.string.vnc_close_viewer), close)
            .build()
        try {
            nm.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied: the viewer works without the notification.
        }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}

/** Handles the notification's "Close viewer" action. */
class VncCloseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        VncActivity.closeViewer(context)
    }
}

/** Tight JPEG rectangles, decoded with the platform codec. */
internal object BitmapJpegDecoder : JpegDecoder {
    override fun decode(data: ByteArray, width: Int, height: Int): IntArray? {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size, opts) ?: return null
        try {
            if (bmp.width != width || bmp.height != height) return null
            val px = IntArray(width * height)
            bmp.getPixels(px, 0, width, 0, 0, width, height)
            return px
        } finally {
            bmp.recycle()
        }
    }
}
