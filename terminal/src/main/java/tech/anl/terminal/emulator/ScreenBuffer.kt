package tech.anl.terminal.emulator

/**
 * One line of cells. [text] holds a code point per cell, [EMPTY] for a never-written
 * (blank) cell, or [WIDE_TAIL] for the right half of a double-width character.
 */
class TerminalRow(cols: Int, blank: Long = TextStyle.NORMAL) {
    var text = IntArray(cols)
        private set
    var style = LongArray(cols).apply { fill(blank) }
        private set

    /** True when the line continues on the next row because of autowrap. */
    var wrapped = false

    val cols: Int get() = text.size

    fun clear(blank: Long) = erase(0, cols, blank).also { wrapped = false }

    /** Blanks cells in [from, to), also blanking any wide character cut in half by the range. */
    fun erase(from: Int, to: Int, blank: Long) {
        val start = from.coerceIn(0, cols)
        val end = to.coerceIn(start, cols)
        if (start >= end) return
        splitWide(start, end, blank)
        for (i in start until end) {
            text[i] = EMPTY
            style[i] = blank
        }
    }

    /** Before overwriting [from, to): blank the orphaned halves of wide characters at the edges. */
    fun splitWide(from: Int, to: Int, blank: Long) {
        if (from in 1 until cols && text[from] == WIDE_TAIL) {
            text[from - 1] = EMPTY
            style[from - 1] = blank
        }
        if (to in 1 until cols && text[to] == WIDE_TAIL) {
            text[to] = EMPTY
            style[to] = blank
        }
    }

    fun set(col: Int, cp: Int, s: Long) {
        text[col] = cp
        style[col] = s
    }

    /** Inserts [n] blank cells at [at], pushing the rest of the line right (cells fall off the end). */
    fun insertCells(at: Int, n: Int, blank: Long) {
        if (at !in 0 until cols) return
        val count = n.coerceAtMost(cols - at)
        splitWide(at, at, blank)
        System.arraycopy(text, at, text, at + count, cols - at - count)
        System.arraycopy(style, at, style, at + count, cols - at - count)
        for (i in at until at + count) set(i, EMPTY, blank)
        // A wide head pushed onto the last column lost its tail.
        if (lastIsOrphanHead()) set(cols - 1, EMPTY, blank)
    }

    /** Deletes [n] cells at [at], pulling the rest of the line left and blank-filling the end. */
    fun deleteCells(at: Int, n: Int, blank: Long) {
        if (at !in 0 until cols) return
        val count = n.coerceAtMost(cols - at)
        splitWide(at, at + count, blank)
        System.arraycopy(text, at + count, text, at, cols - at - count)
        System.arraycopy(style, at + count, style, at, cols - at - count)
        for (i in cols - count until cols) set(i, EMPTY, blank)
    }

    private fun lastIsOrphanHead(): Boolean {
        val cp = text[cols - 1]
        return cp > 0 && WcWidth.of(cp) == 2
    }

    /** Number of cells up to and including the last one with visible content or a non-default background. */
    fun usedLength(): Int {
        for (i in cols - 1 downTo 0) {
            if (text[i] != EMPTY || TextStyle.bg(style[i]) != TextStyle.COLOR_DEFAULT_BG) return i + 1
        }
        return 0
    }

    fun isBlank(): Boolean = usedLength() == 0

    /** Returns a copy with [newCols] columns, truncating or blank-padding. */
    fun resized(newCols: Int, blank: Long): TerminalRow {
        val row = TerminalRow(newCols, blank)
        val n = minOf(newCols, cols)
        System.arraycopy(text, 0, row.text, 0, n)
        System.arraycopy(style, 0, row.style, 0, n)
        if (n in 1 until cols && text[n] == WIDE_TAIL) row.set(n - 1, EMPTY, blank)
        row.wrapped = wrapped && newCols >= cols
        return row
    }

