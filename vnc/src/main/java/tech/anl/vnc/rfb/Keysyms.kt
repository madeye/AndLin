package tech.anl.vnc.rfb

/** X11 keysym constants and character mapping used by KeyEvent messages. */
object Keysyms {
    const val BACKSPACE = 0xff08
    const val TAB = 0xff09
    const val RETURN = 0xff0d
    const val ESCAPE = 0xff1b
    const val DELETE = 0xffff
    const val HOME = 0xff50
    const val LEFT = 0xff51
    const val UP = 0xff52
    const val RIGHT = 0xff53
    const val DOWN = 0xff54
    const val PAGE_UP = 0xff55
    const val PAGE_DOWN = 0xff56
    const val END = 0xff57
    const val INSERT = 0xff63
    const val MENU = 0xff67
    const val PRINT = 0xff61
    const val SCROLL_LOCK = 0xff14
    const val PAUSE = 0xff13
    const val NUM_LOCK = 0xff7f
    const val F1 = 0xffbe
    const val SHIFT_L = 0xffe1
    const val SHIFT_R = 0xffe2
    const val CONTROL_L = 0xffe3
    const val CONTROL_R = 0xffe4
    const val CAPS_LOCK = 0xffe5
    const val META_L = 0xffe7
    const val ALT_L = 0xffe9
    const val ALT_R = 0xffea
    const val SUPER_L = 0xffeb
    const val SUPER_R = 0xffec
    const val SPACE = 0x20

    /** F-key keysym for n in 1..35. */
    fun function(n: Int): Int = F1 + (n - 1)

    /**
     * Maps a Unicode code point to a keysym: Latin-1 printable characters map
     * to themselves, control characters to their function keys, and anything
     * else to the Unicode keysym range (0x01000000 + code point).
     */
    fun forCodePoint(cp: Int): Int = when {
        cp == '\n'.code || cp == '\r'.code -> RETURN
        cp == '\t'.code -> TAB
        cp == 0x08 -> BACKSPACE
        cp == 0x1b -> ESCAPE
        cp == 0x7f -> DELETE
        cp in 0x20..0x7e || cp in 0xa0..0xff -> cp
        cp < 0x20 -> 0 // other control characters have no keysym
        else -> 0x01000000 or cp
    }

    fun isModifier(keysym: Int): Boolean = keysym in SHIFT_L..SUPER_R || keysym == 0xfe03
}
