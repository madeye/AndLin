package tech.anl.library.utils

import android.content.Context
import androidx.preference.PreferenceManager
import tech.anl.library.BuildConfig
import tech.anl.library.desktop.DesktopViewer

/**
 * AndLin is a Linux server first: sessions are SSH terminals, and filesystems are the headless
 * server images. Graphical desktops (VNC, the XFCE/LXDE images, GUI-only apps) appear only when
 * the build includes the viewer and the user has switched desktop support on in settings.
 */
object DesktopSupport {
    const val PREF_KEY = "pref_enable_desktop"

    val isBuiltIn: Boolean get() = BuildConfig.VNC_ENABLED && DesktopViewer.isAvailable

    fun isEnabled(context: Context): Boolean =
        isBuiltIn && PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_KEY, false)
}