    fun copyFrom(other: TerminalRow) {
        if (other.cols != cols) {
            text = other.text.copyOf()
            style = other.style.copyOf()
        } else {
            System.arraycopy(other.text, 0, text, 0, cols)
            System.arraycopy(other.style, 0, style, 0, cols)
        }
        wrapped = other.wrapped
    }

    override fun toString(): String = buildString {
        for (cp in text) when (cp) {
            EMPTY -> append(' ')
            WIDE_TAIL -> Unit
            else -> appendCodePoint(cp)
        }
    }

    companion object {
        const val EMPTY = 0
        const val WIDE_TAIL = -1
    }
}

/**
 * The visible grid plus (for the main screen) a bounded scrollback history. Rows are
 * addressed by `y`: 0 until [rows] for the screen, -[historySize] until 0 for history.
 */
class ScreenBuffer(rows: Int, cols: Int, private val maxHistory: Int) {
    var rows = rows
        private set
    var cols = cols
        private set

    private var lines = Array(rows) { TerminalRow(cols) }
    private val history = ArrayDeque<TerminalRow>()

    /** Total number of lines ever scrolled into history; lets callers keep stable line ids. */
    var scrolledOut = 0L
        private set

    val historySize: Int get() = history.size

    fun row(y: Int): TerminalRow = if (y >= 0) lines[y] else history[history.size + y]

    fun clearHistory() = history.clear()

    fun clearAll(blank: Long) {
        for (row in lines) row.clear(blank)
    }

    /** Scrolls rows [top, bottom] up by [n]; with [toHistory], rows leaving the top are kept as scrollback. */
    fun scrollUp(top: Int, bottom: Int, n: Int, blank: Long, toHistory: Boolean) {
        val count = n.coerceAtMost(bottom - top + 1)
        if (count <= 0) return
        val removed = lines.copyOfRange(top, top + count)
        System.arraycopy(lines, top + count, lines, top, bottom + 1 - top - count)
        for (i in 0 until count) {
            var row = removed[i]
            if (toHistory && maxHistory > 0) {
                history.addLast(row)
                scrolledOut++
                row = if (history.size > maxHistory) history.removeFirst() else TerminalRow(cols, blank)
                if (row.cols != cols) row = TerminalRow(cols, blank)
            }
            row.clear(blank)
            lines[bottom - count + 1 + i] = row
        }
    }

    fun scrollDown(top: Int, bottom: Int, n: Int, blank: Long) {
        val count = n.coerceAtMost(bottom - top + 1)
        if (count <= 0) return
        val removed = lines.copyOfRange(bottom - count + 1, bottom + 1)
        System.arraycopy(lines, top, lines, top + count, bottom + 1 - top - count)
        for (i in 0 until count) {
            removed[i].clear(blank)
            lines[top + i] = removed[i]
        }
    }

    /**
     * Resizes without reflow: columns are truncated or padded, and if the cursor would fall
     * off the bottom the content is shifted up. Returns the new cursor position.
     */
    fun resizeSimple(newRows: Int, newCols: Int, cursorX: Int, cursorY: Int, blank: Long): IntArray {
        val shift = (cursorY - newRows + 1).coerceAtLeast(0)
        lines = Array(newRows) { i ->
            val src = i + shift
            if (src < rows) lines[src].resized(newCols, blank) else TerminalRow(newCols, blank)
        }
        history.clear()
        rows = newRows
        cols = newCols
        return intArrayOf(cursorX.coerceIn(0, newCols - 1), (cursorY - shift).coerceIn(0, newRows - 1))
    }

