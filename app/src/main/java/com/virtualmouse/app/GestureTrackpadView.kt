package com.virtualmouse.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/**
 * High-precision full-screen trackpad view with sub-pixel remainder preservation
 * and adaptive velocity acceleration for zero-latency cursor tracking.
 */
class GestureTrackpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    @Volatile var sensitivity: Float = 1.3f
    @Volatile var scrollSensitivity: Float = 0.5f

    private var lastX: Float = 0f
    private var lastY: Float = 0f
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var downTime: Long = 0L

    // Sub-pixel fractional remainders so slow movements never get truncated
    private var remX: Float = 0f
    private var remY: Float = 0f
    private var scrollRemY: Float = 0f
    private var scrollRemX: Float = 0f

    private var lastTwoFingerY: Float = 0f
    private var lastTwoFingerX: Float = 0f

    private var isDragging = false
    private var isTwoFingerGesture = false
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private val handler = Handler(Looper.getMainLooper())

    private val longPressRunnable = Runnable {
        if (!isTwoFingerGesture) {
            isDragging = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            RootMouseController.mouseDown(1)
        }
    }

    private companion object {
        const val TAP_TIMEOUT_MS = 210L
        const val TAP_MAX_DISTANCE = 28f
        const val LONG_PRESS_TIMEOUT_MS = 420L
    }

    init {
        isClickable = true
        isFocusable = true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val pointerCount = event.pointerCount

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                remX = 0f
                remY = 0f
                downTime = SystemClock.uptimeMillis()
                isDragging = false
                isTwoFingerGesture = false

                handler.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT_MS)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                handler.removeCallbacks(longPressRunnable)
                if (pointerCount == 2) {
                    isTwoFingerGesture = true
                    scrollRemX = 0f
                    scrollRemY = 0f
                    lastTwoFingerX = (event.getX(0) + event.getX(1)) * 0.5f
                    lastTwoFingerY = (event.getY(0) + event.getY(1)) * 0.5f
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (isTwoFingerGesture && pointerCount >= 2) {
                    val currentMidX = (event.getX(0) + event.getX(1)) * 0.5f
                    val currentMidY = (event.getY(0) + event.getY(1)) * 0.5f
                    val deltaY = currentMidY - lastTwoFingerY
                    val deltaX = currentMidX - lastTwoFingerX
                    lastTwoFingerY = currentMidY
                    lastTwoFingerX = currentMidX

                    // Accumulate scroll with sub-unit remainder
                    scrollRemY += deltaY * scrollSensitivity * 0.35f
                    scrollRemX += deltaX * scrollSensitivity * 0.35f

                    val intScrollY = scrollRemY.toInt()
                    val intScrollX = scrollRemX.toInt()

                    if (intScrollY != 0) {
                        scrollRemY -= intScrollY
                        RootMouseController.scroll(intScrollY)
                    }
                    if (intScrollX != 0) {
                        scrollRemX -= intScrollX
                        RootMouseController.scrollHorizontal(intScrollX)
                    }
                } else if (!isTwoFingerGesture) {
                    val pointerIndex = event.findPointerIndex(activePointerId)
                    if (pointerIndex < 0) return true

                    val currX = event.getX(pointerIndex)
                    val currY = event.getY(pointerIndex)

                    val rawDx = currX - lastX
                    val rawDy = currY - lastY
                    lastX = currX
                    lastY = currY

                    // Cancel long press if finger moved beyond tap threshold
                    val distFromDown = hypot((currX - downX).toDouble(), (currY - downY).toDouble()).toFloat()
                    if (distFromDown > TAP_MAX_DISTANCE) {
                        handler.removeCallbacks(longPressRunnable)
                    }

                    // Natural laptop-style velocity acceleration curve
                    val velocity = hypot(rawDx.toDouble(), rawDy.toDouble()).toFloat()
                    val accel = (1.0f + (velocity * 0.022f)).coerceAtMost(2.3f)
                    val mult = sensitivity * accel

                    remX += rawDx * mult
                    remY += rawDy * mult

                    val intDx = remX.toInt()
                    val intDy = remY.toInt()

                    if (intDx != 0 || intDy != 0) {
                        remX -= intDx
                        remY -= intDy
                        RootMouseController.move(intDx, intDy)
                    }
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (pointerCount == 2 && isTwoFingerGesture) {
                    val duration = SystemClock.uptimeMillis() - downTime
                    val distFromDown = hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()).toFloat()
                    if (duration < TAP_TIMEOUT_MS && distFromDown < TAP_MAX_DISTANCE * 2f) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        RootMouseController.click(2)
                    }
                }
                // Handle active pointer switch if primary finger lifted
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                if (pointerId == activePointerId) {
                    val newIndex = if (pointerIndex == 0) 1 else 0
                    if (newIndex < event.pointerCount) {
                        activePointerId = event.getPointerId(newIndex)
                        lastX = event.getX(newIndex)
                        lastY = event.getY(newIndex)
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                activePointerId = MotionEvent.INVALID_POINTER_ID

                if (isDragging) {
                    RootMouseController.mouseUp(1)
                    isDragging = false
                } else if (!isTwoFingerGesture) {
                    val duration = SystemClock.uptimeMillis() - downTime
                    val distFromDown = hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()).toFloat()

                    if (duration < TAP_TIMEOUT_MS && distFromDown < TAP_MAX_DISTANCE) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        RootMouseController.click(1)
                    }
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                activePointerId = MotionEvent.INVALID_POINTER_ID
                if (isDragging) {
                    RootMouseController.mouseUp(1)
                    isDragging = false
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
