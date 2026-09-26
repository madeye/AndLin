package tech.anl.terminal.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalEmulatorTest {
    private val output = StringBuilder()
    private var clipboard: String? = null
    private var titleSeen: String? = null

    private fun emulator(rows: Int = 5, cols: Int = 10, scrollback: Int = 100) =
        TerminalEmulator(rows, cols, scrollback, object : TerminalClient {
            override fun write(data: ByteArray) {
                output.append(String(data, Charsets.UTF_8))
            }

            override fun onClipboardSet(text: String) {
                clipboard = text
            }

            override fun onTitleChanged(title: String) {
                titleSeen = title
            }
        })

    private fun TerminalEmulator.line(y: Int) = buffer.row(y).toString().trimEnd()
    private fun TerminalEmulator.feed(s: String) = append(s)
    private fun TerminalEmulator.assertCursor(x: Int, y: Int) {
        assertEquals("cursor x", x, cursorX)
        assertEquals("cursor y", y, cursorY)
    }

    // ---------------------------------------------------------------- UTF-8

    @Test
    fun utf8SplitAcrossWrites() {
        val e = emulator()
        val bytes = "a€😀b".toByteArray(Charsets.UTF_8)
        for (b in bytes) e.append(byteArrayOf(b))
        assertEquals('a'.code, e.buffer.row(0).text[0])
        assertEquals(0x20AC, e.buffer.row(0).text[1])
        assertEquals(0x1F600, e.buffer.row(0).text[2])
        assertEquals(TerminalRow.WIDE_TAIL, e.buffer.row(0).text[3])
        assertEquals('b'.code, e.buffer.row(0).text[4])
    }

    @Test
    fun utf8DecoderRejectsMalformedInput() {
        val out = ArrayList<Int>()
        val d = Utf8Decoder()
        // Overlong '/', lone continuation, truncated 3-byte sequence followed by ASCII, encoded surrogate.
        d.decode(byteArrayOf(0xC0.toByte(), 0xAF.toByte(), 0x80.toByte(), 0xE2.toByte(), 0x82.toByte(), 'x'.code.toByte(),
            0xED.toByte(), 0xA0.toByte(), 0x80.toByte())) { out.add(it) }
        assertEquals(listOf(0xFFFD, 0xFFFD, 0xFFFD, 0xFFFD, 'x'.code, 0xFFFD), out)
    }

    @Test
    fun utf8FourByteSplitInTheMiddle() {
        val out = ArrayList<Int>()
        val d = Utf8Decoder()
        val bytes = "😀".toByteArray(Charsets.UTF_8)
        d.decode(bytes, 0, 2) { out.add(it) }
        assertTrue(out.isEmpty())
        d.decode(bytes, 2, 2) { out.add(it) }
        assertEquals(listOf(0x1F600), out)
    }

    // --------------------------------------------------------------- cursor

    @Test
    fun printingAdvancesCursor() {
        val e = emulator()
        e.feed("hello")
        assertEquals("hello", e.line(0))
        e.assertCursor(5, 0)
    }

    @Test
    fun cursorMovementSequences() {
        val e = emulator(rows = 10, cols = 20)
        e.feed("\u001b[5;7H")
        e.assertCursor(6, 4)
        e.feed("\u001b[2A")
        e.assertCursor(6, 2)
        e.feed("\u001b[3B")
        e.assertCursor(6, 5)
        e.feed("\u001b[4C")
        e.assertCursor(10, 5)
        e.feed("\u001b[20D")
        e.assertCursor(0, 5)
        e.feed("\u001b[2E")
        e.assertCursor(0, 7)
        e.feed("\u001b[5G\u001b[3F")
        e.assertCursor(0, 4)
        e.feed("\u001b[9d")
        e.assertCursor(0, 8)
        e.feed("\u001b[12`")
        e.assertCursor(11, 8)
        e.feed("\u001b[f")
        e.assertCursor(0, 0)
        e.feed("\u001b[99;99H")
        e.assertCursor(19, 9)
        e.feed("\u001b[0A") // zero counts as one
        e.assertCursor(19, 8)
    }

    @Test
    fun carriageReturnBackspaceAndTabs() {
        val e = emulator(rows = 3, cols = 30)
        e.feed("ab\tc")
        e.assertCursor(9, 0)
        e.feed("\r")
        e.assertCursor(0, 0)
        e.feed("\t\t\u001b[Z")
        e.assertCursor(8, 0)
        e.feed("\u001b[3g\u001b[5G\u001bH\r\t")
        e.assertCursor(4, 0)
        e.feed("\bX")
        assertEquals("ab X    c", e.line(0))
    }

    @Test
    fun saveAndRestoreCursor() {
        val e = emulator()
        e.feed("\u001b[3;4H\u001b[31m\u001b7\u001b[H\u001b[0m\u001b8x")
        e.assertCursor(4, 2)
        assertEquals(1, TextStyle.fg(e.buffer.row(2).style[3]))
    }

    // ------------------------------------------------------------------ SGR

    @Test
    fun sgrBasicAndBrightColors() {
        val e = emulator()
        e.feed("\u001b[1;3;4;31;42ma\u001b[0;95;104mb\u001b[39;49mc")
        val s0 = e.buffer.row(0).style[0]
        assertEquals(1, TextStyle.fg(s0))
        assertEquals(2, TextStyle.bg(s0))
        assertEquals(TextStyle.BOLD or TextStyle.ITALIC or TextStyle.UNDERLINE, TextStyle.attrs(s0))
        val s1 = e.buffer.row(0).style[1]
        assertEquals(13, TextStyle.fg(s1))
        assertEquals(12, TextStyle.bg(s1))
        assertEquals(0, TextStyle.attrs(s1))
        assertEquals(TextStyle.NORMAL, e.buffer.row(0).style[2])
    }

    @Test
    fun sgr256AndTrueColor() {
        val e = emulator()
        e.feed("\u001b[38;5;196;48;2;10;20;30ma")
        e.feed("\u001b[38:2::1:2:3;48:5:17mb")
        e.feed("\u001b[38:2:4:5:6mc")
        val row = e.buffer.row(0)
        assertEquals(196, TextStyle.fg(row.style[0]))
        assertEquals(TextStyle.rgb(10, 20, 30), TextStyle.bg(row.style[0]))
        assertEquals(TextStyle.rgb(1, 2, 3), TextStyle.fg(row.style[1]))
        assertEquals(17, TextStyle.bg(row.style[1]))
        assertEquals(TextStyle.rgb(4, 5, 6), TextStyle.fg(row.style[2]))
        assertEquals(0xFF0A141E.toInt(), e.colors.resolve(TextStyle.bg(row.style[0])))
    }

    @Test
    fun sgrAttributesOnAndOff() {
        val e = emulator()
        e.feed("\u001b[2;5;7;8;9ma\u001b[22;25;27;28;29mb\u001b[4:3mc\u001b[4:0md")
        val row = e.buffer.row(0)
        assertEquals(
            TextStyle.DIM or TextStyle.BLINK or TextStyle.INVERSE or TextStyle.INVISIBLE or TextStyle.STRIKETHROUGH,
            TextStyle.attrs(row.style[0]),
        )
        assertEquals(0, TextStyle.attrs(row.style[1]))
        assertEquals(TextStyle.UNDERLINE, TextStyle.attrs(row.style[2]))
        assertEquals(0, TextStyle.attrs(row.style[3]))
    }

    // -------------------------------------------------------------- wrapping

    @Test
    fun autowrapHasPendingWrapState() {
        val e = emulator(rows = 3, cols = 5)
        e.feed("abcde")
        // The cursor stays on the last column until another character arrives.
        e.assertCursor(4, 0)
        assertEquals("abcde", e.line(0))
        assertEquals("", e.line(1))
        e.feed("f")
        assertEquals("f", e.line(1))
        e.assertCursor(1, 1)
        assertTrue(e.buffer.row(0).wrapped)
    }

    @Test
    fun carriageReturnCancelsPendingWrap() {
        val e = emulator(rows = 3, cols = 5)
        e.feed("abcde\rX")
        assertEquals("Xbcde", e.line(0))
        e.assertCursor(1, 0)
    }

    @Test
    fun cursorMoveCancelsPendingWrap() {
        val e = emulator(rows = 3, cols = 5)
        e.feed("abcde\u001b[DX")
        assertEquals("abcXe", e.line(0))
        assertEquals("", e.line(1))
    }

    @Test
    fun noAutowrapOverwritesLastColumn() {
        val e = emulator(rows = 3, cols = 5)
        e.feed("\u001b[?7labcdefg")
        assertEquals("abcdg", e.line(0))
        e.assertCursor(4, 0)
        assertEquals("", e.line(1))
    }

    // ----------------------------------------------------------- wide chars

    @Test
    fun wideCharactersTakeTwoCells() {
        val e = emulator(rows = 3, cols = 6)
        e.feed("中文a")
        e.assertCursor(5, 0)
        val row = e.buffer.row(0)
        assertEquals('中'.code, row.text[0])
        assertEquals(TerminalRow.WIDE_TAIL, row.text[1])
        assertEquals("中文a", e.line(0))
        assertEquals(2, WcWidth.of(0x1F600))
        assertEquals(0, WcWidth.of(0x0301))
        assertEquals(1, WcWidth.of('a'.code))
    }

    @Test
    fun wideCharacterWrapsWhenOnlyOneCellLeft() {
        val e = emulator(rows = 3, cols = 5)
        e.feed("abcd中")
        assertEquals("abcd", e.line(0))
        assertEquals("中", e.line(1))
        e.assertCursor(2, 1)
        assertTrue(e.buffer.row(0).wrapped)
    }

    @Test
    fun overwritingHalfOfWideCharacterBlanksTheOtherHalf() {
        val e = emulator(rows = 3, cols = 6)
        e.feed("中文\u001b[2Gx")
        val row = e.buffer.row(0)
        assertEquals(TerminalRow.EMPTY, row.text[0])
        assertEquals('x'.code, row.text[1])
        assertEquals('文'.code, row.text[2])
    }

    // --------------------------------------------------------------- erasing

    @Test
    fun eraseInLineAndDisplay() {
        val e = emulator(rows = 3, cols = 5)
        e.feed("abcde\r\nfghij\r\nklmno")
        e.feed("\u001b[2;3H\u001b[K")
        assertEquals("fg", e.line(1))
        e.feed("\u001b[1K")
        assertEquals("", e.line(1))
        e.feed("\u001b[1;3H\u001b[0J")
        assertEquals("ab", e.line(0))
        assertEquals("", e.line(2))
        e.feed("\u001b[H12345\u001b[3;1Hxyz\u001b[2;1H\u001b[1J")
        assertEquals("", e.line(0))
        assertEquals("xyz", e.line(2))
        e.feed("\u001b[2J")
        for (y in 0 until 3) assertEquals("", e.line(y))
    }

    @Test
    fun eraseInsertDeleteCharacters() {
        val e = emulator(rows = 2, cols = 8)
        e.feed("abcdefgh\u001b[1;3H\u001b[2X")
        assertEquals("ab  efgh", e.line(0))
        e.feed("\u001b[2P")
        assertEquals("abefgh", e.line(0))
        e.feed("\u001b[3@")
        assertEquals("ab   efg", e.line(0))
        e.assertCursor(2, 0)
    }

    @Test
    fun insertModeShiftsText() {
        val e = emulator(rows = 2, cols = 8)
        e.feed("abcd\r\u001b[4hXY\u001b[4lZ")
        assertEquals("XYZbcd", e.line(0))
    }

    @Test
    fun backgroundColorErase() {
        val e = emulator(rows = 2, cols = 4)
        e.feed("\u001b[44m\u001b[2J")
        assertEquals(4, TextStyle.bg(e.buffer.row(1).style[3]))
    }

    // --------------------------------------------------------- scroll region

    @Test
    fun scrollRegionConfinesLineFeeds() {
        val e = emulator(rows = 5, cols = 5)
        e.feed("1\r\n2\r\n3\r\n4\r\n5")
        e.feed("\u001b[2;4r")
        e.assertCursor(0, 0) // DECSTBM homes the cursor
        e.feed("\u001b[4;1H\nX")
        assertEquals(listOf("1", "3", "4", "X", "5"), (0 until 5).map { e.line(it) })
        assertEquals(0, e.buffer.historySize) // partial-region scrolls never reach scrollback
    }

    @Test
    fun reverseIndexAtTopScrollsRegionDown() {
        val e = emulator(rows = 4, cols = 5)
        e.feed("a\r\nb\r\nc\r\nd\u001b[2;3r\u001b[2;1H\u001bM")
        assertEquals(listOf("a", "", "b", "d"), (0 until 4).map { e.line(it) })
    }

    @Test
    fun insertAndDeleteLinesInsideRegion() {
        val e = emulator(rows = 4, cols = 5)
        e.feed("a\r\nb\r\nc\r\nd\u001b[2;1H\u001b[L")
        assertEquals(listOf("a", "", "b", "c"), (0 until 4).map { e.line(it) })
        e.feed("\u001b[2M")
        assertEquals(listOf("a", "c", "", ""), (0 until 4).map { e.line(it) })
    }

    @Test
    fun scrollUpAndDownSequences() {
        val e = emulator(rows = 3, cols = 5)
        e.feed("a\r\nb\r\nc\u001b[S")
        assertEquals(listOf("b", "c", ""), (0 until 3).map { e.line(it) })
        e.feed("\u001b[2T")
        assertEquals(listOf("", "", "b"), (0 until 3).map { e.line(it) })
    }

    @Test
    fun originModeIsRelativeToRegion() {
        val e = emulator(rows = 6, cols = 5)
        e.feed("\u001b[3;5r\u001b[?6h")
        e.assertCursor(0, 2)
        e.feed("\u001b[10;1H")
        e.assertCursor(0, 4)
        output.setLength(0)
        e.feed("\u001b[6n")
        assertEquals("\u001b[3;1R", output.toString())
    }

    @Test
    fun scrollbackCollectsLinesAndIsBounded() {
        val e = emulator(rows = 2, cols = 5, scrollback = 3)
        for (i in 1..6) e.feed("$i\r\n")
        assertEquals(3, e.buffer.historySize)
        assertEquals("3", e.buffer.row(-3).toString().trim())
        assertEquals("5", e.buffer.row(-1).toString().trim())
        e.feed("\u001b[3J")
        assertEquals(0, e.buffer.historySize)
    }

    // ------------------------------------------------------------ alt screen

    @Test
    fun alternateScreen1049SavesAndRestores() {
        val e = emulator(rows = 3, cols = 5)
        e.feed("main\u001b[?1049h")
        assertTrue(e.isAltScreen)
        assertEquals("", e.line(0))
        e.feed("\u001b[2;2Halt")
        e.feed("\u001b[?1049l")
        assertFalse(e.isAltScreen)
        assertEquals("main", e.line(0))
        e.assertCursor(4, 0)
    }

    @Test
    fun alternateScreenHasNoScrollback() {
        val e = emulator(rows = 2, cols = 5)
        e.feed("\u001b[?47h1\r\n2\r\n3\r\n")
        assertEquals(0, e.buffer.historySize)
        e.feed("\u001b[?47l")
        assertFalse(e.isAltScreen)
    }

    @Test
    fun alternateScreen1047ClearsOnEntry() {
        val e = emulator(rows = 2, cols = 5)
        e.feed("\u001b[?1047hxx\u001b[?1047l\u001b[?1047h")
        assertEquals("", e.line(0))
    }

    // --------------------------------------------------------------- reports

    @Test
    fun deviceStatusAndAttributes() {
        val e = emulator(rows = 5, cols = 10)
        e.feed("\u001b[2;3H\u001b[6n")
        assertEquals("\u001b[2;3R", output.toString())
        output.setLength(0)
        e.feed("\u001b[5n")
        assertEquals("\u001b[0n", output.toString())
        output.setLength(0)
        e.feed("\u001b[c")
        assertEquals("\u001b[?62;22c", output.toString())
        output.setLength(0)
        e.feed("\u001b[>c")
        assertEquals("\u001b[>1;10;0c", output.toString())
        output.setLength(0)
        e.feed("\u001b[?6n")
        assertEquals("\u001b[?2;3R", output.toString())
        output.setLength(0)
        e.feed("\u001b[18t")
        assertEquals("\u001b[8;5;10t", output.toString())
    }

    @Test
    fun modeReportAndModes() {
        val e = emulator()
        e.feed("\u001b[?2004h\u001b[?1h\u001b[?25l\u001b=")
        assertTrue(e.bracketedPaste)
        assertTrue(e.applicationCursorKeys)
        assertFalse(e.cursorVisible)
        assertTrue(e.applicationKeypad)
        e.feed("\u001b[?2004\$p")
        assertEquals("\u001b[?2004;1\$y", output.toString())
        e.feed("\u001b>\u001b[?25h")
        assertFalse(e.applicationKeypad)
        assertTrue(e.cursorVisible)
    }

    // ------------------------------------------------------------------- OSC

    @Test
    fun oscTitleWithBelAndSt() {
        val e = emulator()
        e.feed("\u001b]0;hello\u0007")
        assertEquals("hello", e.title)
        e.feed("\u001b]2;wörld\u001b\\x")
        assertEquals("wörld", e.title)
        assertEquals("wörld", titleSeen)
        assertEquals("x", e.line(0))
    }

    @Test
    fun oscColorsAndQueries() {
        val e = emulator()
        e.feed("\u001b]4;1;#ff0000\u0007")
        assertEquals(0xFFFF0000.toInt(), e.colors.current[1])
        e.feed("\u001b]11;rgb:00/80/ff\u001b\\")
        assertEquals(0xFF0080FF.toInt(), e.colors.current[TextStyle.COLOR_DEFAULT_BG])
        e.feed("\u001b]10;?\u0007")
        assertEquals("\u001b]10;rgb:e5e5/e5e5/e5e5\u0007", output.toString())
        e.feed("\u001b]104\u0007")
        assertEquals(0xFFCD3131.toInt(), e.colors.current[1])
    }

    @Test
    fun oscClipboardSetOnly() {
        val e = emulator()
        e.feed("\u001b]52;c;aGVsbG8gd29ybGQ=\u0007")
        assertEquals("hello world", clipboard)
        clipboard = null
        e.feed("\u001b]52;c;?\u0007")
        assertNull(clipboard)
        assertEquals("", output.toString())
    }

    @Test
    fun dcsPayloadIsIgnored() {
        val e = emulator()
        e.feed("\u001bP1\$qm\u001b\\ok")
        assertEquals("ok", e.line(0))
    }

    // ------------------------------------------------------------- charsets

    @Test
    fun decSpecialGraphics() {
        val e = emulator()
        e.feed("\u001b(0lqk\u001b(Bq")
        assertEquals("┌─┐q", e.line(0))
        e.feed("\r\n\u001b)0\u000eq\u000fq")
        assertEquals("─q", e.line(1))
    }

    // ---------------------------------------------------------------- resets

    @Test
    fun fullAndSoftReset() {
        val e = emulator()
        e.feed("\u001b[31mabc\u001b[?25l\u001b[!p")
        assertTrue(e.cursorVisible)
        e.feed("d")
        assertEquals(TextStyle.NORMAL, e.buffer.row(0).style[3])
        e.feed("\u001b]0;t\u0007\u001bc")
        assertEquals("", e.line(0))
        assertEquals("", e.title)
        e.assertCursor(0, 0)
    }

    @Test
    fun repeatAndAlignment() {
        val e = emulator(rows = 2, cols = 6)
        e.feed("x\u001b[3b")
        assertEquals("xxxx", e.line(0))
        e.feed("\u001b#8")
        assertEquals("EEEEEE", e.line(1))
    }

    @Test
    fun cancelAbortsSequence() {
        val e = emulator()
        e.feed("\u001b[31\u0018m")
        assertEquals("m", e.line(0))
        assertEquals(TextStyle.NORMAL, e.buffer.row(0).style[0])
    }

    // ---------------------------------------------------------------- resize

    @Test
    fun resizeNarrowerReflowsWrappedLines() {
        val e = emulator(rows = 4, cols = 10)
        e.feed("0123456789abc")
        e.assertCursor(3, 1)
        e.resize(4, 5)
        assertEquals(listOf("01234", "56789", "abc", ""), (0 until 4).map { e.line(it) })
        e.assertCursor(3, 2)
        assertTrue(e.buffer.row(0).wrapped)
        assertTrue(e.buffer.row(1).wrapped)
        assertFalse(e.buffer.row(2).wrapped)
    }

    @Test
    fun resizeWiderJoinsWrappedLines() {
        val e = emulator(rows = 4, cols = 5)
        e.feed("0123456789ab\r\nxy")
        e.resize(4, 20)
        assertEquals(listOf("0123456789ab", "xy", "", ""), (0 until 4).map { e.line(it) })
        e.assertCursor(2, 1)
    }

    @Test
    fun resizeKeepsCursorOnScreenWhenShrinkingRows() {
        val e = emulator(rows = 5, cols = 5)
        e.feed("a\r\nb\r\nc\r\nd\r\ne")
        e.resize(2, 5)
        assertEquals("d", e.line(0))
        assertEquals("e", e.line(1))
        e.assertCursor(1, 1)
        assertEquals(3, e.buffer.historySize)
    }

    @Test
    fun resizeKeepsClearedScreenAnchored() {
        val e = emulator(rows = 3, cols = 5)
        e.feed("a\r\nb\r\nc\r\nd\r\n$ ")
        e.resize(5, 5)
        e.assertCursor(2, 2)
        assertEquals("$", e.line(2))
    }

    @Test
    fun resizeReflowMovesCursorIntoPlaceWithWideChars() {
        val e = emulator(rows = 3, cols = 6)
        e.feed("ab中文")
        e.resize(3, 3)
        assertEquals(listOf("ab", "中", "文"), (0 until 3).map { e.line(it) })
        e.assertCursor(2, 2)
    }

    @Test
    fun resizeWhileOnAltScreen() {
        val e = emulator(rows = 4, cols = 10)
        e.feed("0123456789ab\u001b[?1049h\u001b[4;1Hz")
        e.resize(3, 5)
        e.assertCursor(1, 2)
        e.feed("\u001b[?1049l")
        assertEquals(listOf("01234", "56789", "ab"), (0 until 3).map { e.line(it) })
    }

    // ------------------------------------------------------------ text/input

    @Test
    fun getTextJoinsWrappedRows() {
        val e = emulator(rows = 3, cols = 5)
        e.feed("abcdefg\r\nhi")
        assertEquals("abcdefg\nhi", e.getAllText())
        assertEquals("cdef", e.getText(0, 2, 1, 0))
    }

    @Test
    fun bracketedPasteWrapsAndStripsEscapes() {
        val e = emulator()
        e.paste("a\nb")
        assertEquals("a\rb", output.toString())
        output.setLength(0)
        e.feed("\u001b[?2004h")
        e.paste("x\u001by\r\n")
        assertEquals("\u001b[200~xy\r\u001b[201~", output.toString())
    }

    @Test
    fun mouseReportingSgrAndLegacy() {
        val e = emulator()
        e.sendMouseEvent(0, 1, 1, pressed = true)
        assertEquals("", output.toString())
        e.feed("\u001b[?1000h\u001b[?1006h")
        e.sendMouseEvent(0, 2, 3, pressed = true)
        e.sendMouseEvent(0, 2, 3, pressed = false)
        e.sendMouseEvent(0, 2, 3, pressed = true, motion = true) // not reported in 1000 mode
        assertEquals("\u001b[<0;3;4M\u001b[<0;3;4m", output.toString())
        output.setLength(0)
        e.feed("\u001b[?1006l\u001b[?1002h")
        e.sendMouseEvent(TerminalEmulator.MOUSE_WHEEL_UP, 0, 0, pressed = true)
        assertEquals("\u001b[M" + (32 + 64).toChar() + "!!", output.toString())
    }

    @Test
    fun focusEvents() {
        val e = emulator()
        e.focusChanged(true)
        assertEquals("", output.toString())
        e.feed("\u001b[?1004h")
        e.focusChanged(true)
        e.focusChanged(false)
        assertEquals("\u001b[I\u001b[O", output.toString())
    }

    @Test
    fun keyEncoding() {
        assertEquals("\u001b[A", KeyEncoder.encode(KeyEncoder.Key.UP, 0, appCursor = false))
        assertEquals("\u001bOA", KeyEncoder.encode(KeyEncoder.Key.UP, 0, appCursor = true))
        assertEquals("\u001b[1;5C", KeyEncoder.encode(KeyEncoder.Key.RIGHT, KeyEncoder.MOD_CTRL, appCursor = true))
        assertEquals("\u001b[3~", KeyEncoder.encode(KeyEncoder.Key.DELETE, 0, false))
        assertEquals("\u001b[5;3~", KeyEncoder.encode(KeyEncoder.Key.PAGE_UP, KeyEncoder.MOD_ALT, false))
        assertEquals("\u001bOP", KeyEncoder.encode(KeyEncoder.Key.F1, 0, false))
        assertEquals("\u001b[24~", KeyEncoder.encode(KeyEncoder.Key.F12, 0, false))
        assertEquals("\u001b[Z", KeyEncoder.encode(KeyEncoder.Key.TAB, KeyEncoder.MOD_SHIFT, false))
        assertEquals("\u007f", KeyEncoder.encode(KeyEncoder.Key.BACKSPACE, 0, false))
        assertEquals("\u0003", KeyEncoder.encodeChar('c'.code, ctrl = true, alt = false))
        assertEquals("\u001bx", KeyEncoder.encodeChar('x'.code, ctrl = false, alt = true))
        assertEquals("\u001b\u0001", KeyEncoder.encodeChar('a'.code, ctrl = true, alt = true))
    }
}
