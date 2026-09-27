package tech.anl.library.ui

import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams

/**
 * Inset handling for edge-to-edge windows. Target SDK 35+ always draws edge-to-edge and 36 drops
 * the opt-out, so every full-screen window has to keep its content clear of the system bars,
 * display cutouts and the IME itself.
 */
object SystemBarInsets {
    private val BARS = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
    private val IME = WindowInsetsCompat.Type.ime()

    /**
     * Wires up MainActivity's frame: the toolbar extends under the status bar (so that area takes
     * its color), the bottom navigation sits above the navigation bar, and the IME, when shown,
     * shrinks everything above it. Call [refresh] after showing or hiding [bottomNav].
     */
    fun applyToMainFrame(root: View, toolbar: View, bottomNav: View) {
        val toolbarMinHeight = toolbar.minimumHeight
        val toolbarPaddingTop = toolbar.paddingTop
        val navPaddingBottom = bottomNav.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(BARS)
            val imeVisible = insets.isVisible(IME)
            val ime = insets.getInsets(IME).bottom
            val navShown = bottomNav.visibility == View.VISIBLE
            val bottom = when {
                imeVisible -> maxOf(ime, bars.bottom)
                navShown -> 0
                else -> bars.bottom
            }
            v.setPadding(bars.left, 0, bars.right, bottom)
            toolbar.setPadding(toolbar.paddingLeft, toolbarPaddingTop + bars.top, toolbar.paddingRight, toolbar.paddingBottom)
            // An exact height, not just a larger minHeight: with wrap_content the action menu is
            // measured against the whole screen and ends up below the title.
            toolbar.updateLayoutParams { height = toolbarMinHeight + bars.top }
            bottomNav.setPadding(
                bottomNav.paddingLeft, bottomNav.paddingTop, bottomNav.paddingRight,
                navPaddingBottom + if (imeVisible) 0 else bars.bottom
            )
            // Consumed: the navigation's own inset handling would pad it a second time.
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)
    }

    /**
     * For a full-screen window with no toolbar of its own (e.g. a full-screen dialog): pads [view]
     * by the bars, cutouts and IME on top of its own padding.
     */
    fun padForSystemBars(view: View) {
        val left = view.paddingLeft
        val top = view.paddingTop
        val right = view.paddingRight
        val bottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(BARS)
            val ime = insets.getInsets(IME).bottom
            v.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + maxOf(bars.bottom, ime))
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(view)
    }

    /**
     * Makes a full-screen dialog's window draw edge-to-edge on every API level, like the platform
     * does from 35, and report the IME as insets (handled by [padForSystemBars]).
     */
    fun prepareFullScreenDialog(window: Window?) {
        window ?: return
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    /** Re-dispatches insets, e.g. after a view that [applyToMainFrame] pads was shown or hidden. */
    fun refresh(view: View) = ViewCompat.requestApplyInsets(view)
}
