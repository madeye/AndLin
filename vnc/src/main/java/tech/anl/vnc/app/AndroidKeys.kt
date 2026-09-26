package tech.anl.vnc.app

import android.view.KeyCharacterMap
import android.view.KeyEvent
import tech.anl.vnc.rfb.Keysyms

/** Maps Android key codes that have no printable character to X11 keysyms. */
internal object AndroidKeys {
    fun keysymForKeyCode(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Keysyms.RETURN
        KeyEvent.KEYCODE_DEL -> Keysyms.BACKSPACE
        KeyEvent.KEYCODE_FORWARD_DEL -> Keysyms.DELETE
        KeyEvent.KEYCODE_TAB -> Keysyms.TAB
        KeyEvent.KEYCODE_ESCAPE -> Keysyms.ESCAPE
        KeyEvent.KEYCODE_DPAD_LEFT -> Keysyms.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Keysyms.RIGHT
        KeyEvent.KEYCODE_DPAD_UP -> Keysyms.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> Keysyms.DOWN
        KeyEvent.KEYCODE_MOVE_HOME -> Keysyms.HOME
        KeyEvent.KEYCODE_MOVE_END -> Keysyms.END
        KeyEvent.KEYCODE_PAGE_UP -> Keysyms.PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN -> Keysyms.PAGE_DOWN
        KeyEvent.KEYCODE_INSERT -> Keysyms.INSERT
        KeyEvent.KEYCODE_MENU -> Keysyms.MENU
        KeyEvent.KEYCODE_SYSRQ -> Keysyms.PRINT
        KeyEvent.KEYCODE_BREAK -> Keysyms.PAUSE
        KeyEvent.KEYCODE_SCROLL_LOCK -> Keysyms.SCROLL_LOCK
        KeyEvent.KEYCODE_NUM_LOCK -> Keysyms.NUM_LOCK
        KeyEvent.KEYCODE_CAPS_LOCK -> Keysyms.CAPS_LOCK
        KeyEvent.KEYCODE_SHIFT_LEFT -> Keysyms.SHIFT_L
        KeyEvent.KEYCODE_SHIFT_RIGHT -> Keysyms.SHIFT_R
        KeyEvent.KEYCODE_CTRL_LEFT -> Keysyms.CONTROL_L
        KeyEvent.KEYCODE_CTRL_RIGHT -> Keysyms.CONTROL_R
        KeyEvent.KEYCODE_ALT_LEFT -> Keysyms.ALT_L
        KeyEvent.KEYCODE_ALT_RIGHT -> Keysyms.ALT_R
        KeyEvent.KEYCODE_META_LEFT -> Keysyms.SUPER_L
        KeyEvent.KEYCODE_META_RIGHT -> Keysyms.SUPER_R
        KeyEvent.KEYCODE_SPACE -> Keysyms.SPACE
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> Keysyms.function(keyCode - KeyEvent.KEYCODE_F1 + 1)
        else -> 0
    }

    /** Keysym for [event]: special keys first, then the character it produces. */
    fun keysymFor(event: KeyEvent): Int {
        val special = keysymForKeyCode(event.keyCode)
        if (special != 0) return special
        // Ctrl/Alt/Meta are sent as separate key events; ask for the plain character.
        val meta = event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK or KeyEvent.META_META_MASK).inv()
        var ch = event.getUnicodeChar(meta)
        if (ch == 0) ch = event.getUnicodeChar(0)
        if (ch == 0 || ch and KeyCharacterMap.COMBINING_ACCENT != 0) return 0
        return Keysyms.forCodePoint(ch)
    }
}
