package tech.anl.vnc.app

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Turns touch gestures into pointer events according to the [InputMode].
 *
 * Common to all modes: pinch zooms, two fingers scroll (or pan when zoomed
 * in Direct/Hold Pan), a quick two-finger tap right-clicks and a three-finger
 * tap calls [ViewerInput.onThreeFingerTap].
 *
 * - Direct, Hold Pan: the pointer follows the finger; tap clicks; long-press
 *   right-clicks; long-press then drag drags with the left button.
 * - Direct, Swipe Pan: tap / long-press as above; a swipe pans the zoomed
 *   desktop (scrolls when not zoomed).
 * - Touchpad: relative pointer; tap clicks; tap then drag (or long-press then
 *   drag) drags; long-press or two-finger tap right-clicks.
 * - Single Handed: like Touchpad with a higher gain so a thumb moving over a
 *   small area reaches every corner of the desktop.
 *
 * In direct modes, one-finger gestures that start on the letterbox margin are
 * ignored.
 */
internal class TouchInputHandler(private val view: VncView) {
    var mode: InputMode = InputMode.DIRECT_HOLD_PAN

    private val density = view.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
    private val scrollStep = 28 * density

    private enum class Multi { NONE, UNDECIDED, PINCH, PAN, SCROLL }

    private var ignored = false
    private var moved = false
    private var longPressed = false
    private var dragging = false
    private var tapDrag = false
    private var maxPointers = 0
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f

    private var multi = Multi.NONE
    private var multiStart = 0L
    private var span0 = 0f
    private var lastSpan = 0f
    private var cx0 = 0f
    private var cy0 = 0f
    private var lastCx = 0f
    private var lastCy = 0f
    private var scrollAccX = 0f
    private var scrollAccY = 0f

    private var lastTapUp = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private val longPressRunnable = Runnable { onLongPress() }

    fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(e)
            MotionEvent.ACTION_POINTER_DOWN -> onPointerDown(e)
            MotionEvent.ACTION_MOVE -> {
                if (maxPointers >= 2) {
                    if (maxPointers == 2 && e.pointerCount >= 2) onMultiMove(e)
                } else {
                    onSingleMove(e)
                }
            }
            MotionEvent.ACTION_UP -> onUp(e)
            MotionEvent.ACTION_CANCEL -> {
                cancelLongPress()
                if (dragging) view.setButtons(0)
                dragging = false
            }
        }
        return true
    }

    private fun fbX(x: Float) = view.toFbX(x)
    private fun fbY(y: Float) = view.toFbY(y)

    private fun onDown(e: MotionEvent) {
        cancelLongPress()
        moved = false
        longPressed = false
        dragging = false
        multi = Multi.NONE
        maxPointers = 1
        downTime = e.eventTime
        downX = e.x
        downY = e.y
        lastX = downX
        lastY = downY
        scrollAccX = 0f
        scrollAccY = 0f
        ignored = mode.isDirect && !view.isOnDesktop(downX, downY)
        if (ignored) return
        tapDrag = !mode.isDirect && e.eventTime - lastTapUp < doubleTapTimeout &&
            hypot(downX - lastTapX, downY - lastTapY) < 64 * density
        view.postDelayed(longPressRunnable, longPressTimeout)
        if (mode == InputMode.DIRECT_HOLD_PAN) view.movePointerTo(fbX(downX), fbY(downY))
    }

    private fun onPointerDown(e: MotionEvent) {
        cancelLongPress()
        maxPointers = max(maxPointers, e.pointerCount)
        if (dragging) {
            view.setButtons(0)
            dragging = false
        }
        if (e.pointerCount == 2 && multi == Multi.NONE) {
            multi = Multi.UNDECIDED
            multiStart = e.eventTime
            span0 = span(e)
            lastSpan = span0
            cx0 = (e.getX(0) + e.getX(1)) / 2
            cy0 = (e.getY(0) + e.getY(1)) / 2
            lastCx = cx0
            lastCy = cy0
        }
    }

    private fun span(e: MotionEvent) = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))

    private fun onMultiMove(e: MotionEvent) {
        val s = span(e)
        val cx = (e.getX(0) + e.getX(1)) / 2
        val cy = (e.getY(0) + e.getY(1)) / 2
        if (multi == Multi.UNDECIDED) {
            if (abs(s - span0) > slop * 2) {
                multi = Multi.PINCH
            } else if (hypot(cx - cx0, cy - cy0) > slop) {
                multi = if (mode == InputMode.DIRECT_HOLD_PAN && view.zoom > 1f) Multi.PAN else Multi.SCROLL
                if (multi == Multi.SCROLL && mode.isDirect && view.isOnDesktop(cx0, cy0)) {
                    view.movePointerTo(fbX(cx0), fbY(cy0))
                }
            }
            if (multi != Multi.UNDECIDED) {
                lastSpan = s
                lastCx = cx
                lastCy = cy
            }
        }
        when (multi) {
            Multi.PINCH -> {
                if (lastSpan > 0f && s > 0f) view.zoomBy(s / lastSpan, cx, cy)
                view.panBy(cx - lastCx, cy - lastCy)
            }
            Multi.PAN -> view.panBy(cx - lastCx, cy - lastCy)
            Multi.SCROLL -> scroll(cx - lastCx, cy - lastCy)
            else -> {}
        }
        lastSpan = s
        lastCx = cx
        lastCy = cy
    }

    private fun onSingleMove(e: MotionEvent) {
        if (ignored) return
        val x = e.x
        val y = e.y
        if (!moved && hypot(x - downX, y - downY) > slop) {
            moved = true
            cancelLongPress()
            if (longPressed || tapDrag) {
                dragging = true
                if (mode.isDirect) view.movePointerTo(fbX(downX), fbY(downY), VncView.BUTTON_LEFT)
                else view.setButtons(VncView.BUTTON_LEFT)
            } else if (mode == InputMode.DIRECT_SWIPE_PAN && view.zoom <= 1f) {
                view.movePointerTo(fbX(downX), fbY(downY))
            }
        }
        val dx = x - lastX
        val dy = y - lastY
        lastX = x
        lastY = y
        if (!moved) return
        when (mode) {
            InputMode.DIRECT_HOLD_PAN -> view.movePointerTo(fbX(x), fbY(y))
            InputMode.DIRECT_SWIPE_PAN -> when {
                dragging -> view.movePointerTo(fbX(x), fbY(y))
                view.zoom > 1f -> view.panBy(dx, dy)
                else -> scroll(dx, dy)
            }
            InputMode.TOUCHPAD, InputMode.SINGLE_HANDED -> relativeMove(dx, dy)
        }
    }

    private fun relativeMove(dx: Float, dy: Float) {
        val s = view.scale
        if (s <= 0f) return
        var gain = if (mode == InputMode.SINGLE_HANDED) singleHandedGain() else 1f
        val speed = hypot(dx, dy) / density
        gain *= 1f + min(1.5f, speed / 12f)
        view.movePointerTo(view.pointerX + dx * gain / s, view.pointerY + dy * gain / s)
        if (view.zoom > 1f) view.ensurePointerVisible()
    }

    /** Gain so ~45% of the shorter screen side covers the whole displayed desktop. */
    private fun singleHandedGain(): Float {
        val shown = max(view.fbWidth, view.fbHeight) * view.scale
        val reach = 0.45f * min(view.width, view.height)
        return if (reach <= 0f) 1.5f else max(1.5f, shown / reach)
    }

    private fun scroll(dx: Float, dy: Float) {
        scrollAccX += dx
        scrollAccY += dy
        // Natural scrolling: dragging the content down reveals what is above.
        while (scrollAccY >= scrollStep) {
            view.click(VncView.WHEEL_UP)
            scrollAccY -= scrollStep
        }
        while (scrollAccY <= -scrollStep) {
            view.click(VncView.WHEEL_DOWN)
            scrollAccY += scrollStep
        }
        while (scrollAccX >= scrollStep) {
            view.click(VncView.WHEEL_LEFT)
            scrollAccX -= scrollStep
        }
        while (scrollAccX <= -scrollStep) {
            view.click(VncView.WHEEL_RIGHT)
            scrollAccX += scrollStep
        }
    }

    private fun onLongPress() {
        if (ignored || moved || maxPointers > 1) return
        longPressed = true
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (mode.isDirect) view.movePointerTo(fbX(downX), fbY(downY))
    }

    private fun cancelLongPress() {
        view.removeCallbacks(longPressRunnable)
    }

    private fun onUp(e: MotionEvent) {
        cancelLongPress()
        val now = e.eventTime
        when {
            maxPointers == 1 && !ignored -> when {
                dragging -> {
                    if (mode.isDirect) view.movePointerTo(fbX(e.x), fbY(e.y), 0) else view.setButtons(0)
                    dragging = false
                }
                longPressed && !moved -> {
                    if (mode.isDirect) view.movePointerTo(fbX(downX), fbY(downY))
                    view.click(VncView.BUTTON_RIGHT)
                }
                !moved -> {
                    if (mode.isDirect) view.movePointerTo(fbX(e.x), fbY(e.y))
                    view.click(VncView.BUTTON_LEFT)
                    if (tapDrag) {
                        lastTapUp = 0L
                    } else {
                        lastTapUp = now
                        lastTapX = e.x
                        lastTapY = e.y
                    }
                }
            }
            maxPointers == 2 && multi == Multi.UNDECIDED && now - multiStart < MULTI_TAP_MS -> {
                if (mode.isDirect && view.isOnDesktop(downX, downY)) view.movePointerTo(fbX(downX), fbY(downY))
                view.click(VncView.BUTTON_RIGHT)
            }
            maxPointers == 3 && now - downTime < MULTI_TAP_MS * 2 -> view.input?.onThreeFingerTap()
        }
        maxPointers = 0
        multi = Multi.NONE
    }

    companion object {
        private const val MULTI_TAP_MS = 300L
    }
}