    /**
     * Resizes with reflow: autowrapped lines (history included) are joined and re-wrapped to
     * [newCols]. The top of the screen stays anchored unless the cursor would fall off the
     * bottom. Returns the new cursor position.
     */
    fun resizeReflow(newRows: Int, newCols: Int, cursorX: Int, cursorY: Int, blank: Long): IntArray {
        val all = ArrayList<TerminalRow>(history.size + rows)
        all.addAll(history)
        all.addAll(lines)
        val oldHistory = history.size
        val cursorIndex = oldHistory + cursorY

        // Trailing blank rows below the cursor carry nothing worth reflowing.
        var last = all.size - 1
        while (last > cursorIndex && all[last].isBlank() && !all[last - 1].wrapped) last--

        val out = ArrayList<TerminalRow>()
        var newCursorRow = -1
        var newCursorCol = 0
        var screenTopRow = -1
        val cells = IntList()
        val styles = LongList()

        var i = 0
        while (i <= last) {
            cells.clear()
            styles.clear()
            var cursorOffset = -1
            var topOffset = -1
            while (true) {
                val row = all[i]
                if (i == cursorIndex) cursorOffset = cells.size + cursorX
                if (i == oldHistory) topOffset = cells.size
                val continues = row.wrapped && i < last
                var length = if (continues) row.cols else row.usedLength()
                // A wide character that did not fit left a blank in the last column; drop it.
                if (continues && row.text[row.cols - 1] == TerminalRow.EMPTY &&
                    all[i + 1].cols > 1 && all[i + 1].text[1] == TerminalRow.WIDE_TAIL
                ) length--
                for (c in 0 until length) {
                    cells.add(row.text[c])
                    styles.add(row.style[c])
                }
                if (!continues) break
                i++
            }
            i++
            while (cursorOffset >= 0 && cells.size <= cursorOffset) {
                cells.add(TerminalRow.EMPTY)
                styles.add(blank)
            }

            val lineStart = out.size
            var row = TerminalRow(newCols, blank)
            out.add(row)
            var col = 0
            var idx = 0
            while (idx < cells.size) {
                val cp = cells[idx]
                var width = if (idx + 1 < cells.size && cells[idx + 1] == TerminalRow.WIDE_TAIL) 2 else 1
                if (cp == TerminalRow.WIDE_TAIL) {
                    idx++
                    continue
                }
                if (width > newCols) width = 1
                if (col + width > newCols) {
                    row.wrapped = true
                    row = TerminalRow(newCols, blank)
                    out.add(row)
                    col = 0
                }
                if (cursorOffset in idx until idx + width) {
                    newCursorRow = out.size - 1
                    newCursorCol = col
                }
                row.set(col, cp, styles[idx])
                if (width == 2) row.set(col + 1, TerminalRow.WIDE_TAIL, styles[idx])
                col += width
                idx += if (width == 2) 2 else 1
            }
            if (topOffset >= 0) {
                screenTopRow = (lineStart + topOffset / newCols).coerceAtMost(out.size - 1)
            }
        }

        if (newCursorRow < 0) newCursorRow = (out.size - 1).coerceAtLeast(0)
        var screenTop = if (screenTopRow < 0) 0 else screenTopRow
        if (newCursorRow >= screenTop + newRows) screenTop = newCursorRow - newRows + 1
        if (newCursorRow < screenTop) screenTop = newCursorRow

        history.clear()
        for (r in 0 until screenTop) history.addLast(out[r])
        while (history.size > maxHistory) history.removeFirst()
        lines = Array(newRows) { r ->
            val src = screenTop + r
            if (src < out.size) out[src] else TerminalRow(newCols, blank)
        }
        lines[newRows - 1].wrapped = false
        rows = newRows
        cols = newCols
        return intArrayOf(newCursorCol.coerceIn(0, newCols - 1), newCursorRow - screenTop)
    }
}

internal class IntList {
    private var data = IntArray(64)
    var size = 0
        private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    operator fun get(i: Int) = data[i]
    fun clear() {
        size = 0
    }
}

internal class LongList {
    private var data = LongArray(64)
    var size = 0
        private set

    fun add(v: Long) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    operator fun get(i: Int) = data[i]
    fun clear() {
        size = 0
    }
}
