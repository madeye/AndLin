package tech.anl.library.desktop

import android.content.Context

/** Stand-in for builds made with -PandlinVnc=false, which leave the VNC viewer out entirely. */
object DesktopViewer {
    const val isAvailable = false

    @Suppress("UNUSED_PARAMETER")
    fun launch(
        context: Context,
        host: String,
        port: Int,
        password: String?,
        inputMode: String,
        hideToolbar: Boolean,
        hideExtraKeys: Boolean,
        title: String
    ): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun close(context: Context) = Unit
}
