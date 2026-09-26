package tech.anl.terminal.emulator

/** Encodes keyboard input as the byte sequences an xterm-compatible application expects. */
object KeyEncoder {
    const val MOD_SHIFT = 1
    const val MOD_ALT = 2
    const val MOD_CTRL = 4

    enum class Key {
        UP, DOWN, RIGHT, LEFT, HOME, END, PAGE_UP, PAGE_DOWN, INSERT, DELETE,
        ENTER, TAB, BACKSPACE, ESCAPE,
        F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
    }

    /**
     * Encodes a special key. [mods] is a combination of MOD_* flags; [appCursor] reflects
     * DECCKM and [newLineMode] reflects LNM.
     */
    fun encode(key: Key, mods: Int, appCursor: Boolean, newLineMode: Boolean = false): String {
        val m = if (mods == 0) "" else ";${mods + 1}"
        return when (key) {
            Key.UP -> cursorKey('A', mods, appCursor)
            Key.DOWN -> cursorKey('B', mods, appCursor)
            Key.RIGHT -> cursorKey('C', mods, appCursor)
            Key.LEFT -> cursorKey('D', mods, appCursor)
            Key.HOME -> cursorKey('H', mods, appCursor)
            Key.END -> cursorKey('F', mods, appCursor)
            Key.INSERT -> "\u001b[2$m~"
            Key.DELETE -> "\u001b[3$m~"
            Key.PAGE_UP -> "\u001b[5$m~"
            Key.PAGE_DOWN -> "\u001b[6$m~"
            Key.F1 -> functionKey('P', mods)
            Key.F2 -> functionKey('Q', mods)
            Key.F3 -> functionKey('R', mods)
            Key.F4 -> functionKey('S', mods)
            Key.F5 -> "\u001b[15$m~"
            Key.F6 -> "\u001b[17$m~"
            Key.F7 -> "\u001b[18$m~"
            Key.F8 -> "\u001b[19$m~"
            Key.F9 -> "\u001b[20$m~"
            Key.F10 -> "\u001b[21$m~"
            Key.F11 -> "\u001b[23$m~"
            Key.F12 -> "\u001b[24$m~"
            Key.ENTER -> altPrefix(mods, if (newLineMode) "\r\n" else "\r")
            Key.TAB -> if (mods and MOD_SHIFT != 0) "\u001b[Z" else altPrefix(mods, "\t")
            Key.BACKSPACE -> altPrefix(mods, if (mods and MOD_CTRL != 0) "\u0008" else "\u007f")
            Key.ESCAPE -> altPrefix(mods, "\u001b")
        }
    }

    private fun cursorKey(final: Char, mods: Int, appCursor: Boolean): String = when {
        mods != 0 -> "\u001b[1;${mods + 1}$final"
        appCursor -> "\u001bO$final"
        else -> "\u001b[$final"
    }

    private fun functionKey(final: Char, mods: Int): String =
        if (mods == 0) "\u001bO$final" else "\u001b[1;${mods + 1}$final"

    private fun altPrefix(mods: Int, s: String) = if (mods and MOD_ALT != 0) "\u001b$s" else s

    /** Maps a character to its Ctrl-modified control code, or null if it has none. */
    fun controlCode(cp: Int): Int? = when (cp) {
        in 'a'.code..'z'.code -> cp - 'a'.code + 1
        in 'A'.code..'Z'.code -> cp - 'A'.code + 1
        ' '.code, '@'.code, '2'.code -> 0
        '['.code, '3'.code -> 27
        '\\'.code, '4'.code -> 28
        ']'.code, '5'.code -> 29
        '^'.code, '6'.code -> 30
        '_'.code, '/'.code, '7'.code -> 31
        '?'.code, '8'.code -> 127
        else -> null
    }

    /** Encodes a typed character with optional Ctrl/Alt. */
    fun encodeChar(cp: Int, ctrl: Boolean, alt: Boolean): String {
        var code = cp
        if (ctrl) controlCode(cp)?.let { code = it }
        val s = StringBuilder()
        if (alt) s.append('\u001b')
        s.appendCodePoint(code)
        return s.toString()
    }
}
