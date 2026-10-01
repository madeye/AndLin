package tech.anl.terminal

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import tech.anl.terminal.TerminalLauncher.getSpec
import tech.anl.terminal.view.ExtraKeysView
import tech.anl.terminal.view.TerminalView

/** Shows one terminal session at a time; relaunching with another session key switches to it. */
class TerminalActivity : AppCompatActivity(), TerminalSession.Listener, TerminalSessions.Observer, TerminalView.Listener {

    private lateinit var terminalView: TerminalView
    private lateinit var extraKeys: ExtraKeysView
    private lateinit var toolbar: Toolbar
    private var session: TerminalSession? = null
    private var keyboardVisible = false

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.terminal_activity)

        toolbar = findViewById(R.id.terminal_toolbar)
        setSupportActionBar(toolbar)
        terminalView = findViewById(R.id.terminal_view)
        extraKeys = findViewById(R.id.terminal_extra_keys)
        extraKeys.terminalView = terminalView
        terminalView.listener = this

        val savedSp = prefs.getFloat(PREF_FONT_SP, TerminalView.DEFAULT_FONT_SP)
        terminalView.setFontSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, savedSp, resources.displayMetrics))

        // Drawn edge-to-edge (enforced from target SDK 35): the toolbar extends under the status
        // bar so that area takes its color, and the rest stays clear of the bars and the IME.
        val root = findViewById<View>(R.id.terminal_root)
        // An exact height (see below) must still fit the title plus the subtitle (the session's
        // live title), which the 48dp minHeight doesn't: use the action bar height for that.
        val actionBarSize = TypedValue().let { tv ->
            if (theme.resolveAttribute(androidx.appcompat.R.attr.actionBarSize, tv, true)) {
                TypedValue.complexToDimensionPixelSize(tv.data, resources.displayMetrics)
            } else 0
        }
        val toolbarHeight = maxOf(toolbar.minimumHeight, actionBarSize)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, 0, bars.right, maxOf(bars.bottom, ime.bottom))
            toolbar.setPadding(toolbar.paddingLeft, bars.top, toolbar.paddingRight, toolbar.paddingBottom)
            toolbar.updateLayoutParams { height = toolbarHeight + bars.top }
            setKeyboardVisible(insets.isVisible(WindowInsetsCompat.Type.ime()))
            WindowInsetsCompat.CONSUMED
        }

        // Predictive back never delivers KEYCODE_BACK to the view: clear a selection from here.
        val clearSelectionOnBack = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = terminalView.clearSelection()
        }
        onBackPressedDispatcher.addCallback(this, clearSelectionOnBack)
        terminalView.onSelectionChanged = { clearSelectionOnBack.isEnabled = it }

        TerminalSessions.addObserver(this)
        // A recreated activity or a relaunch from recents must not restart an exited session.
        val fresh = savedInstanceState == null &&
            intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0
        if (!attachFromIntent(intent, allowStart = fresh)) {
            finish()
            return
        }
        showHintOnce()
        if (savedInstanceState == null) {
            terminalView.post { showKeyboard() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        attachFromIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // Launching may have been blocked from the background; we are visible now.
        if (!TerminalSessions.isEmpty) ensureService()
        terminalView.onScreenUpdated()
    }

    override fun onDestroy() {
        TerminalSessions.removeObserver(this)
        session?.removeListener(this)
        super.onDestroy()
    }

    /** Attaches to the session named by [intent], starting it if needed. False if there is nothing to show. */
    private fun attachFromIntent(intent: Intent, allowStart: Boolean = true): Boolean {
        val spec = if (allowStart) intent.getSpec() else null
        val key = intent.getStringExtra(TerminalLauncher.EXTRA_SESSION_KEY)
        val target = when {
            spec != null -> TerminalSessions.getOrCreate(spec)
            key != null -> TerminalSessions.get(key) ?: TerminalSessions.all.lastOrNull()
            else -> TerminalSessions.all.lastOrNull()
        } ?: return false
        attach(target)
        return true
    }

    private fun attach(target: TerminalSession) {
        if (session === target) return
        session?.removeListener(this)
        session = target
        target.addListener(this)
        terminalView.attachSession(target)
        updateTitle()
        terminalView.requestFocus()
    }

    private fun ensureService() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, TerminalService::class.java))
        } catch (_: RuntimeException) {
            // Still restricted; the session keeps running as long as the process does.
        }
    }

    private fun updateTitle() {
        val s = session ?: return
        val title = s.spec.title
        val live = s.title
        toolbar.title = title
        toolbar.subtitle = live.takeIf { it != title }
        @Suppress("DEPRECATION")
        setTaskDescription(ActivityManager.TaskDescription(title))
    }

    private fun setKeyboardVisible(visible: Boolean) {
        keyboardVisible = visible
        extraKeys.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun showKeyboard() {
        terminalView.requestFocus()
        WindowInsetsControllerCompat(window, terminalView).show(WindowInsetsCompat.Type.ime())
    }

    private fun hideKeyboard() {
        WindowInsetsControllerCompat(window, terminalView).hide(WindowInsetsCompat.Type.ime())
    }

    private fun showHintOnce() {
        if (prefs.getBoolean(PREF_HINT_SHOWN, false)) return
        prefs.edit().putBoolean(PREF_HINT_SHOWN, true).apply()
        Toast.makeText(this, R.string.terminal_hint, Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------ menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.terminal, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.terminal_action_copy_all -> onCopyAll()
            R.id.terminal_action_paste -> terminalView.pasteFromClipboard()
            R.id.terminal_action_keyboard -> onToggleKeyboard()
            R.id.terminal_action_font_larger -> terminalView.changeFontSize(FONT_STEP)
            R.id.terminal_action_font_smaller -> terminalView.changeFontSize(1f / FONT_STEP)
            R.id.terminal_action_close -> onCloseSession()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ---------------------------------------------------- TerminalView.Listener

    override fun onCopyAll() {
        val s = session ?: return
        val text = synchronized(s.emulator) { s.emulator.getAllText() }
        terminalView.copyToClipboard(text)
        Toast.makeText(this, R.string.terminal_copied, Toast.LENGTH_SHORT).show()
    }

    override fun onRequestKeyboard() = showKeyboard()

    override fun onToggleKeyboard() {
        if (keyboardVisible) hideKeyboard() else showKeyboard()
    }

    override fun onFontSizeChanged(sizePx: Float) {
        val sp = sizePx / TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics)
        prefs.edit().putFloat(PREF_FONT_SP, sp).apply()
    }

    override fun onCloseSession() {
        session?.let { TerminalSessions.remove(it) }
    }

    // --------------------------------------------------- session callbacks

    override fun onScreenUpdated(session: TerminalSession) = terminalView.onScreenUpdated()

    override fun onTitleChanged(session: TerminalSession) = updateTitle()

    override fun onSessionFinished(session: TerminalSession) = terminalView.onScreenUpdated()

    override fun onClipboardSet(session: TerminalSession, text: String) {
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(getString(R.string.terminal_clip_label), text))
    }

    override fun onSessionsChanged() {
        val current = session
        if (current != null && TerminalSessions.get(current.spec.sessionKey) === current) return
        val next = TerminalSessions.all.lastOrNull()
        if (next == null) {
            current?.removeListener(this)
            session = null
            finish()
        } else {
            attach(next)
        }
    }

    companion object {
        private const val PREFS = "tech.anl.terminal"
        private const val PREF_HINT_SHOWN = "hint_shown"
        private const val PREF_FONT_SP = "font_size_sp"
        private const val FONT_STEP = 1.15f
    }
}
