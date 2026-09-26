package tech.anl.vnc.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import tech.anl.vnc.rfb.Framebuffer
import tech.anl.vnc.rfb.Keysyms
import tech.anl.vnc.rfb.RemoteCursor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What the view needs from its host: sending input to the server. */
internal interface ViewerInput {
    fun sendPointer(x: Int, y: Int, buttons: Int)

    /** Types text from the soft keyboard (applies latched modifiers). */
    fun typeText(text: String)

    /** Presses and releases one keysym (applies latched modifiers). */
    fun tapKeysym(keysym: Int)

    /** A hardware / IME key event; return true if consumed. */
    fun onKey(event: KeyEvent): Boolean
    fun onThreeFingerTap()
    fun onViewSizeChanged(width: Int, height: Int)
}

/**
 * Draws the remote framebuffer scaled to fit (letterboxed), with optional zoom
 * and pan, plus the remote cursor at the local pointer position.
 */
@SuppressLint("ViewConstructor")
internal class VncView(context: Context) : View(context) {
    var input: ViewerInput? = null
    val touch = TouchInputHandler(this)

    private var framebuffer: Framebuffer? = null
    private var bitmap: Bitmap? = null
    private val dirty = Rect()
    private var dirtyAll = true
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val plainPaint = Paint()

    private var cursor: RemoteCursor? = null
    private var cursorBitmap: Bitmap? = null

    /** Pointer position in framebuffer pixels. */
    var pointerX = 0f
        private set
    var pointerY = 0f
        private set
    var buttons = 0
        private set

    private var fitScale = 1f
    var zoom = 1f
        private set
    private var offX = 0f
    private var offY = 0f

