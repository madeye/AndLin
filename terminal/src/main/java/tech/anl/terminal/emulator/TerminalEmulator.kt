package tech.anl.terminal.emulator

import tech.anl.terminal.emulator.TerminalRow.Companion.EMPTY

/** Receives everything the emulator produces besides screen updates. */
interface TerminalClient {
    /** Bytes to send back to the application (device reports, key/mouse encodings). */
    fun write(data: ByteArray) {}
    fun onTitleChanged(title: String) {}
    fun onClipboardSet(text: String) {}
    fun onBell() {}
    fun onColorsChanged() {}
}

/**
 * A VT100/xterm-compatible terminal emulator. The parser follows the DEC ANSI parser state
 * machine (vt100.net/emu/dec_ansi_parser); control functions follow ECMA-48 and the xterm
 * control sequence documentation.
 *
 * Not thread-safe: callers serialize access (the session and view lock on this object).
 */
class TerminalEmulator(
    rows: Int,
    cols: Int,
    private val scrollbackLines: Int = DEFAULT_SCROLLBACK,
    var client: TerminalClient = object : TerminalClient {},
) {
    var rows = rows.coerceAtLeast(1)
        private set
    var cols = cols.coerceAtLeast(1)
        private set

    private val mainBuffer = ScreenBuffer(this.rows, this.cols, scrollbackLines)
    private val altBuffer = ScreenBuffer(this.rows, this.cols, 0)

    /** The buffer currently displayed. */
    var buffer = mainBuffer
        private set
    val isAltScreen: Boolean get() = buffer === altBuffer

    val colors = TerminalColors()

    var cursorX = 0
        private set
    var cursorY = 0
        private set
    private var pendingWrap = false
    private var style = TextStyle.NORMAL
    private var scrollTop = 0
    private var scrollBottom = this.rows - 1
    private var tabStops = BooleanArray(this.cols)

    // Modes.
    private var originMode = false
    private var autoWrap = true
    private var insertMode = false
    var newLineMode = false
        private set
    var cursorVisible = true
        private set
    var applicationCursorKeys = false
        private set
    var applicationKeypad = false
        private set
    var bracketedPaste = false
        private set
    var reverseVideo = false
        private set
    var mouseTracking = MOUSE_NONE
        private set
    var sgrMouse = false
        private set
    var focusReporting = false
        private set

    /** DECSCUSR shape: one of [CURSOR_BLOCK], [CURSOR_UNDERLINE], [CURSOR_BAR]. */
    var cursorShape = CURSOR_BLOCK
        private set

    var title = ""
        private set

    // Character sets (G0/G1, shifted in with SI/SO).
    private val charsets = intArrayOf(CHARSET_ASCII, CHARSET_ASCII)
    private var activeCharset = 0

    private class SavedCursor {
        var x = 0
        var y = 0
        var style = TextStyle.NORMAL
        var pendingWrap = false
        var originMode = false
        var autoWrap = true
        var g0 = CHARSET_ASCII
        var g1 = CHARSET_ASCII
        var active = 0
    }

    private val savedMain = SavedCursor()
    private val savedAlt = SavedCursor()

    // Parser state.
    private val decoder = Utf8Decoder()
    private var state = STATE_GROUND
    private val params = IntArray(MAX_PARAMS)
    private val subParam = BooleanArray(MAX_PARAMS)
    private var paramCount = 0
    private var privateMarker = 0
    private var intermediate = 0
    private var intermediateOverflow = false
    private val oscBuffer = StringBuilder()
    private var lastPrinted = -1

    private val sink = Utf8Decoder.Sink { process(it) }

    init {
        resetTabStops()
    }

    /** Line ids stay stable while output scrolls: `id = y + scrolledOut`. */
    val scrolledOut: Long get() = buffer.scrolledOut

    fun append(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        decoder.decode(data, offset, length, sink)
    }

    fun append(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        append(bytes, 0, bytes.size)
    }

    // ---------------------------------------------------------------- parser

    private fun process(cp: Int) {
        // Transitions valid from any state.
        when (cp) {
            0x18, 0x1A -> {
                state = STATE_GROUND
                return
            }
            0x1B -> {
                if (state == STATE_OSC) dispatchOsc(terminatedByBel = false)
                state = STATE_ESCAPE
                intermediate = 0
                intermediateOverflow = false
                return
            }
        }
        when (state) {
            STATE_GROUND -> when {
                cp < 0x20 -> execute(cp)
                cp == 0x7F || cp in 0x80..0x9F -> Unit
                else -> print(cp)
            }
            STATE_ESCAPE -> when {
                cp < 0x20 -> execute(cp)
                cp in 0x20..0x2F -> {
                    collectIntermediate(cp)
                    state = STATE_ESCAPE_INTERMEDIATE
                }
                cp == '['.code -> startCsi()
                cp == ']'.code -> {
                    oscBuffer.setLength(0)
                    state = STATE_OSC
                }
                cp == 'P'.code || cp == 'X'.code || cp == '^'.code || cp == '_'.code -> state = STATE_IGNORE_STRING
                cp == 0x7F -> Unit
                else -> {
                    state = STATE_GROUND
                    escDispatch(cp)
                }
            }
            STATE_ESCAPE_INTERMEDIATE -> when {
                cp < 0x20 -> execute(cp)
                cp in 0x20..0x2F -> collectIntermediate(cp)
                cp in 0x30..0x7E -> {
                    state = STATE_GROUND
                    escDispatch(cp)
                }
            }
            STATE_CSI_ENTRY, STATE_CSI_PARAM, STATE_CSI_INTERMEDIATE, STATE_CSI_IGNORE -> csiChar(cp)
            STATE_OSC -> when {
                cp == 0x07 -> {
                    dispatchOsc(terminatedByBel = true)
                    state = STATE_GROUND
                }
                cp < 0x20 -> Unit
                oscBuffer.length < MAX_OSC_LENGTH -> oscBuffer.appendCodePoint(cp)
            }
            STATE_IGNORE_STRING -> Unit // DCS/SOS/PM/APC payloads are consumed until ST.
        }
    }

    private fun collectIntermediate(cp: Int) {
        if (intermediate == 0) intermediate = cp else intermediateOverflow = true
    }

    private fun startCsi() {
        state = STATE_CSI_ENTRY
        paramCount = 0
        privateMarker = 0
        intermediate = 0
        intermediateOverflow = false
    }

    private fun csiChar(cp: Int) {
        when {
            cp < 0x20 -> execute(cp)
            cp == 0x7F || cp >= 0x80 -> Unit
            cp in 0x40..0x7E -> {
                val ignored = state == STATE_CSI_IGNORE || intermediateOverflow
                state = STATE_GROUND
                if (!ignored) csiDispatch(cp)
            }
            state == STATE_CSI_IGNORE -> Unit
            cp in 0x20..0x2F -> {
                collectIntermediate(cp)
                state = STATE_CSI_INTERMEDIATE
            }
            state == STATE_CSI_INTERMEDIATE -> state = STATE_CSI_IGNORE // parameter after intermediate
            cp in 0x3C..0x3F -> {
                if (state == STATE_CSI_ENTRY) {
                    privateMarker = cp
                    state = STATE_CSI_PARAM
                } else {
                    state = STATE_CSI_IGNORE
                }
            }
            cp in '0'.code..'9'.code -> {
                state = STATE_CSI_PARAM
                if (paramCount == 0) newParam(false)
                val i = paramCount - 1
                val current = if (params[i] < 0) 0 else params[i]
                params[i] = (current * 10 + (cp - '0'.code)).coerceAtMost(MAX_PARAM_VALUE)
            }
            cp == ';'.code || cp == ':'.code -> {
                state = STATE_CSI_PARAM
                if (paramCount == 0) newParam(false)
                newParam(cp == ':'.code)
            }
        }
    }

    private fun newParam(sub: Boolean) {
        if (paramCount >= MAX_PARAMS) return
        params[paramCount] = -1
        subParam[paramCount] = sub
        paramCount++
    }

    /** Raw parameter [i], or [default] when absent. */
    private fun param(i: Int, default: Int): Int = if (i < paramCount && params[i] >= 0) params[i] else default

    /** Parameter [i] as a count: absent or zero means 1. */
    private fun count(i: Int): Int = param(i, 1).coerceAtLeast(1)

    // ------------------------------------------------------------ C0 / ESC

    private fun execute(cp: Int) {
        when (cp) {
            0x07 -> client.onBell()
            0x08 -> {
                if (cursorX > 0) cursorX--
                pendingWrap = false
            }
            0x09 -> tabForward(1)
            0x0A, 0x0B, 0x0C -> {
                if (newLineMode) cursorX = 0
                lineFeed()
            }
            0x0D -> {
                cursorX = 0
                pendingWrap = false
            }
            0x0E -> activeCharset = 1
            0x0F -> activeCharset = 0
        }
    }

    private fun escDispatch(cp: Int) {
        val c = cp.toChar()
        when (intermediate) {
            0 -> when (c) {
                '7' -> saveCursor()
                '8' -> restoreCursor()
                'D' -> lineFeed()
                'E' -> {
                    cursorX = 0
                    lineFeed()
                }
                'H' -> tabStops[cursorX] = true
                'M' -> reverseIndex()
                'c' -> reset()
                '=' -> applicationKeypad = true
                '>' -> applicationKeypad = false
                'Z' -> reply("\u001b[?62;22c")
            }
            '('.code, ')'.code -> {
                val slot = if (intermediate == '('.code) 0 else 1
                charsets[slot] = when (c) {
                    '0' -> CHARSET_DEC_GRAPHICS
                    'A' -> CHARSET_UK
                    else -> CHARSET_ASCII
                }
            }
            '#'.code -> if (c == '8') alignmentTest()
        }
    }

    // ----------------------------------------------------------------- CSI

    private fun csiDispatch(finalChar: Int) {
        val f = finalChar.toChar()
        when (privateMarker) {
            0 -> when (intermediate) {
                0 -> csiStandard(f)
                '!'.code -> if (f == 'p') softReset()
                ' '.code -> if (f == 'q') setCursorShape(param(0, 0))
                '$'.code -> if (f == 'p') reportMode(param(0, 0), private = false)
            }
            '?'.code -> when {
                intermediate == '$'.code && f == 'p' -> reportMode(param(0, 0), private = true)
                intermediate != 0 -> Unit
                f == 'h' -> for (i in 0 until paramCount) setDecMode(param(i, 0), true)
                f == 'l' -> for (i in 0 until paramCount) setDecMode(param(i, 0), false)
                f == 'J' -> eraseInDisplay(param(0, 0))
                f == 'K' -> eraseInLine(param(0, 0))
                f == 'n' -> if (param(0, 0) == 6) {
                    reply("\u001b[?${reportedRow()};${cursorX + 1}R")
                }
            }
            '>'.code -> if (f == 'c' && intermediate == 0 && param(0, 0) == 0) reply("\u001b[>1;10;0c")
        }
    }

    private fun csiStandard(f: Char) {
        when (f) {
            '@' -> buffer.row(cursorY).insertCells(cursorX, count(0), eraseStyle()).also { pendingWrap = false }
            'A' -> cursorUp(count(0))
            'B', 'e' -> cursorDown(count(0))
            'C', 'a' -> moveCursorTo(cursorX + count(0), cursorY)
            'D' -> moveCursorTo(cursorX - count(0), cursorY)
            'E' -> {
                cursorDown(count(0))
                cursorX = 0
            }
            'F' -> {
                cursorUp(count(0))
                cursorX = 0
            }
            'G', '`' -> moveCursorTo(count(0) - 1, cursorY)
            'H', 'f' -> setCursorPosition(count(1) - 1, count(0) - 1)
            'I' -> tabForward(count(0))
            'J' -> eraseInDisplay(param(0, 0))
            'K' -> eraseInLine(param(0, 0))
            'L' -> insertLines(count(0))
            'M' -> deleteLines(count(0))
            'P' -> buffer.row(cursorY).deleteCells(cursorX, count(0), eraseStyle()).also { pendingWrap = false }
            'S' -> scrollUp(count(0))
            'T' -> if (paramCount <= 1) scrollDown(count(0))
            'X' -> {
                val row = buffer.row(cursorY)
                row.erase(cursorX, cursorX + count(0), eraseStyle())
                pendingWrap = false
            }
            'Z' -> tabBackward(count(0))
            'b' -> if (lastPrinted >= 0) {
                val cp = lastPrinted
                repeat(count(0).coerceAtMost(rows * cols)) { printMapped(cp) }
            }
            'c' -> if (param(0, 0) == 0) reply("\u001b[?62;22c")
            'd' -> setCursorPosition(cursorX, count(0) - 1)
            'g' -> when (param(0, 0)) {
                0 -> tabStops[cursorX] = false
                3 -> tabStops.fill(false)
            }
            'h' -> for (i in 0 until paramCount) setAnsiMode(param(i, 0), true)
            'l' -> for (i in 0 until paramCount) setAnsiMode(param(i, 0), false)
            'm' -> selectGraphicRendition()
            'n' -> when (param(0, 0)) {
                5 -> reply("\u001b[0n")
                6 -> reply("\u001b[${reportedRow()};${cursorX + 1}R")
            }
            'r' -> setScrollRegion()
            's' -> saveCursor()
            't' -> when (param(0, 0)) {
                18 -> reply("\u001b[8;$rows;${cols}t")
            }
            'u' -> restoreCursor()
        }
    }

    private fun reportedRow(): Int = cursorY + 1 - if (originMode) scrollTop else 0

    private fun setAnsiMode(mode: Int, on: Boolean) {
        when (mode) {
            4 -> insertMode = on
            20 -> newLineMode = on
        }
    }

    private fun setDecMode(mode: Int, on: Boolean) {
        when (mode) {
            1 -> applicationCursorKeys = on
            5 -> reverseVideo = on
            6 -> {
                originMode = on
                setCursorPosition(0, 0)
            }
            7 -> {
                autoWrap = on
                if (!on) pendingWrap = false
            }
            25 -> cursorVisible = on
            9 -> mouseTracking = if (on) MOUSE_X10 else MOUSE_NONE
            1000 -> mouseTracking = if (on) MOUSE_NORMAL else MOUSE_NONE
            1002 -> mouseTracking = if (on) MOUSE_BUTTON_EVENT else MOUSE_NONE
            1003 -> mouseTracking = if (on) MOUSE_ANY_EVENT else MOUSE_NONE
            1004 -> focusReporting = on
            1006 -> sgrMouse = on
            2004 -> bracketedPaste = on
            47, 1047 -> if (on) enterAltScreen(clear = mode == 1047) else leaveAltScreen()
            1048 -> if (on) saveCursor() else restoreCursor()
            1049 -> if (on) {
                if (!isAltScreen) {
                    saveCursor()
                    enterAltScreen(clear = true)
                }
            } else if (isAltScreen) {
                leaveAltScreen()
                restoreCursor()
            }
        }
    }

    private fun decModeState(mode: Int): Boolean? = when (mode) {
        1 -> applicationCursorKeys
        5 -> reverseVideo
        6 -> originMode
        7 -> autoWrap
        25 -> cursorVisible
        9 -> mouseTracking == MOUSE_X10
        1000 -> mouseTracking == MOUSE_NORMAL
        1002 -> mouseTracking == MOUSE_BUTTON_EVENT
        1003 -> mouseTracking == MOUSE_ANY_EVENT
        1004 -> focusReporting
        1006 -> sgrMouse
        2004 -> bracketedPaste
        47, 1047, 1049 -> isAltScreen
        else -> null
    }

    private fun reportMode(mode: Int, private: Boolean) {
        val value = if (private) {
            decModeState(mode)
        } else when (mode) {
            4 -> insertMode
            20 -> newLineMode
            else -> null
        }
        val code = when (value) {
            null -> 0
            true -> 1
            false -> 2
        }
        reply("\u001b[${if (private) "?" else ""}$mode;$code\$y")
    }

    private fun setCursorShape(ps: Int) {
        cursorShape = when (ps) {
            3, 4 -> CURSOR_UNDERLINE
            5, 6 -> CURSOR_BAR
            else -> CURSOR_BLOCK
        }
    }

    private fun setScrollRegion() {
        val top = (param(0, 1).coerceAtLeast(1) - 1)
        val bottom = (param(1, rows).let { if (it == 0) rows else it }.coerceAtMost(rows) - 1)
        if (top < bottom) {
            scrollTop = top
            scrollBottom = bottom
            setCursorPosition(0, 0)
        }
    }

    // ----------------------------------------------------------------- SGR

    private fun selectGraphicRendition() {
        if (paramCount == 0) {
            style = TextStyle.NORMAL
            return
        }
        var fg = TextStyle.fg(style)
        var bg = TextStyle.bg(style)
        var attrs = TextStyle.attrs(style)
        var i = 0
        while (i < paramCount) {
            val p = param(i, 0)
            // Colon sub-parameters belonging to this parameter.
            var subEnd = i + 1
            while (subEnd < paramCount && subParam[subEnd]) subEnd++
            val hasSubs = subEnd > i + 1
            when (p) {
                0 -> {
                    fg = TextStyle.COLOR_DEFAULT_FG
                    bg = TextStyle.COLOR_DEFAULT_BG
                    attrs = 0
                }
                1 -> attrs = attrs or TextStyle.BOLD
                2 -> attrs = attrs or TextStyle.DIM
                3 -> attrs = attrs or TextStyle.ITALIC
                4 -> attrs = if (hasSubs && param(i + 1, 1) == 0) {
                    attrs and TextStyle.UNDERLINE.inv()
                } else {
                    attrs or TextStyle.UNDERLINE
                }
                5, 6 -> attrs = attrs or TextStyle.BLINK
                7 -> attrs = attrs or TextStyle.INVERSE
                8 -> attrs = attrs or TextStyle.INVISIBLE
                9 -> attrs = attrs or TextStyle.STRIKETHROUGH
                21 -> attrs = attrs or TextStyle.UNDERLINE
                22 -> attrs = attrs and (TextStyle.BOLD or TextStyle.DIM).inv()
                23 -> attrs = attrs and TextStyle.ITALIC.inv()
                24 -> attrs = attrs and TextStyle.UNDERLINE.inv()
                25 -> attrs = attrs and TextStyle.BLINK.inv()
                27 -> attrs = attrs and TextStyle.INVERSE.inv()
                28 -> attrs = attrs and TextStyle.INVISIBLE.inv()
                29 -> attrs = attrs and TextStyle.STRIKETHROUGH.inv()
                in 30..37 -> fg = p - 30
                39 -> fg = TextStyle.COLOR_DEFAULT_FG
                in 40..47 -> bg = p - 40
                49 -> bg = TextStyle.COLOR_DEFAULT_BG
                in 90..97 -> fg = p - 90 + 8
                in 100..107 -> bg = p - 100 + 8
                38, 48, 58 -> {
                    val color: Int?
                    if (hasSubs) {
                        color = extendedColor(i + 1, subEnd - i - 1, colonForm = true)
                    } else {
                        val consumed = extendedColorLength(i + 1)
                        color = extendedColor(i + 1, consumed, colonForm = false)
                        subEnd = (i + 1 + consumed).coerceAtMost(paramCount)
                    }
                    if (color != null) {
                        if (p == 38) fg = color else if (p == 48) bg = color
                    }
                }
            }
            i = subEnd
        }
        style = TextStyle.encode(fg, bg, attrs)
    }

    /** How many `;`-separated parameters follow 38/48 in the legacy (semicolon) form. */
    private fun extendedColorLength(start: Int): Int = when (param(start, -1)) {
        5 -> 2
        2 -> 4
        else -> 1
    }

    private fun extendedColor(start: Int, length: Int, colonForm: Boolean): Int? {
        if (length < 1) return null
        return when (param(start, -1)) {
            5 -> if (length >= 2) param(start + 1, -1).takeIf { it in 0..255 } else null
            2 -> {
                // Colon form may carry a color-space id: 2:<cs>:r:g:b.
                val rgbStart = if (colonForm && length >= 5) start + 2 else start + 1
                if (rgbStart + 3 > start + length) return null
                val r = param(rgbStart, 0)
                val g = param(rgbStart + 1, 0)
                val b = param(rgbStart + 2, 0)
                if (r > 255 || g > 255 || b > 255) null else TextStyle.rgb(r, g, b)
            }
            else -> null
        }
    }

    // ----------------------------------------------------------------- OSC

    private fun dispatchOsc(terminatedByBel: Boolean) {
        val text = oscBuffer.toString()
        oscBuffer.setLength(0)
        val sep = text.indexOf(';')
        val command = (if (sep < 0) text else text.substring(0, sep)).toIntOrNull() ?: return
        val arg = if (sep < 0) "" else text.substring(sep + 1)
        val terminator = if (terminatedByBel) "\u0007" else "\u001b\\"
        when (command) {
            0, 2 -> {
                title = arg
                client.onTitleChanged(arg)
            }
            4 -> {
                val parts = arg.split(';')
                var changed = false
                var j = 0
                while (j + 1 < parts.size) {
                    val index = parts[j].toIntOrNull()
                    val spec = parts[j + 1]
                    if (index != null && index in 0..255) {
                        if (spec == "?") {
                            reply("\u001b]4;$index;${TerminalColors.formatColorSpec(colors.current[index])}$terminator")
                        } else {
                            TerminalColors.parseColorSpec(spec)?.let {
                                colors.current[index] = it
                                changed = true
                            }
                        }
                    }
                    j += 2
                }
                if (changed) client.onColorsChanged()
            }
            10, 11, 12 -> {
                // Each following spec applies to the next dynamic color (10 -> 11 -> 12).
                var slot = command
                for (spec in arg.split(';')) {
                    if (slot > 12) break
                    val index = when (slot) {
                        10 -> TextStyle.COLOR_DEFAULT_FG
                        11 -> TextStyle.COLOR_DEFAULT_BG
                        else -> TextStyle.COLOR_CURSOR
                    }
                    if (spec == "?") {
                        reply("\u001b]$slot;${TerminalColors.formatColorSpec(colors.current[index])}$terminator")
                    } else {
                        TerminalColors.parseColorSpec(spec)?.let {
                            colors.current[index] = it
                            client.onColorsChanged()
                        }
                    }
                    slot++
                }
            }
            52 -> {
                val payloadSep = arg.indexOf(';')
                if (payloadSep < 0) return
                val payload = arg.substring(payloadSep + 1)
                if (payload == "?") return // Reading the clipboard is not allowed.
                Base64.decode(payload)?.let { client.onClipboardSet(String(it, Charsets.UTF_8)) }
            }
            104 -> {
                if (arg.isEmpty()) {
                    for (i in 0 until 256) colors.reset(i)
                } else {
                    arg.split(';').mapNotNull { it.toIntOrNull() }.filter { it in 0..255 }.forEach { colors.reset(it) }
                }
                client.onColorsChanged()
            }
            110 -> colors.reset(TextStyle.COLOR_DEFAULT_FG).also { client.onColorsChanged() }
            111 -> colors.reset(TextStyle.COLOR_DEFAULT_BG).also { client.onColorsChanged() }
            112 -> colors.reset(TextStyle.COLOR_CURSOR).also { client.onColorsChanged() }
        }
    }

    // ------------------------------------------------------------- printing

    private fun print(cp: Int) {
        printMapped(mapCharset(cp))
    }

    private fun mapCharset(cp: Int): Int = when (charsets[activeCharset]) {
        CHARSET_DEC_GRAPHICS -> if (cp in 0x5F..0x7E) DEC_GRAPHICS[cp - 0x5F] else cp
        CHARSET_UK -> if (cp == '#'.code) 0xA3 else cp
        else -> cp
    }

    private fun printMapped(cp: Int) {
        var width = WcWidth.of(cp)
        if (width < 0) return
        if (width == 0) return // Combining marks are not composed onto the previous cell.
        if (width == 2 && cols < 2) width = 1
        lastPrinted = cp

        if (pendingWrap) {
            if (autoWrap) wrapToNextLine()
            pendingWrap = false
        }
        if (width == 2 && cursorX == cols - 1) {
            if (!autoWrap) return
            buffer.row(cursorY).erase(cursorX, cols, eraseStyle())
            wrapToNextLine()
        }

        val row = buffer.row(cursorY)
        if (insertMode) row.insertCells(cursorX, width, eraseStyle())
        row.splitWide(cursorX, cursorX + width, eraseStyle())
        row.set(cursorX, cp, style)
        if (width == 2) row.set(cursorX + 1, TerminalRow.WIDE_TAIL, style)

        val next = cursorX + width
        if (next >= cols) {
            cursorX = cols - 1
            pendingWrap = autoWrap
        } else {
            cursorX = next
        }
    }

    private fun wrapToNextLine() {
        buffer.row(cursorY).wrapped = true
        cursorX = 0
        lineFeed()
    }

    // ------------------------------------------------------ cursor movement

    private fun moveCursorTo(x: Int, y: Int) {
        cursorX = x.coerceIn(0, cols - 1)
        cursorY = y.coerceIn(0, rows - 1)
        pendingWrap = false
    }

    /** CUP semantics: coordinates are relative to the scroll region in origin mode. */
    private fun setCursorPosition(x: Int, y: Int) {
        if (originMode) {
            moveCursorTo(x, (y + scrollTop).coerceIn(scrollTop, scrollBottom))
        } else {
            moveCursorTo(x, y)
        }
    }

    private fun cursorUp(n: Int) {
        val limit = if (cursorY >= scrollTop) scrollTop else 0
        moveCursorTo(cursorX, (cursorY - n).coerceAtLeast(limit))
    }

    private fun cursorDown(n: Int) {
        val limit = if (cursorY <= scrollBottom) scrollBottom else rows - 1
        moveCursorTo(cursorX, (cursorY + n).coerceAtMost(limit))
    }

    private fun lineFeed() {
        pendingWrap = false
        if (cursorY == scrollBottom) {
            scrollUp(1)
        } else if (cursorY < rows - 1) {
            cursorY++
        }
    }

    private fun reverseIndex() {
        pendingWrap = false
        if (cursorY == scrollTop) {
            scrollDown(1)
        } else if (cursorY > 0) {
            cursorY--
        }
    }

    private fun scrollUp(n: Int) {
        val fullScreen = scrollTop == 0 && scrollBottom == rows - 1
        buffer.scrollUp(scrollTop, scrollBottom, n, eraseStyle(), toHistory = fullScreen && !isAltScreen)
    }

    private fun scrollDown(n: Int) {
        buffer.scrollDown(scrollTop, scrollBottom, n, eraseStyle())
    }

    private fun insertLines(n: Int) {
        if (cursorY < scrollTop || cursorY > scrollBottom) return
        buffer.scrollDown(cursorY, scrollBottom, n, eraseStyle())
        cursorX = 0
        pendingWrap = false
    }

    private fun deleteLines(n: Int) {
        if (cursorY < scrollTop || cursorY > scrollBottom) return
        buffer.scrollUp(cursorY, scrollBottom, n, eraseStyle(), toHistory = false)
        cursorX = 0
        pendingWrap = false
    }

    private fun tabForward(n: Int) {
        var x = cursorX
        repeat(n) {
            x++
            while (x < cols - 1 && !tabStops[x]) x++
        }
        cursorX = x.coerceAtMost(cols - 1)
        pendingWrap = false
    }

    private fun tabBackward(n: Int) {
        var x = cursorX
        repeat(n) {
            x--
            while (x > 0 && !tabStops[x]) x--
        }
        cursorX = x.coerceAtLeast(0)
        pendingWrap = false
    }

    private fun resetTabStops() {
        tabStops = BooleanArray(cols) { it > 0 && it % 8 == 0 }
    }

    // ------------------------------------------------------------- erasing

    /** Erased cells keep the current background color (xterm's back-color-erase). */
    private fun eraseStyle(): Long = TextStyle.encode(TextStyle.COLOR_DEFAULT_FG, TextStyle.bg(style), 0)

    private fun eraseInDisplay(mode: Int) {
        val blank = eraseStyle()
        when (mode) {
            0 -> {
                eraseInLine(0)
                for (y in cursorY + 1 until rows) buffer.row(y).clear(blank)
            }
            1 -> {
                for (y in 0 until cursorY) buffer.row(y).clear(blank)
                eraseInLine(1)
            }
            2 -> for (y in 0 until rows) buffer.row(y).clear(blank)
            3 -> buffer.clearHistory()
        }
        pendingWrap = false
    }

    private fun eraseInLine(mode: Int) {
        val row = buffer.row(cursorY)
        val blank = eraseStyle()
        when (mode) {
            0 -> {
                row.erase(cursorX, cols, blank)
                row.wrapped = false
            }
            1 -> row.erase(0, cursorX + 1, blank)
            2 -> row.clear(blank)
        }
        pendingWrap = false
    }

    private fun alignmentTest() {
        for (y in 0 until rows) {
            val row = buffer.row(y)
            row.wrapped = false
            for (x in 0 until cols) row.set(x, 'E'.code, TextStyle.NORMAL)
        }
        scrollTop = 0
        scrollBottom = rows - 1
        moveCursorTo(0, 0)
    }

    // ------------------------------------------------ save/restore, screens

    private fun currentSaved() = if (isAltScreen) savedAlt else savedMain

    private fun saveCursor() {
        val s = currentSaved()
        s.x = cursorX
        s.y = cursorY
        s.style = style
        s.pendingWrap = pendingWrap
        s.originMode = originMode
        s.autoWrap = autoWrap
        s.g0 = charsets[0]
        s.g1 = charsets[1]
        s.active = activeCharset
    }

    private fun restoreCursor() {
        val s = currentSaved()
        cursorX = s.x.coerceIn(0, cols - 1)
        cursorY = s.y.coerceIn(0, rows - 1)
        style = s.style
        pendingWrap = s.pendingWrap && cursorX == cols - 1
        originMode = s.originMode
        autoWrap = s.autoWrap
        charsets[0] = s.g0
        charsets[1] = s.g1
        activeCharset = s.active
    }

    private fun enterAltScreen(clear: Boolean) {
        if (isAltScreen) return
        buffer = altBuffer
        if (clear) altBuffer.clearAll(eraseStyle())
    }

    private fun leaveAltScreen() {
        if (!isAltScreen) return
        buffer = mainBuffer
    }

    private fun softReset() {
        cursorVisible = true
        insertMode = false
        originMode = false
        autoWrap = true
        applicationKeypad = false
        applicationCursorKeys = false
        scrollTop = 0
        scrollBottom = rows - 1
        charsets[0] = CHARSET_ASCII
        charsets[1] = CHARSET_ASCII
        activeCharset = 0
        style = TextStyle.NORMAL
        pendingWrap = false
        savedMain.apply { x = 0; y = 0; style = TextStyle.NORMAL; originMode = false; autoWrap = true }
        savedAlt.apply { x = 0; y = 0; style = TextStyle.NORMAL; originMode = false; autoWrap = true }
    }

    /** RIS: full reset to the initial state. */
    fun reset() {
        leaveAltScreen()
        softReset()
        newLineMode = false
        reverseVideo = false
        bracketedPaste = false
        mouseTracking = MOUSE_NONE
        sgrMouse = false
        focusReporting = false
        cursorShape = CURSOR_BLOCK
        title = ""
        colors.reset()
        client.onColorsChanged()
        resetTabStops()
        mainBuffer.clearAll(TextStyle.NORMAL)
        mainBuffer.clearHistory()
        altBuffer.clearAll(TextStyle.NORMAL)
        cursorX = 0
        cursorY = 0
        lastPrinted = -1
        state = STATE_GROUND
    }

    // -------------------------------------------------------------- resize

    fun resize(newRows: Int, newCols: Int) {
        val r = newRows.coerceAtLeast(1)
        val c = newCols.coerceAtLeast(1)
        if (r == rows && c == cols) return
        val blank = TextStyle.NORMAL
        if (isAltScreen) {
            // The main screen's cursor lives in the saved slot while the alt screen is up.
            val main = mainBuffer.resizeReflow(r, c, savedMain.x.coerceIn(0, cols - 1), savedMain.y.coerceIn(0, rows - 1), blank)
            savedMain.x = main[0]
            savedMain.y = main[1]
            val alt = altBuffer.resizeSimple(r, c, cursorX, cursorY, blank)
            cursorX = alt[0]
            cursorY = alt[1]
        } else {
            // A pending wrap means the cursor logically sits just past the last column.
            val logicalX = cursorX + if (pendingWrap) 1 else 0
            val main = mainBuffer.resizeReflow(r, c, logicalX, cursorY, blank)
            altBuffer.resizeSimple(r, c, 0, 0, blank)
            cursorX = main[0]
            cursorY = main[1]
        }
        rows = r
        cols = c
        scrollTop = 0
        scrollBottom = rows - 1
        pendingWrap = false
        val oldStops = tabStops
        tabStops = BooleanArray(cols) { if (it < oldStops.size) oldStops[it] else it % 8 == 0 }
    }

    // ------------------------------------------------------ input encoding

    /** Sends [text] typed or pasted by the user, bracketing it if the application asked. */
    fun paste(text: String) {
        val normalized = text.replace("\r\n", "\r").replace('\n', '\r')
        if (bracketedPaste) {
            reply("\u001b[200~" + normalized.replace("\u001b", "") + "\u001b[201~")
        } else {
            reply(normalized)
        }
    }

    fun focusChanged(focused: Boolean) {
        if (focusReporting) reply(if (focused) "\u001b[I" else "\u001b[O")
    }

    /**
     * Reports a mouse event at 0-based cell ([col], [row]) if mouse tracking is enabled.
     * [button] is 0-2 for left/middle/right, or [MOUSE_WHEEL_UP]/[MOUSE_WHEEL_DOWN].
     */
    fun sendMouseEvent(button: Int, col: Int, row: Int, pressed: Boolean, motion: Boolean = false) {
        when (mouseTracking) {
            MOUSE_NONE -> return
            MOUSE_X10 -> if (!pressed || motion) return
            MOUSE_NORMAL -> if (motion) return
        }
        val x = col.coerceIn(0, cols - 1) + 1
        val y = row.coerceIn(0, rows - 1) + 1
        var code = button
        if (motion) code += 32
        if (sgrMouse) {
            reply("\u001b[<$code;$x;${y}${if (pressed) 'M' else 'm'}")
        } else {
            if (!pressed && button < MOUSE_WHEEL_UP) code = 3 + if (motion) 32 else 0
            if (x > 223 || y > 223) return
            val bytes = byteArrayOf(0x1B, '['.code.toByte(), 'M'.code.toByte(), (32 + code).toByte(), (32 + x).toByte(), (32 + y).toByte())
            client.write(bytes)
        }
    }

    private fun reply(text: String) = client.write(text.toByteArray(Charsets.UTF_8))

    // ---------------------------------------------------------- text access

    /**
     * Text between two cell positions (inclusive), rows in buffer coordinates (negative for
     * scrollback). Autowrapped rows are joined without a newline; trailing blanks are trimmed.
     */
    fun getText(startY: Int, startX: Int, endY: Int, endX: Int): String {
        val sb = StringBuilder()
        val first = startY.coerceAtLeast(-buffer.historySize)
        val last = endY.coerceAtMost(rows - 1)
        for (y in first..last) {
            val row = buffer.row(y)
            val from = if (y == startY) startX.coerceIn(0, cols) else 0
            val to = if (y == endY) (endX + 1).coerceIn(0, row.cols) else row.cols
            val lineStart = sb.length
            for (x in from until to) {
                when (val cp = row.text[x]) {
                    EMPTY -> sb.append(' ')
                    TerminalRow.WIDE_TAIL -> Unit
                    else -> sb.appendCodePoint(cp)
                }
            }
            if (!row.wrapped || y == last) {
                var end = sb.length
                while (end > lineStart && sb[end - 1] == ' ') end--
                sb.setLength(end)
                if (y != last) sb.append('\n')
            }
        }
        return sb.toString()
    }

    /** Scrollback plus screen as text, without trailing empty lines. */
    fun getAllText(): String = getText(-buffer.historySize, 0, rows - 1, cols - 1).trimEnd('\n')

    companion object {
        const val DEFAULT_SCROLLBACK = 2000

        const val MOUSE_NONE = 0
        const val MOUSE_X10 = 9
        const val MOUSE_NORMAL = 1000
        const val MOUSE_BUTTON_EVENT = 1002
        const val MOUSE_ANY_EVENT = 1003
        const val MOUSE_WHEEL_UP = 64
        const val MOUSE_WHEEL_DOWN = 65

        const val CURSOR_BLOCK = 0
        const val CURSOR_UNDERLINE = 1
        const val CURSOR_BAR = 2

        private const val STATE_GROUND = 0
        private const val STATE_ESCAPE = 1
        private const val STATE_ESCAPE_INTERMEDIATE = 2
        private const val STATE_CSI_ENTRY = 3
        private const val STATE_CSI_PARAM = 4
        private const val STATE_CSI_INTERMEDIATE = 5
        private const val STATE_CSI_IGNORE = 6
        private const val STATE_OSC = 7
        private const val STATE_IGNORE_STRING = 8

        private const val MAX_PARAMS = 32
        private const val MAX_PARAM_VALUE = 99999
        private const val MAX_OSC_LENGTH = 1 shl 20

        private const val CHARSET_ASCII = 0
        private const val CHARSET_DEC_GRAPHICS = 1
        private const val CHARSET_UK = 2

        /** DEC Special Graphics for 0x5F..0x7E. */
        private val DEC_GRAPHICS = intArrayOf(
            0x00A0, 0x25C6, 0x2592, 0x2409, 0x240C, 0x240D, 0x240A, 0x00B0, 0x00B1, 0x2424,
            0x240B, 0x2518, 0x2510, 0x250C, 0x2514, 0x253C, 0x23BA, 0x23BB, 0x2500, 0x23BC,
            0x23BD, 0x251C, 0x2524, 0x2534, 0x252C, 0x2502, 0x2264, 0x2265, 0x03C0, 0x2260,
            0x00A3, 0x00B7,
        )
    }
}

/** Minimal RFC 4648 base64 decoder (java.util.Base64 needs API 26). */
internal object Base64 {
    fun decode(input: String): ByteArray? {
        val out = java.io.ByteArrayOutputStream(input.length * 3 / 4)
        var acc = 0
        var bits = 0
        for (ch in input) {
            val v = when (ch) {
                in 'A'..'Z' -> ch - 'A'
                in 'a'..'z' -> ch - 'a' + 26
                in '0'..'9' -> ch - '0' + 52
                '+', '-' -> 62
                '/', '_' -> 63
                '=' -> break
                ' ', '\n', '\r', '\t' -> continue
                else -> return null
            }
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((acc shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
