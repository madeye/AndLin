package tech.anl.terminal.emulator

/**
 * A cell's style packed into a Long: foreground color (bits 0-24), background color
 * (bits 25-49) and attribute flags (bits 50-59).
 *
 * Colors are either a palette index (0-255), one of the [COLOR_DEFAULT_FG] /
 * [COLOR_DEFAULT_BG] sentinels, or a 24-bit RGB value tagged with [TRUECOLOR].
 */
object TextStyle {
    const val COLOR_DEFAULT_FG = 256
    const val COLOR_DEFAULT_BG = 257
    const val COLOR_CURSOR = 258
    const val TRUECOLOR = 0x1000000

    const val BOLD = 1
    const val DIM = 1 shl 1
    const val ITALIC = 1 shl 2
    const val UNDERLINE = 1 shl 3
    const val BLINK = 1 shl 4
    const val INVERSE = 1 shl 5
    const val INVISIBLE = 1 shl 6
    const val STRIKETHROUGH = 1 shl 7

    private const val COLOR_MASK = 0x1FFFFFFL

    val NORMAL: Long = encode(COLOR_DEFAULT_FG, COLOR_DEFAULT_BG, 0)

    fun encode(fg: Int, bg: Int, attrs: Int): Long =
        (fg.toLong() and COLOR_MASK) or ((bg.toLong() and COLOR_MASK) shl 25) or (attrs.toLong() shl 50)

    fun fg(style: Long): Int = (style and COLOR_MASK).toInt()
    fun bg(style: Long): Int = ((style ushr 25) and COLOR_MASK).toInt()
    fun attrs(style: Long): Int = (style ushr 50).toInt() and 0x3FF

    fun rgb(r: Int, g: Int, b: Int): Int = TRUECOLOR or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    fun isTrueColor(color: Int): Boolean = color and TRUECOLOR != 0
}

/** The 256-color palette plus default foreground, background and cursor, as ARGB ints. */
class TerminalColors {
    val current = IntArray(259)

    init {
        reset()
    }

    fun reset() {
        for (i in 0 until 259) reset(i)
    }

    fun reset(index: Int) {
        current[index] = DEFAULTS[index]
    }

    /** Resolves a style color (index, sentinel or truecolor) to ARGB. */
    fun resolve(color: Int): Int =
        if (TextStyle.isTrueColor(color)) (0xFF000000.toInt() or (color and 0xFFFFFF)) else current[color]

    companion object {
        private val DEFAULTS = IntArray(259).also { p ->
            val base = intArrayOf(
                0x000000, 0xCD3131, 0x0DBC79, 0xE5E510, 0x2472C8, 0xBC3FBC, 0x11A8CD, 0xE5E5E5,
                0x666666, 0xF14C4C, 0x23D18B, 0xF5F543, 0x3B8EEA, 0xD670D6, 0x29B8DB, 0xFFFFFF,
            )
            for (i in 0 until 16) p[i] = base[i]
            val steps = intArrayOf(0, 95, 135, 175, 215, 255)
            for (i in 0 until 216) {
                p[16 + i] = (steps[i / 36] shl 16) or (steps[(i / 6) % 6] shl 8) or steps[i % 6]
            }
            for (i in 0 until 24) {
                val v = 8 + i * 10
                p[232 + i] = (v shl 16) or (v shl 8) or v
            }
            p[TextStyle.COLOR_DEFAULT_FG] = 0xE5E5E5
            p[TextStyle.COLOR_DEFAULT_BG] = 0x000000
            p[TextStyle.COLOR_CURSOR] = 0xC0C0C0
            for (i in p.indices) p[i] = p[i] or 0xFF000000.toInt()
        }

        /** Parses an X11 color spec: `rgb:r/g/b` (1-4 hex digits each) or `#rgb`/`#rrggbb`. */
        fun parseColorSpec(spec: String): Int? {
            fun scale(hex: String): Int? {
                if (hex.isEmpty() || hex.length > 4) return null
                val v = hex.toIntOrNull(16) ?: return null
                val max = (1 shl (4 * hex.length)) - 1
                return v * 255 / max
            }
            if (spec.startsWith("rgb:")) {
                val parts = spec.substring(4).split('/')
                if (parts.size != 3) return null
                val r = scale(parts[0]) ?: return null
                val g = scale(parts[1]) ?: return null
                val b = scale(parts[2]) ?: return null
                return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
            }
            if (spec.startsWith("#")) {
                val hex = spec.substring(1)
                if (hex.isEmpty() || hex.length % 3 != 0 || hex.length > 12) return null
                val n = hex.length / 3
                val r = scale(hex.substring(0, n)) ?: return null
                val g = scale(hex.substring(n, 2 * n)) ?: return null
                val b = scale(hex.substring(2 * n)) ?: return null
                return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
            }
            return null
        }

        fun formatColorSpec(argb: Int): String {
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            return "rgb:%02x%02x/%02x%02x/%02x%02x".format(r, r, g, g, b, b)
        }
    }
}
