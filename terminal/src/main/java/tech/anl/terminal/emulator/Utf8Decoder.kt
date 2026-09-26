package tech.anl.terminal.emulator

/**
 * Streaming UTF-8 decoder. Multi-byte sequences may be split across calls to [decode];
 * malformed input (overlong forms, surrogates, stray continuation bytes, values past
 * U+10FFFF) is replaced by U+FFFD.
 */
class Utf8Decoder {
    fun interface Sink {
        fun codepoint(cp: Int)
    }

    private var partial = 0
    private var remaining = 0
    private var minimum = 0

    fun decode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size, sink: Sink) {
        var i = offset
        val end = offset + length
        while (i < end) {
            val b = bytes[i].toInt() and 0xFF
            if (remaining > 0) {
                if (b and 0xC0 == 0x80) {
                    partial = (partial shl 6) or (b and 0x3F)
                    if (--remaining == 0) {
                        val valid = partial >= minimum && partial <= 0x10FFFF && partial !in 0xD800..0xDFFF
                        sink.codepoint(if (valid) partial else REPLACEMENT)
                    }
                    i++
                } else {
                    // Truncated sequence: report it, then reprocess this byte as a fresh start.
                    remaining = 0
                    sink.codepoint(REPLACEMENT)
                }
                continue
            }
            when (b) {
                in 0x00..0x7F -> sink.codepoint(b)
                in 0xC2..0xDF -> start(b and 0x1F, 1, 0x80)
                in 0xE0..0xEF -> start(b and 0x0F, 2, 0x800)
                in 0xF0..0xF4 -> start(b and 0x07, 3, 0x10000)
                else -> sink.codepoint(REPLACEMENT)
            }
            i++
        }
    }

    fun reset() {
        remaining = 0
    }

    private fun start(bits: Int, count: Int, min: Int) {
        partial = bits
        remaining = count
        minimum = min
    }

    companion object {
        const val REPLACEMENT = 0xFFFD
    }
}
