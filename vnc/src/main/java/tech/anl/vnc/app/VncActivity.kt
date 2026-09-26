package tech.anl.vnc.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import tech.anl.vnc.R
import tech.anl.vnc.rfb.Keysyms
import tech.anl.vnc.rfb.RemoteCursor
import tech.anl.vnc.rfb.RfbAuthException
import tech.anl.vnc.rfb.RfbClient
import tech.anl.vnc.rfb.RfbListener
import java.lang.ref.WeakReference
import kotlin.math.hypot

/**
 * Full-screen VNC viewer for a ServerBox desktop session.
 *
 * Started with [createIntent]; the data URI is `vnc://host:port/?password=...`.
 */
class VncActivity : AppCompatActivity(), ViewerInput {

    companion object {
        const val EXTRA_HIDE_TOOLBAR = "hide_toolbar"
        const val EXTRA_HIDE_EXTRA_KEYS = "hide_extra_keys"
        const val EXTRA_INPUT_MODE = "input_mode"
        const val EXTRA_TITLE = "title"

        private const val DEFAULT_PORT = 5951
        private const val PREFS = "tech.anl.vnc.viewer"
        private const val PREF_TOOLBAR_X = "toolbar_x"
        private const val PREF_TOOLBAR_Y = "toolbar_y"
        private const val PREF_ASKED_NOTIFICATIONS = "asked_notifications"
        private const val TOOLBAR_SHOW_MS = 2500L
        private const val HINT_SHOW_MS = 5000L
        private const val MAX_PASTE_CHARS = 4096

        @Volatile
        private var current: WeakReference<VncActivity>? = null

        fun createIntent(
            context: Context,
            host: String,
            port: Int,
            password: String?,
            inputMode: String,
            hideToolbar: Boolean,
            hideExtraKeys: Boolean,
            title: String
        ): Intent {
            val hostPart = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
            val uri = Uri.Builder()
                .scheme("vnc")
                .encodedAuthority("$hostPart:$port")
                .path("/")
                .apply { if (password != null) appendQueryParameter("password", password) }
                .build()
            return Intent(context, VncActivity::class.java)
                .setData(uri)
                .putExtra(EXTRA_INPUT_MODE, inputMode)
                .putExtra(EXTRA_HIDE_TOOLBAR, hideToolbar)
                .putExtra(EXTRA_HIDE_EXTRA_KEYS, hideExtraKeys)
                .putExtra(EXTRA_TITLE, title)
                .apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        }

        /** Finishes the viewer if it is open. The desktop session keeps running. */
        fun closeViewer(context: Context) {
            val activity = current?.get()
            if (activity != null) {
                activity.runOnUiThread { activity.finish() }
            } else {
                ViewerNotification.cancel(context.applicationContext)
            }
        }
    }

    private data class Target(val host: String, val port: Int, val password: String?)

    private lateinit var root: FrameLayout
    private lateinit var vncView: VncView
    private lateinit var toolbar: LinearLayout
    private lateinit var menuButton: ImageButton
    private lateinit var extraKeys: HorizontalScrollView
    private lateinit var hintView: TextView
    private lateinit var progress: LinearLayout
    private lateinit var progressText: TextView

    private var target: Target? = null
    private var client: RfbClient? = null
    private var everConnected = false
    private var dialog: AlertDialog? = null
    private var inputMode = InputMode.DIRECT_HOLD_PAN
    private var hideToolbar = false
    private var hideExtraKeys = false
    private var title = ""

    private var imeVisible = false
    private var imeHeight = 0
    private var menuOpen = false
    private var toolbarDragging = false
    private var lastBackPress = 0L
    private var lastBell = 0L
    private var lastClipboard: String? = null
    private var lastSizeRequest: Pair<Int, Int>? = null

    private val latched = LinkedHashSet<Int>()
    private val latchButtons = HashMap<Int, View>()
    private val keysDown = HashMap<Int, Int>()
    private var keyLatchesPending: List<Int> = emptyList()

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val clipboard by lazy { getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { sendClipboardIfChanged() }
    private val hideToolbarRunnable = Runnable { hideToolbarNow() }
    private val hideHintRunnable = Runnable {
        hintView.animate().alpha(0f).withEndAction { hintView.visibility = View.GONE }.start()
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && !isFinishing) ViewerNotification.show(this, title)
        }

