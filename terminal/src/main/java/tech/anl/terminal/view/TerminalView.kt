package tech.anl.terminal.view

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.InputType
import android.util.AttributeSet
import android.util.TypedValue
import android.view.ActionMode
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.OverScroller
import tech.anl.terminal.R
import tech.anl.terminal.TerminalSession
import tech.anl.terminal.emulator.KeyEncoder
import tech.anl.terminal.emulator.TerminalEmulator
import tech.anl.terminal.emulator.TerminalRow
import tech.anl.terminal.emulator.TextStyle
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Renders a [TerminalSession] and turns touch, keyboard and IME input into terminal input. */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Actions the hosting activity performs on the view's behalf. */
    interface Listener {
        fun onCopyAll()
        fun onToggleKeyboard()
        fun onRequestKeyboard()
        fun onFontSizeChanged(sizePx: Float)
        fun onCloseSession()
        /** A tap landed on [url], an http(s) link in the output. */
        fun onOpenUrl(url: String) {}
    }

    var listener: Listener? = null
    var session: TerminalSession? = null
        private set

    /** Latched Ctrl/Alt from the extra-keys row; cleared after the next key. */
    var ctrlLatched = false
        private set
    var altLatched = false
        private set
    var onModifiersChanged: (() -> Unit)? = null

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val fillPaint = Paint()
    private val charBuffer = CharArray(512)

    var fontSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, DEFAULT_FONT_SP, resources.displayMetrics)
        private set
    private var cellWidth = 1f
    private var cellHeight = 1
    private var baseline = 0

    private var viewRows = 0
    private var viewCols = 0

    /** Rows scrolled back into history; 0 means following the live screen. */
    private var scrollOffset = 0
    private var lastScrolledOut = 0L
    private var scrollRemainder = 0f
    private val scroller = OverScroller(context)
    private var flingActive = false

    // Selection endpoints as (line id, column); line id = buffer row + emulator.scrolledOut.
    private var selecting = false
        set(value) {
            if (field == value) return
            field = value
            onSelectionChanged?.invoke(value)
        }

    /**
     * Called when a text selection starts or ends. With predictive back (target SDK 33+ opted in,
     * always on from 36) KEYCODE_BACK never reaches [onKeyDown], so the host clears the selection
     * from an OnBackPressedCallback that it enables only while this reports true.
     */
    var onSelectionChanged: ((Boolean) -> Unit)? = null

    val isSelecting: Boolean get() = selecting
    private var selStartLine = 0L
    private var selStartCol = 0
    private var selEndLine = 0L
    private var selEndCol = 0
    private var draggingHandle = HANDLE_NONE
    private var draggingFromHandle = false
    private var actionMode: ActionMode? = null
    private val handleRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10f, resources.displayMetrics)

    // Pinch zoom: previewed by scaling the canvas, applied once when the gesture ends.
    private var pinchScale = 1f
    private var pinchFocusX = 0f
    private var pinchFocusY = 0f
    private var scaling = false

    private var mouseButtonDown = false

    private val gestureDetector = GestureDetector(context, GestureListener())
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener()).apply {
        isQuickScaleEnabled = false
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        updateFontMetrics()
    }

    // ------------------------------------------------------------ session

    fun attachSession(newSession: TerminalSession) {
        if (session === newSession) return
        clearSelection()
        session = newSession
        scrollOffset = 0
        lastScrolledOut = synchronized(newSession.emulator) { newSession.emulator.scrolledOut }
        updateSize(force = true)
        invalidate()
    }

    /** Called (on the main thread) when the session's screen content changed. */
    fun onScreenUpdated() {
        invalidate()
    }

    fun setFontSize(px: Float) {
        val min = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, MIN_FONT_SP, resources.displayMetrics)
        val maxPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, MAX_FONT_SP, resources.displayMetrics)
        val clamped = px.coerceIn(min, maxPx)
        if (clamped == fontSizePx) return
        fontSizePx = clamped
        updateFontMetrics()
        // Snap back to the live screen so the cursor row stays in view after the resize.
        scrollOffset = 0
        clearSelection()
        updateSize(force = false)
        invalidate()
    }

    private fun updateFontMetrics() {
        textPaint.textSize = fontSizePx
        cellWidth = textPaint.measureText("M")
        val fm = textPaint.fontMetricsInt
        cellHeight = ceil((fm.descent - fm.ascent).toDouble()).toInt().coerceAtLeast(1)
        baseline = -fm.ascent
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateSize(force = false)
    }

    private fun updateSize(force: Boolean) {
        val s = session ?: return
        if (width == 0 || height == 0) return
        val cols = max(MIN_COLS, (width / cellWidth).toInt())
        val rows = max(MIN_ROWS, height / cellHeight)
        if (force || cols != viewCols || rows != viewRows) {
            viewCols = cols
            viewRows = rows
            scrollOffset = 0
            clearSelection() // Reflow invalidates selection coordinates.
            s.resize(rows, cols, width, height)
        }
    }

    // ------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        val s = session
        if (s == null) {
            canvas.drawColor(DEFAULT_BG)
            return
        }
        val e = s.emulator
        synchronized(e) {
            val colors = e.colors
            val defaultBg = colors.current[if (e.reverseVideo) TextStyle.COLOR_DEFAULT_FG else TextStyle.COLOR_DEFAULT_BG]
            canvas.drawColor(defaultBg)

            // Keep a scrolled-back view anchored to the same lines while output arrives.
            val scrolled = e.scrolledOut
            if (scrollOffset > 0) scrollOffset += (scrolled - lastScrolledOut).toInt()
            lastScrolledOut = scrolled
            scrollOffset = scrollOffset.coerceIn(0, e.buffer.historySize)

            if (scaling) {
                canvas.save()
                canvas.scale(pinchScale, pinchScale, pinchFocusX, pinchFocusY)
            }
            for (screenRow in 0 until e.rows) {
                val y = screenRow - scrollOffset
                if (y < -e.buffer.historySize) continue
                drawRow(canvas, e, e.buffer.row(y), y + scrolled, screenRow * cellHeight, defaultBg)
            }
            drawCursor(canvas, e)
            if (scaling) canvas.restore()
            if (selecting) drawHandles(canvas, scrolled)
        }
    }

    private fun drawRow(canvas: Canvas, e: TerminalEmulator, row: TerminalRow, lineId: Long, top: Int, defaultBg: Int) {
        val cols = min(row.cols, e.cols)
        var x = 0
        while (x < cols) {
            val start = x
            val style = row.style[x]
            val selected = isSelected(lineId, x)
            x++
            while (x < cols && row.style[x] == style && isSelected(lineId, x) == selected) x++

            val attrs = TextStyle.attrs(style)
            val fg = foreground(e, style, selected)
            val bg = background(e, style, selected)
            val left = start * cellWidth
            if (bg != defaultBg) {
                fillPaint.color = bg
                canvas.drawRect(left, top.toFloat(), x * cellWidth, (top + cellHeight).toFloat(), fillPaint)
            }
            if (attrs and TextStyle.INVISIBLE != 0) continue
            textPaint.color = if (attrs and TextStyle.DIM != 0) dim(fg) else fg
            textPaint.isFakeBoldText = attrs and TextStyle.BOLD != 0
            textPaint.textSkewX = if (attrs and TextStyle.ITALIC != 0) -0.25f else 0f
            textPaint.isUnderlineText = attrs and TextStyle.UNDERLINE != 0
            textPaint.isStrikeThruText = attrs and TextStyle.STRIKETHROUGH != 0
            drawText(canvas, row, start, x, top + baseline)
        }
        textPaint.isUnderlineText = false
        textPaint.isStrikeThruText = false
    }

    /**
     * Draws cells [from, to). Runs of plain ASCII go out in one call (the monospace font
     * lines them up with the grid); anything else is drawn per cell and squeezed to fit.
     */
    private fun drawText(canvas: Canvas, row: TerminalRow, from: Int, to: Int, baselineY: Int) {
        var runStart = -1
        var runLength = 0
        fun flush() {
            if (runLength > 0) canvas.drawText(charBuffer, 0, runLength, runStart * cellWidth, baselineY.toFloat(), textPaint)
            runStart = -1
            runLength = 0
        }
        var x = from
        while (x < to) {
            val cp = row.text[x]
            when {
                cp == TerminalRow.WIDE_TAIL -> flush()
                cp in 0x21..0x7E && runLength < charBuffer.size -> {
                    if (runStart < 0) runStart = x
                    charBuffer[runLength++] = cp.toChar()
                }
                cp == TerminalRow.EMPTY || cp == ' '.code -> {
                    // Spaces keep an underline continuous; otherwise they break the run.
                    if (textPaint.isUnderlineText || textPaint.isStrikeThruText) {
                        if (runStart < 0) runStart = x
                        if (runLength < charBuffer.size) charBuffer[runLength++] = ' ' else flush()
                    } else {
                        flush()
                    }
                }
                else -> {
                    flush()
                    val wide = x + 1 < row.cols && row.text[x + 1] == TerminalRow.WIDE_TAIL
                    drawGlyph(canvas, cp, x, if (wide) 2 else 1, baselineY)
                }
            }
            x++
        }
        flush()
    }

    private fun drawGlyph(canvas: Canvas, cp: Int, col: Int, width: Int, baselineY: Int) {
        val text = String(Character.toChars(cp))
        val available = cellWidth * width
        val measured = textPaint.measureText(text)
        if (measured > available + 0.5f && measured > 0f) {
            val oldScale = textPaint.textScaleX
            textPaint.textScaleX = available / measured
            canvas.drawText(text, col * cellWidth, baselineY.toFloat(), textPaint)
            textPaint.textScaleX = oldScale
        } else {
            canvas.drawText(text, col * cellWidth + (available - measured) / 2f, baselineY.toFloat(), textPaint)
        }
    }

    private fun drawCursor(canvas: Canvas, e: TerminalEmulator) {
        if (!e.cursorVisible) return
        val screenRow = e.cursorY + scrollOffset
        if (screenRow >= e.rows) return
        val row = e.buffer.row(e.cursorY)
        val x = e.cursorX
        val wide = x + 1 < row.cols && row.text[x + 1] == TerminalRow.WIDE_TAIL
        val left = x * cellWidth
        val top = (screenRow * cellHeight).toFloat()
        val right = left + cellWidth * (if (wide) 2 else 1)
        val bottom = top + cellHeight
        val cursorColor = e.colors.current[TextStyle.COLOR_CURSOR]
        fillPaint.color = cursorColor
        val thickness = max(2f, cellHeight / 8f)
        when {
            !hasWindowFocus() -> {
                fillPaint.style = Paint.Style.STROKE
                fillPaint.strokeWidth = thickness / 2
                canvas.drawRect(left, top, right, bottom, fillPaint)
                fillPaint.style = Paint.Style.FILL
            }
            e.cursorShape == TerminalEmulator.CURSOR_UNDERLINE -> canvas.drawRect(left, bottom - thickness, right, bottom, fillPaint)
            e.cursorShape == TerminalEmulator.CURSOR_BAR -> canvas.drawRect(left, top, left + thickness, bottom, fillPaint)
            else -> {
                canvas.drawRect(left, top, right, bottom, fillPaint)
                val cp = row.text[x]
                if (cp > ' '.code) {
                    textPaint.color = background(e, row.style[x], false)
                    textPaint.isFakeBoldText = TextStyle.attrs(row.style[x]) and TextStyle.BOLD != 0
                    textPaint.textSkewX = 0f
                    drawGlyph(canvas, cp, x, if (wide) 2 else 1, screenRow * cellHeight + baseline)
                }
            }
        }
    }

    private fun foreground(e: TerminalEmulator, style: Long, selected: Boolean): Int {
        var fg = TextStyle.fg(style)
        val attrs = TextStyle.attrs(style)
        if (attrs and TextStyle.BOLD != 0 && fg < 8) fg += 8
        val inverse = (attrs and TextStyle.INVERSE != 0) xor e.reverseVideo xor selected
        return e.colors.resolve(if (inverse) TextStyle.bg(style) else fg)
    }

    private fun background(e: TerminalEmulator, style: Long, selected: Boolean): Int {
        var fg = TextStyle.fg(style)
        val attrs = TextStyle.attrs(style)
        if (attrs and TextStyle.BOLD != 0 && fg < 8) fg += 8
        val inverse = (attrs and TextStyle.INVERSE != 0) xor e.reverseVideo xor selected
        return e.colors.resolve(if (inverse) fg else TextStyle.bg(style))
    }

    private fun dim(color: Int): Int {
        val r = ((color shr 16) and 0xFF) * 2 / 3
        val g = ((color shr 8) and 0xFF) * 2 / 3
        val b = (color and 0xFF) * 2 / 3
        return (color and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
    }

    // ---------------------------------------------------------- selection

    private fun isSelected(lineId: Long, col: Int): Boolean {
        if (!selecting) return false
        val (sLine, sCol, eLine, eCol) = orderedSelection()
        if (lineId < sLine || lineId > eLine) return false
        if (lineId == sLine && col < sCol) return false
        if (lineId == eLine && col > eCol) return false
        return true
    }

    private data class Span(val startLine: Long, val startCol: Int, val endLine: Long, val endCol: Int)

    private fun orderedSelection(): Span =
        if (selStartLine < selEndLine || (selStartLine == selEndLine && selStartCol <= selEndCol)) {
            Span(selStartLine, selStartCol, selEndLine, selEndCol)
        } else {
            Span(selEndLine, selEndCol, selStartLine, selStartCol)
        }

    private fun cellAt(x: Float, y: Float): Pair<Long, Int> {
        val e = session!!.emulator
        val col = (x / cellWidth).toInt().coerceIn(0, e.cols - 1)
        val screenRow = (y / cellHeight).toInt().coerceIn(0, e.rows - 1)
        val bufferRow = (screenRow - scrollOffset).coerceAtLeast(-e.buffer.historySize)
        return Pair(bufferRow + e.scrolledOut, col)
    }

    /** The link under (x, y), if any. */
    private fun linkAt(x: Float, y: Float): TerminalEmulator.Link? {
        val e = session?.emulator ?: return null
        synchronized(e) {
            val (line, col) = cellAt(x, y)
            return e.linkAt((line - e.scrolledOut).toInt(), col)
        }
    }

    private fun startSelection(x: Float, y: Float) {
        val e = session?.emulator ?: return
        synchronized(e) {
            val (line, col) = cellAt(x, y)
            val link = e.linkAt((line - e.scrolledOut).toInt(), col)
            if (link != null) {
                // A long press on a link selects all of it, across rows, ready to copy.
                selStartLine = link.startY + e.scrolledOut
                selStartCol = link.startX
                selEndLine = link.endY + e.scrolledOut
                selEndCol = link.endX
                selecting = true
                draggingHandle = HANDLE_NONE
                draggingFromHandle = false
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                showActionMode()
                invalidate()
                return
            }
            val row = e.buffer.row((line - e.scrolledOut).toInt())
            fun isWordCell(c: Int) = row.text[c] != TerminalRow.EMPTY && row.text[c] != ' '.code
            var start = col
            var end = col
            if (isWordCell(col) || row.text[col] == TerminalRow.WIDE_TAIL) {
                while (start > 0 && (isWordCell(start - 1))) start--
                while (end < row.cols - 1 && isWordCell(end + 1)) end++
            }
            selStartLine = line
            selEndLine = line
            selStartCol = start
            selEndCol = end
        }
        selecting = true
        draggingHandle = HANDLE_END
        draggingFromHandle = false
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        showActionMode()
        invalidate()
    }

    fun clearSelection() {
        if (!selecting && actionMode == null) return
        selecting = false
        draggingHandle = HANDLE_NONE
        actionMode?.finish()
        actionMode = null
        invalidate()
    }

    private fun selectedText(): String {
        val e = session?.emulator ?: return ""
        synchronized(e) {
            val span = orderedSelection()
            val offset = e.scrolledOut
            return e.getText((span.startLine - offset).toInt(), span.startCol, (span.endLine - offset).toInt(), span.endCol)
        }
    }

    private fun handleCenter(start: Boolean, scrolled: Long): Pair<Float, Float> {
        val span = orderedSelection()
        val line = if (start) span.startLine else span.endLine
        val col = if (start) span.startCol else span.endCol + 1
        val screenRow = (line - scrolled).toInt() + scrollOffset
        return Pair(col * cellWidth, ((screenRow + 1) * cellHeight).toFloat() + handleRadius)
    }

    private fun drawHandles(canvas: Canvas, scrolled: Long) {
        fillPaint.color = HANDLE_COLOR
        for (start in listOf(true, false)) {
            val (cx, cy) = handleCenter(start, scrolled)
            canvas.drawCircle(cx, cy, handleRadius, fillPaint)
            canvas.drawRect(cx - 1.5f, cy - handleRadius - cellHeight / 3f, cx + 1.5f, cy, fillPaint)
        }
    }

    private fun hitHandle(x: Float, y: Float): Int {
        if (!selecting) return HANDLE_NONE
        val e = session?.emulator ?: return HANDLE_NONE
        val scrolled = synchronized(e) { e.scrolledOut }
        val slop = handleRadius * 2.5f
        for ((handle, start) in listOf(HANDLE_START to true, HANDLE_END to false)) {
            val (cx, cy) = handleCenter(start, scrolled)
            if (abs(x - cx) < slop && abs(y - cy) < slop) return handle
        }
        return HANDLE_NONE
    }

    /**
     * Moves the dragged selection endpoint. Handles sit below and to the side of the text
     * they mark, so a handle drag aims at the cell above-left of the finger.
     */
    private fun dragSelection(x: Float, y: Float) {
        val e = session?.emulator ?: return
        val fromHandle = draggingFromHandle
        synchronized(e) {
            val (line, col) = if (fromHandle) cellAt(x, y - cellHeight / 2f - handleRadius) else cellAt(x, y)
            val span = orderedSelection()
            if (draggingHandle == HANDLE_START) {
                selStartLine = line
                selStartCol = col
                selEndLine = span.endLine
                selEndCol = span.endCol
            } else {
                val endCol = if (fromHandle) col - 1 else col
                selStartLine = span.startLine
                selStartCol = span.startCol
                selEndLine = line
                selEndCol = max(endCol, if (line == span.startLine) span.startCol else 0)
            }
        }
        invalidate()
    }

    private fun showActionMode() {
        actionMode?.let {
            it.invalidate()
            return
        }
        actionMode = startActionMode(object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(Menu.NONE, R.id.terminal_action_copy, 0, R.string.terminal_menu_copy)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                menu.add(Menu.NONE, R.id.terminal_action_paste, 1, R.string.terminal_menu_paste)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                menu.add(Menu.NONE, R.id.terminal_action_copy_all, 2, R.string.terminal_menu_copy_all)
                menu.add(Menu.NONE, R.id.terminal_action_keyboard, 3, R.string.terminal_menu_keyboard)
                menu.add(Menu.NONE, R.id.terminal_action_font_larger, 4, R.string.terminal_menu_font_larger)
                menu.add(Menu.NONE, R.id.terminal_action_font_smaller, 5, R.string.terminal_menu_font_smaller)
                menu.add(Menu.NONE, R.id.terminal_action_close, 6, R.string.terminal_menu_close)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                when (item.itemId) {
                    R.id.terminal_action_copy -> copyToClipboard(selectedText())
                    R.id.terminal_action_paste -> pasteFromClipboard()
                    R.id.terminal_action_copy_all -> listener?.onCopyAll()
                    R.id.terminal_action_keyboard -> listener?.onToggleKeyboard()
                    R.id.terminal_action_font_larger -> changeFontSize(FONT_STEP)
                    R.id.terminal_action_font_smaller -> changeFontSize(1f / FONT_STEP)
                    R.id.terminal_action_close -> listener?.onCloseSession()
                    else -> return false
                }
                clearSelection()
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                actionMode = null
                if (selecting) {
                    selecting = false
                    invalidate()
                }
            }

            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                val e = session?.emulator
                if (e == null || !selecting) {
                    outRect.set(0, 0, width, cellHeight)
                    return
                }
                val scrolled = synchronized(e) { e.scrolledOut }
                val span = orderedSelection()
                val top = ((span.startLine - scrolled).toInt() + scrollOffset) * cellHeight
                val bottom = ((span.endLine - scrolled).toInt() + scrollOffset + 1) * cellHeight
                val left = if (span.startLine == span.endLine) (span.startCol * cellWidth).toInt() else 0
                val right = if (span.startLine == span.endLine) ((span.endCol + 1) * cellWidth).toInt() else width
                outRect.set(left, top.coerceIn(0, height), right, bottom.coerceIn(0, height))
            }
        }, ActionMode.TYPE_FLOATING)
    }

    fun changeFontSize(factor: Float) {
        setFontSize(fontSizePx * factor)
        listener?.onFontSizeChanged(fontSizePx)
    }

    fun copyToClipboard(text: String) {
        if (text.isEmpty()) return
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.terminal_clip_label), text))
    }

    fun pasteFromClipboard() {
        val s = session ?: return
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        if (text.isNullOrEmpty()) return
        scrollToBottom()
        synchronized(s.emulator) { s.emulator.paste(text) }
    }

    // --------------------------------------------------------------- touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (session == null) return false
        if (event.isFromSource(InputDevice.SOURCE_MOUSE) && handleMouse(event)) return true

        if (draggingHandle != HANDLE_NONE) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> dragSelection(event.x, event.y)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    draggingHandle = HANDLE_NONE
                    actionMode?.invalidateContentRect()
                }
            }
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN && selecting) {
            val handle = hitHandle(event.x, event.y)
            if (handle != HANDLE_NONE) {
                draggingHandle = handle
                draggingFromHandle = true
                return true
            }
        }

        scaleDetector.onTouchEvent(event)
        if (scaling || scaleDetector.isInProgress) return true
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) scrollRemainder = 0f
        return true
    }

    private fun handleMouse(event: MotionEvent): Boolean {
        val e = session?.emulator ?: return false
        if (e.mouseTracking == TerminalEmulator.MOUSE_NONE) return false
        val col = (event.x / cellWidth).toInt()
        val row = (event.y / cellHeight).toInt()
        val button = when {
            event.isButtonPressed(MotionEvent.BUTTON_SECONDARY) -> 2
            event.isButtonPressed(MotionEvent.BUTTON_TERTIARY) -> 1
            else -> 0
        }
        synchronized(e) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    mouseButtonDown = true
                    e.sendMouseEvent(button, col, row, pressed = true)
                }
                MotionEvent.ACTION_MOVE -> if (mouseButtonDown) e.sendMouseEvent(button, col, row, pressed = true, motion = true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mouseButtonDown = false
                    e.sendMouseEvent(button, col, row, pressed = false)
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val e = session?.emulator ?: return super.onGenericMotionEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE && e.mouseTracking == TerminalEmulator.MOUSE_ANY_EVENT) {
            synchronized(e) { e.sendMouseEvent(0, (event.x / cellWidth).toInt(), (event.y / cellHeight).toInt(), pressed = true, motion = true) }
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (v != 0f) {
                scrollBy(if (v > 0) -WHEEL_ROWS else WHEEL_ROWS, event.x, event.y)
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    /** Scrolls [rows] (positive = towards newer output), as wheel events, arrow keys or history. */
    private fun scrollBy(rows: Int, x: Float, y: Float) {
        val s = session ?: return
        val e = s.emulator
        synchronized(e) {
            when {
                e.mouseTracking != TerminalEmulator.MOUSE_NONE -> {
                    val button = if (rows < 0) TerminalEmulator.MOUSE_WHEEL_UP else TerminalEmulator.MOUSE_WHEEL_DOWN
                    repeat(abs(rows)) { e.sendMouseEvent(button, (x / cellWidth).toInt(), (y / cellHeight).toInt(), pressed = true) }
                }
                e.isAltScreen -> {
                    val key = if (rows < 0) KeyEncoder.Key.UP else KeyEncoder.Key.DOWN
                    val seq = KeyEncoder.encode(key, 0, e.applicationCursorKeys)
                    repeat(abs(rows)) { s.write(seq.toByteArray()) }
                }
                else -> {
                    scrollOffset = (scrollOffset - rows).coerceIn(0, e.buffer.historySize)
                    invalidate()
                }
            }
        }
    }

    fun scrollToBottom() {
        if (scrollOffset != 0) {
            scrollOffset = 0
            invalidate()
        }
    }

    override fun computeScroll() {
        if (!flingActive) return
        if (scroller.computeScrollOffset()) {
            val e = session?.emulator ?: return
            val target = (scroller.currY / cellHeight)
            synchronized(e) { scrollOffset = target.coerceIn(0, e.buffer.historySize) }
            if (scroller.isFinished) flingActive = false
            postInvalidateOnAnimation()
        } else {
            flingActive = false
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            if (flingActive) {
                scroller.forceFinished(true)
                flingActive = false
            }
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            performClick()
            if (selecting) {
                clearSelection()
                return true
            }
            val s = session ?: return true
            linkAt(e.x, e.y)?.let { link ->
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                listener?.onOpenUrl(link.url)
                return true
            }
            val emu = s.emulator
            if (emu.mouseTracking != TerminalEmulator.MOUSE_NONE) {
                val col = (e.x / cellWidth).toInt()
                val row = (e.y / cellHeight).toInt()
                synchronized(emu) {
                    emu.sendMouseEvent(0, col, row, pressed = true)
                    emu.sendMouseEvent(0, col, row, pressed = false)
                }
            } else {
                listener?.onRequestKeyboard()
            }
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            scrollRemainder += distanceY
            val rows = (scrollRemainder / cellHeight).toInt()
            if (rows != 0) {
                scrollRemainder -= rows * cellHeight
                scrollBy(rows, e2.x, e2.y)
            }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val emu = session?.emulator ?: return false
            val (history, useHistory) = synchronized(emu) {
                Pair(emu.buffer.historySize, emu.mouseTracking == TerminalEmulator.MOUSE_NONE && !emu.isAltScreen)
            }
            if (!useHistory || history == 0) return false
            scroller.fling(0, scrollOffset * cellHeight, 0, velocityY.roundToInt(), 0, 0, 0, history * cellHeight)
            flingActive = true
            postInvalidateOnAnimation()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (scaling || scaleDetector.isInProgress) return
            startSelection(e.x, e.y)
        }
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaling = true
            pinchScale = 1f
            pinchFocusX = detector.focusX
            pinchFocusY = detector.focusY
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            pinchScale = (pinchScale * detector.scaleFactor).coerceIn(0.3f, 4f)
            invalidate()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            scaling = false
            val factor = pinchScale
            pinchScale = 1f
            if (abs(factor - 1f) > 0.05f) changeFontSize(factor) else invalidate()
        }
    }

    // ------------------------------------------------------------ keyboard

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // TYPE_NULL keeps IMEs from predicting, auto-correcting or composing in the terminal.
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE
        return TerminalInputConnection(this)
    }

    fun setCtrlLatched(on: Boolean) {
        ctrlLatched = on
        onModifiersChanged?.invoke()
    }

    fun setAltLatched(on: Boolean) {
        altLatched = on
        onModifiersChanged?.invoke()
    }

    private fun consumeLatches() {
        if (ctrlLatched || altLatched) {
            ctrlLatched = false
            altLatched = false
            onModifiersChanged?.invoke()
        }
    }

    /** Text from the IME or the extra keys; latched modifiers apply to the first character. */
    fun sendText(text: CharSequence) {
        val s = session ?: return
        if (text.isEmpty()) return
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            var cp = Character.codePointAt(text, i)
            i += Character.charCount(cp)
            if (cp == '\n'.code) cp = '\r'.code
            if (ctrlLatched || altLatched) {
                out.append(KeyEncoder.encodeChar(cp, ctrlLatched, altLatched))
                consumeLatches()
            } else {
                out.appendCodePoint(cp)
            }
        }
        scrollToBottom()
        s.writeInput(out.toString())
    }

    /** Sends a special key, combining [extraMods] with any latched modifiers. */
    fun sendKey(key: KeyEncoder.Key, extraMods: Int = 0) {
        val s = session ?: return
        var mods = extraMods
        if (ctrlLatched) mods = mods or KeyEncoder.MOD_CTRL
        if (altLatched) mods = mods or KeyEncoder.MOD_ALT
        consumeLatches()
        val e = s.emulator
        val seq = synchronized(e) { KeyEncoder.encode(key, mods, e.applicationCursorKeys, e.newLineMode) }
        scrollToBottom()
        s.writeInput(seq)
    }

    @Suppress("DEPRECATION") // ACTION_MULTIPLE/characters are still how some IMEs deliver text.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val s = session ?: return super.onKeyDown(keyCode, event)
        when (keyCode) {
            // Back (clearing a selection included) goes through the activity's OnBackPressedDispatcher.
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_POWER,
            -> return super.onKeyDown(keyCode, event)
        }
        if (event.isCtrlPressed && event.isShiftPressed) {
            when (keyCode) {
                KeyEvent.KEYCODE_V -> {
                    pasteFromClipboard()
                    return true
                }
                KeyEvent.KEYCODE_C -> {
                    if (selecting) copyToClipboard(selectedText())
                    return true
                }
            }
        }

        var mods = 0
        if (event.isShiftPressed) mods = mods or KeyEncoder.MOD_SHIFT
        if (event.isAltPressed) mods = mods or KeyEncoder.MOD_ALT
        if (event.isCtrlPressed) mods = mods or KeyEncoder.MOD_CTRL

        val special = SPECIAL_KEYS[keyCode]
        if (special != null) {
            sendKey(special, mods)
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_UNKNOWN && event.action == KeyEvent.ACTION_MULTIPLE) {
            event.characters?.let { sendText(it) }
            return true
        }

        val metaWithoutCtrlAlt = event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv()
        val unicode = event.getUnicodeChar(metaWithoutCtrlAlt)
        if (unicode == 0) return super.onKeyDown(keyCode, event)
        if (unicode and KeyCharacterMap.COMBINING_ACCENT != 0) return true // Dead keys are not composed.

        val ctrl = event.isCtrlPressed || ctrlLatched
        val alt = event.isAltPressed || altLatched
        consumeLatches()
        scrollToBottom()
        s.writeInput(KeyEncoder.encodeChar(if (unicode == '\n'.code) '\r'.code else unicode, ctrl, alt))
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (session != null && (SPECIAL_KEYS[keyCode] != null || event.getUnicodeChar(0) != 0)) return true
        return super.onKeyUp(keyCode, event)
    }

    @Suppress("DEPRECATION")
    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            event.characters?.let { sendText(it) }
            return true
        }
        return super.onKeyMultiple(keyCode, repeatCount, event)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        session?.emulator?.let { synchronized(it) { it.focusChanged(hasWindowFocus) } }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        actionMode?.finish()
        super.onDetachedFromWindow()
    }

    companion object {
        const val DEFAULT_FONT_SP = 14f
        private const val MIN_FONT_SP = 6f
        private const val MAX_FONT_SP = 40f
        private const val FONT_STEP = 1.15f
        private const val MIN_COLS = 4
        private const val MIN_ROWS = 2
        private const val WHEEL_ROWS = 3
        private const val DEFAULT_BG = 0xFF000000.toInt()
        private const val HANDLE_COLOR = 0xFF4FC3F7.toInt()

        private const val HANDLE_NONE = 0
        private const val HANDLE_START = 1
        private const val HANDLE_END = 2

        private val SPECIAL_KEYS: Map<Int, KeyEncoder.Key> = mapOf(
            KeyEvent.KEYCODE_DPAD_UP to KeyEncoder.Key.UP,
            KeyEvent.KEYCODE_DPAD_DOWN to KeyEncoder.Key.DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT to KeyEncoder.Key.LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT to KeyEncoder.Key.RIGHT,
            KeyEvent.KEYCODE_MOVE_HOME to KeyEncoder.Key.HOME,
            KeyEvent.KEYCODE_MOVE_END to KeyEncoder.Key.END,
            KeyEvent.KEYCODE_PAGE_UP to KeyEncoder.Key.PAGE_UP,
            KeyEvent.KEYCODE_PAGE_DOWN to KeyEncoder.Key.PAGE_DOWN,
            KeyEvent.KEYCODE_INSERT to KeyEncoder.Key.INSERT,
            KeyEvent.KEYCODE_FORWARD_DEL to KeyEncoder.Key.DELETE,
            KeyEvent.KEYCODE_ENTER to KeyEncoder.Key.ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER to KeyEncoder.Key.ENTER,
            KeyEvent.KEYCODE_TAB to KeyEncoder.Key.TAB,
            KeyEvent.KEYCODE_DEL to KeyEncoder.Key.BACKSPACE,
            KeyEvent.KEYCODE_ESCAPE to KeyEncoder.Key.ESCAPE,
            KeyEvent.KEYCODE_F1 to KeyEncoder.Key.F1,
            KeyEvent.KEYCODE_F2 to KeyEncoder.Key.F2,
            KeyEvent.KEYCODE_F3 to KeyEncoder.Key.F3,
            KeyEvent.KEYCODE_F4 to KeyEncoder.Key.F4,
            KeyEvent.KEYCODE_F5 to KeyEncoder.Key.F5,
            KeyEvent.KEYCODE_F6 to KeyEncoder.Key.F6,
            KeyEvent.KEYCODE_F7 to KeyEncoder.Key.F7,
            KeyEvent.KEYCODE_F8 to KeyEncoder.Key.F8,
            KeyEvent.KEYCODE_F9 to KeyEncoder.Key.F9,
            KeyEvent.KEYCODE_F10 to KeyEncoder.Key.F10,
            KeyEvent.KEYCODE_F11 to KeyEncoder.Key.F11,
            KeyEvent.KEYCODE_F12 to KeyEncoder.Key.F12,
        )
    }
}
