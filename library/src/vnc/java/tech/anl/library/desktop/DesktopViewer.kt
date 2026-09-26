package tech.anl.library.desktop

import android.content.Context
import android.content.Intent
import tech.anl.vnc.app.VncActivity

/** Opens the built-in VNC viewer. The no-VNC build swaps in a version that has none. */
object DesktopViewer {
    const val isAvailable = true

    fun launch(
        context: Context,
        host: String,
        port: Int,
        password: String?,
        inputMode: String,
        hideToolbar: Boolean,
        hideExtraKeys: Boolean,
        title: String
    ): Boolean {
        val intent = VncActivity.createIntent(context, host, port, password, inputMode, hideToolbar, hideExtraKeys, title)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(intent)
        return true
    }

    fun close(context: Context) = VncActivity.closeViewer(context)
}