    // ---- Lifecycle ------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        readIntent(intent)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        buildUi()
        enterImmersive()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val now = SystemClock.uptimeMillis()
                if (now - lastBackPress < 2000) {
                    finish()
                } else {
                    lastBackPress = now
                    Toast.makeText(this@VncActivity, R.string.vnc_press_back_again, Toast.LENGTH_SHORT).show()
                }
            }
        })
        postNotification()
        connect()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.data == null) return // e.g. the notification: just come back to the front
        val previous = target
        setIntent(intent)
        readIntent(intent)
        vncView.touch.mode = inputMode
        ViewerNotification.show(this, title)
        if (target != previous || client?.isClosed != false) {
            connect()
        }
    }

    override fun onResume() {
        super.onResume()
        enterImmersive()
        clipboard.addPrimaryClipChangedListener(clipListener)
    }

    override fun onPause() {
        clipboard.removePrimaryClipChangedListener(clipListener)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enterImmersive()
            sendClipboardIfChanged()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enterImmersive()
    }

    override fun onDestroy() {
        client?.close()
        client = null
        dialog?.dismiss()
        if (current?.get() === this) {
            current = null
            ViewerNotification.cancel(this)
        }
        super.onDestroy()
    }

    private fun readIntent(intent: Intent) {
        val data = intent.data
        if (data != null) {
            val host = (data.host ?: "127.0.0.1").removePrefix("[").removeSuffix("]")
            val port = if (data.port > 0) data.port else DEFAULT_PORT
            target = Target(host, port, data.getQueryParameter("password"))
        }
        inputMode = InputMode.fromPref(intent.getStringExtra(EXTRA_INPUT_MODE))
        hideToolbar = intent.getBooleanExtra(EXTRA_HIDE_TOOLBAR, false)
        hideExtraKeys = intent.getBooleanExtra(EXTRA_HIDE_EXTRA_KEYS, false)
        title = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }
            ?: getString(R.string.vnc_default_title)
    }

    private fun enterImmersive() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun postNotification() {
        ViewerNotification.show(this, title)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean(PREF_ASKED_NOTIFICATIONS, false)
        ) {
            prefs.edit().putBoolean(PREF_ASKED_NOTIFICATIONS, true).apply()
            try {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } catch (_: Exception) {
            }
        }
    }

    // ---- UI -------------------------------------------------------------

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun buildUi() {
        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        vncView = VncView(this)
        vncView.input = this
        vncView.touch.mode = inputMode
        root.addView(vncView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        progress = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(ProgressBar(this@VncActivity))
            progressText = TextView(this@VncActivity).apply {
                setTextColor(Color.LTGRAY)
                setPadding(0, dp(12), 0, 0)
            }
            addView(progressText)
        }
        root.addView(progress, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        hintView = TextView(this).apply {
            setBackgroundResource(R.drawable.vnc_hint_bg)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            gravity = Gravity.CENTER
            visibility = View.GONE
            maxWidth = dp(520)
        }
        root.addView(hintView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(24)
            leftMargin = dp(16)
            rightMargin = dp(16)
        })

        extraKeys = buildExtraKeys()
        extraKeys.visibility = View.GONE
        root.addView(extraKeys, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(EXTRA_KEYS_DP), Gravity.BOTTOM))

        toolbar = buildToolbar()
        toolbar.visibility = View.INVISIBLE
        toolbar.alpha = 0f
        root.addView(toolbar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) placeToolbar()
        }
        toolbar.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) placeToolbar()
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            imeHeight = if (imeVisible) insets.getInsets(WindowInsetsCompat.Type.ime()).bottom else 0
            updateExtraKeys()
            insets
        }

        setContentView(root)
        vncView.requestFocus()
    }

    private fun updateExtraKeys() {
        val show = imeVisible && !hideExtraKeys
        extraKeys.visibility = if (show) View.VISIBLE else View.GONE
        (extraKeys.layoutParams as FrameLayout.LayoutParams).let {
            if (it.bottomMargin != imeHeight) {
                it.bottomMargin = imeHeight
                extraKeys.layoutParams = it
            }
        }
        vncView.obscuredBottom = imeHeight + if (show) dp(EXTRA_KEYS_DP) else 0
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildToolbar(): LinearLayout {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setBackgroundResource(R.drawable.vnc_toolbar_bg)
        bar.setPadding(dp(4), dp(2), dp(4), dp(2))
        fun button(icon: Int, label: Int, onClick: (View) -> Unit) = ImageButton(this).apply {
            setImageResource(icon)
            contentDescription = getString(label)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener(onClick)
            setOnTouchListener(ToolbarDragListener())
        }
        menuButton = button(R.drawable.vnc_ic_more, R.string.vnc_menu) { showMenu() }
        bar.addView(menuButton, LinearLayout.LayoutParams(dp(40), dp(40)))
        bar.addView(button(R.drawable.vnc_ic_keyboard, R.string.vnc_keyboard) { toggleKeyboard() }, LinearLayout.LayoutParams(dp(40), dp(40)))
        return bar
    }

    /** Lets the toolbar buttons drag the whole cluster; a touch that doesn't move is a click. */
    private inner class ToolbarDragListener : View.OnTouchListener {
        private var startRawX = 0f
        private var startRawY = 0f
        private var startX = 0f
        private var startY = 0f
        private val slop = ViewConfiguration.get(this@VncActivity).scaledTouchSlop

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = e.rawX
                    startRawY = e.rawY
                    startX = toolbar.x
                    startY = toolbar.y
                    toolbarDragging = false
                    v.isPressed = true
                    showToolbar(autoHide = false)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - startRawX
                    val dy = e.rawY - startRawY
                    if (!toolbarDragging && hypot(dx, dy) > slop) {
                        toolbarDragging = true
                        v.isPressed = false
                    }
                    if (toolbarDragging) {
                        toolbar.x = (startX + dx).coerceIn(0f, (root.width - toolbar.width).coerceAtLeast(0).toFloat())
                        toolbar.y = (startY + dy).coerceIn(0f, (root.height - toolbar.height).coerceAtLeast(0).toFloat())
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    if (toolbarDragging) {
                        toolbarDragging = false
                        saveToolbarPosition()
                        showToolbar()
                    } else {
                        v.performClick()
                        if (!menuOpen) showToolbar()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    toolbarDragging = false
                    showToolbar()
                }
            }
            return true
        }
    }

    private fun saveToolbarPosition() {
        val w = (root.width - toolbar.width).toFloat()
        val h = (root.height - toolbar.height).toFloat()
        if (w <= 0 || h <= 0) return
        prefs.edit()
            .putFloat(PREF_TOOLBAR_X, (toolbar.x / w).coerceIn(0f, 1f))
            .putFloat(PREF_TOOLBAR_Y, (toolbar.y / h).coerceIn(0f, 1f))
            .apply()
    }

    private fun placeToolbar() {
        val w = root.width - toolbar.width
        val h = root.height - toolbar.height
        if (w <= 0 || h <= 0) return
        val margin = dp(8)
        val defaultX = 1f - margin.toFloat() / w
        val defaultY = margin.toFloat() / h
        toolbar.x = prefs.getFloat(PREF_TOOLBAR_X, defaultX) * w
        toolbar.y = prefs.getFloat(PREF_TOOLBAR_Y, defaultY) * h
    }

    private fun showToolbar(autoHide: Boolean = true) {
        toolbar.removeCallbacks(hideToolbarRunnable)
        if (toolbar.visibility != View.VISIBLE || toolbar.alpha < 1f) {
            toolbar.visibility = View.VISIBLE
            toolbar.animate().cancel()
            toolbar.animate().alpha(1f).setDuration(120).start()
        }
        if (autoHide && !menuOpen && !toolbarDragging) toolbar.postDelayed(hideToolbarRunnable, TOOLBAR_SHOW_MS)
    }

    private fun hideToolbarNow() {
        if (menuOpen || toolbarDragging) return
        toolbar.animate().cancel()
        toolbar.animate().alpha(0f).setDuration(250).withEndAction {
            if (toolbar.alpha == 0f) toolbar.visibility = View.INVISIBLE
        }.start()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && !hideToolbar && !menuOpen) showToolbar()
        return super.dispatchTouchEvent(ev)
    }

    override fun onThreeFingerTap() = showToolbar()

    private fun showHint(text: String) {
        hintView.removeCallbacks(hideHintRunnable)
        hintView.animate().cancel()
        hintView.text = text
        hintView.alpha = 1f
        hintView.visibility = View.VISIBLE
        hintView.postDelayed(hideHintRunnable, HINT_SHOW_MS)
    }

    private fun modeHint(mode: InputMode): String = getString(
        when (mode) {
            InputMode.DIRECT_HOLD_PAN -> R.string.vnc_hint_direct_hold_pan
            InputMode.DIRECT_SWIPE_PAN -> R.string.vnc_hint_direct_swipe_pan
            InputMode.TOUCHPAD -> R.string.vnc_hint_touchpad
            InputMode.SINGLE_HANDED -> R.string.vnc_hint_single_handed
        }
    )

    private fun setInputMode(mode: InputMode) {
        inputMode = mode
        vncView.touch.mode = mode
        if (!mode.isDirect && vncView.pointerX == 0f && vncView.pointerY == 0f) vncView.centerPointer()
        showHint(modeHint(mode))
    }

    // ---- Menu -----------------------------------------------------------

    private val modeItems = mapOf(
        R.id.vnc_mode_direct_hold_pan to InputMode.DIRECT_HOLD_PAN,
        R.id.vnc_mode_direct_swipe_pan to InputMode.DIRECT_SWIPE_PAN,
        R.id.vnc_mode_touchpad to InputMode.TOUCHPAD,
        R.id.vnc_mode_single_handed to InputMode.SINGLE_HANDED
    )

    private fun showMenu() {
        val popup = PopupMenu(this, menuButton)
        popup.inflate(R.menu.vnc_viewer)
        for ((id, mode) in modeItems) popup.menu.findItem(id)?.isChecked = mode == inputMode
        popup.setOnMenuItemClickListener { onMenuItem(it) }
        popup.setOnDismissListener {
            menuOpen = false
            showToolbar()
            enterImmersive()
        }
        menuOpen = true
        showToolbar(autoHide = false)
        popup.show()
    }

    private fun onMenuItem(item: MenuItem): Boolean {
        modeItems[item.itemId]?.let {
            item.isChecked = true
            setInputMode(it)
            return true
        }
        when (item.itemId) {
            R.id.vnc_key_esc -> tapCombo(Keysyms.ESCAPE)
            R.id.vnc_key_tab -> tapCombo(Keysyms.TAB)
            R.id.vnc_key_ctrl_c -> tapCombo('c'.code, Keysyms.CONTROL_L)
            R.id.vnc_key_ctrl_v -> tapCombo('v'.code, Keysyms.CONTROL_L)
            R.id.vnc_key_ctrl_z -> tapCombo('z'.code, Keysyms.CONTROL_L)
            R.id.vnc_key_alt_tab -> tapCombo(Keysyms.TAB, Keysyms.ALT_L)
            R.id.vnc_key_alt_f4 -> tapCombo(Keysyms.function(4), Keysyms.ALT_L)
            R.id.vnc_key_super -> tapCombo(Keysyms.SUPER_L)
            R.id.vnc_menu_paste -> pasteClipboard()
            R.id.vnc_menu_reset_zoom -> vncView.resetZoom()
            R.id.vnc_menu_close -> finish()
            else -> return false
        }
        return true
    }

    private fun tapCombo(keysym: Int, vararg modifiers: Int) {
        val c = client ?: return
        for (m in modifiers) c.sendKey(m, true)
        c.sendKey(keysym, true)
        c.sendKey(keysym, false)
        for (m in modifiers.reversed()) c.sendKey(m, false)
    }

    private fun toggleKeyboard() {
        val controller = WindowInsetsControllerCompat(window, vncView)
        if (imeVisible) {
            controller.hide(WindowInsetsCompat.Type.ime())
        } else {
            vncView.requestFocus()
            controller.show(WindowInsetsCompat.Type.ime())
        }
    }

    // ---- Extra keys -----------------------------------------------------

    private fun buildExtraKeys(): HorizontalScrollView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF181818.toInt())
            setPadding(dp(2), dp(3), dp(2), dp(3))
        }
        fun key(label: String, onClick: (View) -> Unit): TextView = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minWidth = dp(48)
            setPadding(dp(10), 0, dp(10), 0)
            setBackgroundResource(R.drawable.vnc_key_bg)
            isFocusable = false
            setOnClickListener(onClick)
            row.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            })
        }
        fun latch(label: String, keysym: Int) {
            latchButtons[keysym] = key(label) { toggleLatch(keysym) }
        }
        key("Esc") { tapKeysym(Keysyms.ESCAPE) }
        key("Tab") { tapKeysym(Keysyms.TAB) }
        latch("Ctrl", Keysyms.CONTROL_L)
        latch("Alt", Keysyms.ALT_L)
        latch("Super", Keysyms.SUPER_L)
        key("←") { tapKeysym(Keysyms.LEFT) }
        key("↓") { tapKeysym(Keysyms.DOWN) }
        key("↑") { tapKeysym(Keysyms.UP) }
        key("→") { tapKeysym(Keysyms.RIGHT) }
        key("Home") { tapKeysym(Keysyms.HOME) }
        key("End") { tapKeysym(Keysyms.END) }
        key("PgUp") { tapKeysym(Keysyms.PAGE_UP) }
        key("PgDn") { tapKeysym(Keysyms.PAGE_DOWN) }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun toggleLatch(keysym: Int) {
        if (!latched.remove(keysym)) latched.add(keysym)
        latchButtons[keysym]?.isSelected = keysym in latched
    }

    private fun clearLatches() {
        latched.clear()
        latchButtons.values.forEach { it.isSelected = false }
    }

    /** Takes the latched modifiers for the next key and clears the latches. */
    private fun takeLatches(): List<Int> {
        val mods = latched.toList()
        if (mods.isNotEmpty()) clearLatches()
        return mods
    }

    // ---- ViewerInput ----------------------------------------------------

    override fun sendPointer(x: Int, y: Int, buttons: Int) {
        client?.sendPointer(x, y, buttons)
    }

    override fun tapKeysym(keysym: Int) {
        val c = client ?: return
        val mods = takeLatches()
        for (m in mods) c.sendKey(m, true)
        c.sendKey(keysym, true)
        c.sendKey(keysym, false)
        for (m in mods.asReversed()) c.sendKey(m, false)
    }

    override fun typeText(text: String) {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val keysym = Keysyms.forCodePoint(cp)
            if (keysym != 0) tapKeysym(keysym)
        }
    }

    override fun onKey(event: KeyEvent): Boolean {
        val c = client ?: return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_HOME -> return false
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                val keysym = AndroidKeys.keysymFor(event)
                if (keysym == 0) return false
                if (event.repeatCount == 0 && !Keysyms.isModifier(keysym)) {
                    keyLatchesPending = takeLatches()
                    for (m in keyLatchesPending) c.sendKey(m, true)
                }
                keysDown[event.keyCode] = keysym
                c.sendKey(keysym, true)
                return true
            }
            KeyEvent.ACTION_UP -> {
                val keysym = keysDown.remove(event.keyCode) ?: AndroidKeys.keysymFor(event)
                if (keysym == 0) return false
                c.sendKey(keysym, false)
                if (!Keysyms.isModifier(keysym) && keyLatchesPending.isNotEmpty()) {
                    for (m in keyLatchesPending.asReversed()) c.sendKey(m, false)
                    keyLatchesPending = emptyList()
                }
                return true
            }
        }
        return false
    }

    override fun onViewSizeChanged(width: Int, height: Int) {
        maybeRequestDesktopSize()
    }

    /** On rotation, ask a resizable server to swap the desktop's orientation to match. */
    private fun maybeRequestDesktopSize() {
        val c = client ?: return
        val fw = c.framebuffer.width
        val fh = c.framebuffer.height
        val vw = vncView.width
        val vh = vncView.height
        if (!c.supportsDesktopResize || fw <= 0 || fh <= 0 || vw <= 0 || vh <= 0 || fw == fh) return
        if ((vw > vh) != (fw > fh)) {
            val request = fh to fw
            if (request != lastSizeRequest) {
                lastSizeRequest = request
                c.requestDesktopSize(fh, fw)
            }
        }
    }

    // ---- Clipboard ------------------------------------------------------

    private fun clipboardText(): String? = try {
        clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
    } catch (_: Exception) {
        null
    }

    private fun sendClipboardIfChanged() {
        val c = client ?: return
        val text = clipboardText() ?: return
        if (text == lastClipboard) return
        lastClipboard = text
        c.sendClientCutText(text)
    }

    private fun pasteClipboard() {
        val text = clipboardText()
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, R.string.vnc_clipboard_empty, Toast.LENGTH_SHORT).show()
            return
        }
        lastClipboard = text
        client?.sendClientCutText(text)
        typeText(text.replace("\r\n", "\n").take(MAX_PASTE_CHARS))
    }

    // ---- Connection -----------------------------------------------------

    private fun connect() {
        val t = target ?: run {
            finish()
            return
        }
        dialog?.dismiss()
        dialog = null
        client?.close()
        lastSizeRequest = null
        progressText.text = getString(R.string.vnc_connecting, "${t.host}:${t.port}")
        progress.visibility = View.VISIBLE
        val listener = Listener()
        val c = RfbClient(t.host, t.port, t.password, listener, BitmapJpegDecoder)
        listener.owner = c
        client = c
        vncView.attach(c.framebuffer)
        vncView.setRemoteCursor(null)
        c.start()
    }

    /** Callbacks for one [RfbClient]; ignored once that client has been replaced. */
    private inner class Listener : RfbListener {
        lateinit var owner: RfbClient

        private fun ui(block: (RfbClient) -> Unit) {
            runOnUiThread {
                val c = owner
                if (c === client && !isFinishing && !isDestroyed) block(c)
            }
        }

        override fun onConnected(desktopName: String, width: Int, height: Int) = ui { c ->
            everConnected = true
            progress.visibility = View.GONE
            vncView.onFramebufferResized()
            if (!inputMode.isDirect) vncView.centerPointer()
            showHint(modeHint(inputMode))
            lastClipboard = null
            sendClipboardIfChanged()
            if (c.supportsDesktopResize) maybeRequestDesktopSize()
        }

        override fun onResize(width: Int, height: Int) = ui {
            vncView.onFramebufferResized()
            vncView.post { maybeRequestDesktopSize() }
        }

        override fun onFramebufferUpdate(x: Int, y: Int, width: Int, height: Int) {
            vncView.markDirty(x, y, width, height)
        }

        override fun onCursor(cursor: RemoteCursor?) = ui { vncView.setRemoteCursor(cursor) }

        override fun onBell() = ui {
            val now = SystemClock.uptimeMillis()
            if (now - lastBell > 500) {
                lastBell = now
                vncView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }

        override fun onServerCutText(text: String) = ui {
            if (text != lastClipboard) {
                lastClipboard = text
                try {
                    clipboard.setPrimaryClip(ClipData.newPlainText("VNC", text))
                } catch (_: Exception) {
                }
            }
        }

        override fun onDisconnected(error: Throwable?) = ui { showDisconnected(error) }
    }

    private fun showDisconnected(error: Throwable?) {
        progress.visibility = View.GONE
        dialog?.dismiss()
        val titleRes = when {
            error is RfbAuthException -> R.string.vnc_auth_failed
            !everConnected -> R.string.vnc_connection_failed
            else -> R.string.vnc_connection_lost
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(titleRes)
            .apply { error?.message?.let { setMessage(it) } }
            .setCancelable(false)
            .setPositiveButton(R.string.vnc_reconnect) { _, _ -> connect() }
            .setNegativeButton(R.string.vnc_close) { _, _ -> finish() }
            .show()
    }
}

private const val EXTRA_KEYS_DP = 44