    /** Height at the bottom covered by the soft keyboard / extra keys. */
    var obscuredBottom = 0
        set(value) {
            if (field == value) return
            field = value
            clampPan()
            if (value > 0) ensurePointerVisible()
            invalidate()
        }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Framebuffer.OPAQUE_BLACK)
    }

    val fbWidth: Int get() = framebuffer?.width ?: 0
    val fbHeight: Int get() = framebuffer?.height ?: 0
    val scale: Float get() = fitScale * zoom

    fun attach(fb: Framebuffer) {
        framebuffer = fb
        onFramebufferResized()
    }

    /** Called on the UI thread after the remote desktop changed size. */
    fun onFramebufferResized() {
        synchronized(dirty) { dirtyAll = true }
        pointerX = pointerX.coerceIn(0f, max(0, fbWidth - 1).toFloat())
        pointerY = pointerY.coerceIn(0f, max(0, fbHeight - 1).toFloat())
        computeFit()
        invalidate()
    }

    /** Called from the RFB reader thread for every updated rectangle. */
    fun markDirty(x: Int, y: Int, w: Int, h: Int) {
        synchronized(dirty) {
            if (dirty.isEmpty) dirty.set(x, y, x + w, y + h) else dirty.union(x, y, x + w, y + h)
        }
        postInvalidateOnAnimation()
    }

    fun setRemoteCursor(c: RemoteCursor?) {
        cursor = c
        cursorBitmap = c?.let { Bitmap.createBitmap(it.pixels, it.width, it.height, Bitmap.Config.ARGB_8888) }
        invalidate()
    }

    fun centerPointer() {
        pointerX = fbWidth / 2f
        pointerY = fbHeight / 2f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeFit()
        input?.onViewSizeChanged(w, h)
    }

    private fun computeFit() {
        val fw = fbWidth
        val fh = fbHeight
        if (fw <= 0 || fh <= 0 || width <= 0 || height <= 0) return
        fitScale = min(width.toFloat() / fw, height.toFloat() / fh)
        clampPan()
    }

    // ---- Coordinates ----------------------------------------------------

    fun toFbX(sx: Float): Float = (sx - offX) / scale
    fun toFbY(sy: Float): Float = (sy - offY) / scale
    fun toScreenX(fx: Float): Float = fx * scale + offX
    fun toScreenY(fy: Float): Float = fy * scale + offY

    /** True if the screen point lies on the desktop (not the letterbox margin). */
    fun isOnDesktop(sx: Float, sy: Float): Boolean {
        val fx = toFbX(sx)
        val fy = toFbY(sy)
        return fx >= 0 && fy >= 0 && fx < fbWidth && fy < fbHeight
    }

    private fun clampPan() {
        val s = scale
        val cw = fbWidth * s
        val ch = fbHeight * s
        val vw = width.toFloat()
        val vh = (height - obscuredBottom).toFloat().coerceAtLeast(1f)
        offX = if (cw <= vw) (vw - cw) / 2 else offX.coerceIn(vw - cw, 0f)
        offY = if (ch <= vh) (vh - ch) / 2 else offY.coerceIn(vh - ch, 0f)
        if (s == 1f) {
            offX = offX.roundToInt().toFloat()
            offY = offY.roundToInt().toFloat()
        }
    }

    fun panBy(dx: Float, dy: Float) {
        offX += dx
        offY += dy
        clampPan()
        invalidate()
    }

    fun zoomBy(factor: Float, focusX: Float, focusY: Float) {
        val fx = toFbX(focusX)
        val fy = toFbY(focusY)
        zoom = (zoom * factor).coerceIn(1f, MAX_ZOOM)
        offX = focusX - fx * scale
        offY = focusY - fy * scale
        clampPan()
        invalidate()
    }

    fun resetZoom() {
        zoom = 1f
        clampPan()
        invalidate()
    }

    /** Pans so the pointer stays on screen (zoomed in, or above the keyboard). */
    fun ensurePointerVisible() {
        val margin = 24 * resources.displayMetrics.density
        val sx = toScreenX(pointerX)
        val sy = toScreenY(pointerY)
        val vh = height - obscuredBottom
        var dx = 0f
        var dy = 0f
        if (sx < margin) dx = margin - sx else if (sx > width - margin) dx = width - margin - sx
        if (sy < margin) dy = margin - sy else if (sy > vh - margin) dy = vh - margin - sy
        if (dx != 0f || dy != 0f) panBy(dx, dy)
    }

    // ---- Pointer --------------------------------------------------------

    fun movePointerTo(fx: Float, fy: Float, newButtons: Int = buttons) {
        pointerX = fx.coerceIn(0f, max(0, fbWidth - 1).toFloat())
        pointerY = fy.coerceIn(0f, max(0, fbHeight - 1).toFloat())
        buttons = newButtons
        input?.sendPointer(pointerX.toInt(), pointerY.toInt(), buttons)
        invalidate()
    }

    fun setButtons(newButtons: Int) {
        if (newButtons == buttons) return
        buttons = newButtons
        input?.sendPointer(pointerX.toInt(), pointerY.toInt(), buttons)
    }

    fun click(mask: Int) {
        setButtons(buttons or mask)
        setButtons(buttons and mask.inv())
    }

    // ---- Drawing --------------------------------------------------------

    /** Copies the dirty part of the framebuffer into the (mutable) bitmap. Runs under the framebuffer lock. */
    private fun syncBitmap(px: IntArray, w: Int, h: Int): Bitmap? {
        if (w <= 0 || h <= 0) return null
        val r = Rect()
        var all: Boolean
        synchronized(dirty) {
            all = dirtyAll
            r.set(dirty)
            dirty.setEmpty()
            dirtyAll = false
        }
        var b = bitmap
        if (b == null || b.width != w || b.height != h) {
            b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap = b
            all = true
        }
        if (all) {
            b.setPixels(px, 0, w, 0, 0, w, h)
        } else if (r.intersect(0, 0, w, h)) {
            b.setPixels(px, r.top * w + r.left, w, r.left, r.top, r.width(), r.height())
        }
        return b
    }

    override fun onDraw(canvas: Canvas) {
        val fb = framebuffer ?: return
        val bmp = fb.withPixels { px, w, h -> syncBitmap(px, w, h) } ?: return
        val s = scale
        canvas.save()
        canvas.translate(offX, offY)
        canvas.scale(s, s)
        canvas.drawBitmap(bmp, 0f, 0f, if (s == 1f) plainPaint else paint)
        val c = cursor
        val cb = cursorBitmap
        if (c != null && cb != null) {
            canvas.drawBitmap(cb, pointerX.toInt() - c.hotX.toFloat(), pointerY.toInt() - c.hotY.toFloat(), paint)
        }
        canvas.restore()
    }

    // ---- Touch and mouse ------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (fbWidth <= 0) return true
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) return onMouseEvent(event)
        return touch.onTouchEvent(event)
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (fbWidth > 0 && event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE &&
            event.actionMasked == MotionEvent.ACTION_HOVER_MOVE
        ) {
            movePointerTo(toFbX(event.x), toFbY(event.y))
            return true
        }
        return super.onHoverEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (fbWidth <= 0) return super.onGenericMotionEvent(event)
        if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            when (event.actionMasked) {
                MotionEvent.ACTION_SCROLL -> {
                    movePointerTo(toFbX(event.x), toFbY(event.y))
                    val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                    val h = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                    if (v > 0) click(WHEEL_UP) else if (v < 0) click(WHEEL_DOWN)
                    if (h > 0) click(WHEEL_RIGHT) else if (h < 0) click(WHEEL_LEFT)
                    return true
                }
            }
        }
        return super.onGenericMotionEvent(event)
    }

    private fun onMouseEvent(event: MotionEvent): Boolean {
        var mask = 0
        val state = event.buttonState
        if (state and MotionEvent.BUTTON_PRIMARY != 0) mask = mask or BUTTON_LEFT
        if (state and MotionEvent.BUTTON_TERTIARY != 0) mask = mask or BUTTON_MIDDLE
        if (state and MotionEvent.BUTTON_SECONDARY != 0) mask = mask or BUTTON_RIGHT
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) mask = 0
        else if (mask == 0 && event.actionMasked == MotionEvent.ACTION_DOWN) mask = BUTTON_LEFT
        movePointerTo(toFbX(event.x), toFbY(event.y), mask)
        return true
    }

    // ---- Keyboard -------------------------------------------------------

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE
        return RemoteInputConnection()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        input?.onKey(event) == true || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        input?.onKey(event) == true || super.onKeyUp(keyCode, event)

    @Suppress("DEPRECATION")
    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        val chars = event.characters
        if (chars != null) {
            input?.typeText(chars)
            return true
        }
        return input?.onKey(event) == true || super.onKeyMultiple(keyCode, repeatCount, event)
    }

    /**
     * Turns IME edits into key events. The editor is always empty from the
     * IME's point of view; composing text is typed as it changes, with
     * backspaces for the characters it replaces.
     */
    private inner class RemoteInputConnection : BaseInputConnection(this@VncView, false) {
        private var composing = ""

        private fun replaceComposing(next: String) {
            var common = 0
            while (common < composing.length && common < next.length && composing[common] == next[common]) common++
            repeat(composing.length - common) { input?.tapKeysym(Keysyms.BACKSPACE) }
            if (common < next.length) input?.typeText(next.substring(common))
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            replaceComposing(text?.toString() ?: "")
            composing = ""
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val next = text?.toString() ?: ""
            replaceComposing(next)
            composing = next
            return true
        }

        override fun finishComposingText(): Boolean {
            composing = ""
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            val fromComposing = min(beforeLength, composing.length)
            composing = composing.dropLast(fromComposing)
            repeat(min(beforeLength, 256)) { input?.tapKeysym(Keysyms.BACKSPACE) }
            repeat(min(afterLength, 256)) { input?.tapKeysym(Keysyms.DELETE) }
            return true
        }

        @Suppress("DEPRECATION")
        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_MULTIPLE && event.characters != null) {
                input?.typeText(event.characters)
                return true
            }
            input?.onKey(event)
            return true
        }

        override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence = ""
        override fun getTextAfterCursor(length: Int, flags: Int): CharSequence = ""
    }

    companion object {
        const val BUTTON_LEFT = 1
        const val BUTTON_MIDDLE = 2
        const val BUTTON_RIGHT = 4
        const val WHEEL_UP = 8
        const val WHEEL_DOWN = 16
        const val WHEEL_LEFT = 32
        const val WHEEL_RIGHT = 64
        const val MAX_ZOOM = 8f
    }
}
