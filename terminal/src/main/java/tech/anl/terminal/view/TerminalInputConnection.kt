package tech.anl.terminal.view

import android.text.Selection
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection

/**
 * Forwards IME input to the terminal. Committed text is sent immediately; composing text
 * (for IMEs that insist on composing) is held until it is committed or finished. The
 * backing editable is emptied after every commit so the IME never tries to edit history.
 */
internal class TerminalInputConnection(private val view: TerminalView) : BaseInputConnection(view, true) {

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        clearEditable()
        text?.let { view.sendText(it) }
        return true
    }

    override fun finishComposingText(): Boolean {
        val pending = editable?.toString().orEmpty()
        super.finishComposingText()
        clearEditable()
        if (pending.isNotEmpty()) view.sendText(pending)
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        val editable = editable
        if (editable != null && editable.isNotEmpty()) {
            // Deleting inside the composing region: the IME will re-send what remains.
            return super.deleteSurroundingText(beforeLength, afterLength)
        }
        repeat(beforeLength) { sendDownUp(KeyEvent.KEYCODE_DEL) }
        repeat(afterLength) { sendDownUp(KeyEvent.KEYCODE_FORWARD_DEL) }
        return true
    }

    @Suppress("DEPRECATION")
    override fun sendKeyEvent(event: KeyEvent): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> view.onKeyDown(event.keyCode, event)
            KeyEvent.ACTION_UP -> view.onKeyUp(event.keyCode, event)
            KeyEvent.ACTION_MULTIPLE -> view.onKeyMultiple(event.keyCode, event.repeatCount, event)
        }
        return true
    }

    private fun sendDownUp(keyCode: Int) {
        view.onKeyDown(keyCode, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        view.onKeyUp(keyCode, KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun clearEditable() {
        val editable = editable ?: return
        removeComposingSpans(editable)
        editable.clear()
        Selection.setSelection(editable, 0)
    }
}
