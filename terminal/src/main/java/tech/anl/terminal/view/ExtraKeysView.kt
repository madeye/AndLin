package tech.anl.terminal.view

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView
import tech.anl.terminal.emulator.KeyEncoder

/**
 * Keys the soft keyboard lacks, in two rows of six: ESC, TAB, latching CTRL/ALT, arrows (laid
 * out as an inverted T) and navigation keys. Arrow and navigation keys auto-repeat while held.
 * Two rows keep each key wide and tall enough to hit reliably on a phone; one row of twelve
 * made them narrower than a fingertip.
 */
class ExtraKeysView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private sealed class Action {
        data class Send(val key: KeyEncoder.Key, val repeats: Boolean) : Action()
        object Ctrl : Action()
        object Alt : Action()
    }

    private class Button(val label: String, val action: Action)

    var terminalView: TerminalView? = null
        set(value) {
            field = value
            value?.onModifiersChanged = { refreshModifiers() }
            refreshModifiers()
        }

    private val repeatHandler = Handler(Looper.getMainLooper())
    private val modifierViews = HashMap<Action, TextView>()

    init {
        orientation = VERTICAL
        setBackgroundColor(BACKGROUND)
        val rows = listOf(
            listOf(
                Button("ESC", Action.Send(KeyEncoder.Key.ESCAPE, false)),
                Button("CTRL", Action.Ctrl),
                Button("HOME", Action.Send(KeyEncoder.Key.HOME, false)),
                Button("↑", Action.Send(KeyEncoder.Key.UP, true)),
                Button("END", Action.Send(KeyEncoder.Key.END, false)),
                Button("PGUP", Action.Send(KeyEncoder.Key.PAGE_UP, true)),
            ),
            listOf(
                Button("TAB", Action.Send(KeyEncoder.Key.TAB, false)),
                Button("ALT", Action.Alt),
                Button("←", Action.Send(KeyEncoder.Key.LEFT, true)),
                Button("↓", Action.Send(KeyEncoder.Key.DOWN, true)),
                Button("→", Action.Send(KeyEncoder.Key.RIGHT, true)),
                Button("PGDN", Action.Send(KeyEncoder.Key.PAGE_DOWN, true)),
            ),
        )
        val height = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, KEY_HEIGHT_DP, resources.displayMetrics).toInt()
        for (buttons in rows) {
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, height))
            addButtons(row, buttons)
        }
    }

    private fun addButtons(row: LinearLayout, buttons: List<Button>) {
        for (button in buttons) {
            val view = TextView(context).apply {
                text = button.label
                gravity = Gravity.CENTER
                setTextColor(TEXT_COLOR)
                typeface = Typeface.MONOSPACE
                // Glyph arrows read better a little larger than the text labels.
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (button.label.length == 1) 20f else 14f)
                maxLines = 1
                isClickable = true
                isFocusable = false
            }
            view.setOnTouchListener { v, event -> onKeyTouch(v as TextView, button.action, event) }
            if (button.action is Action.Ctrl || button.action is Action.Alt) modifierViews[button.action] = view
            row.addView(view, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private fun onKeyTouch(view: TextView, action: Action, event: MotionEvent): Boolean {
        val terminal = terminalView ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.setBackgroundColor(PRESSED)
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                when (action) {
                    is Action.Send -> {
                        terminal.sendKey(action.key)
                        if (action.repeats) scheduleRepeat(terminal, action.key, REPEAT_DELAY_MS)
                    }
                    Action.Ctrl -> terminal.setCtrlLatched(!terminal.ctrlLatched)
                    Action.Alt -> terminal.setAltLatched(!terminal.altLatched)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                repeatHandler.removeCallbacksAndMessages(null)
                refreshModifiers()
                if (action is Action.Send) view.setBackgroundColor(0)
            }
        }
        return true
    }

    private fun scheduleRepeat(terminal: TerminalView, key: KeyEncoder.Key, delay: Long) {
        repeatHandler.postDelayed({
            terminal.sendKey(key)
            scheduleRepeat(terminal, key, REPEAT_INTERVAL_MS)
        }, delay)
    }

    private fun refreshModifiers() {
        val terminal = terminalView ?: return
        modifierViews[Action.Ctrl]?.setBackgroundColor(if (terminal.ctrlLatched) LATCHED else 0)
        modifierViews[Action.Alt]?.setBackgroundColor(if (terminal.altLatched) LATCHED else 0)
    }

    override fun onDetachedFromWindow() {
        repeatHandler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val BACKGROUND = 0xFF1E1E1E.toInt()
        const val TEXT_COLOR = 0xFFEEEEEE.toInt()
        const val PRESSED = 0xFF424242.toInt()
        const val LATCHED = 0xFF1565C0.toInt()
        const val KEY_HEIGHT_DP = 44f
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_INTERVAL_MS = 60L
    }
}
