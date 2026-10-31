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

/**
 * Custom full-screen touch interceptor that converts multi-touch gestures
 * into relative mouse movement, clicks, scrolls, and drag operations.
 */
class GestureTrackpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var sensitivity: Float = 1.3f
    var scrollSensitivity: Float = 0.5f

    private var lastX: Float = 0f
    private var lastY: Float = 0f
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var downTime: Long = 0L

    private var initialPointerDistance: Float = 0f
    private var lastTwoFingerY: Float = 0f
    private var lastTwoFingerX: Float = 0f

    private var isDragging = false
    private var isTwoFingerGesture = false
    private val handler = Handler(Looper.getMainLooper())

    private val longPressRunnable = Runnable {
        // Trigger drag mode on long press
        if (!isTwoFingerGesture) {
            isDragging = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            RootMouseController.mouseDown(1) // Hold left click
        }
    }

    private companion object {
        const val TAP_TIMEOUT_MS = 220L
        const val TAP_MAX_DISTANCE = 35f // px
        const val LONG_PRESS_TIMEOUT_MS = 450L
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
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                downTime = SystemClock.uptimeMillis()
                isDragging = false
                isTwoFingerGesture = false

                // Schedule long press detection for click-and-drag
                handler.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT_MS)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                handler.removeCallbacks(longPressRunnable)
                if (pointerCount == 2) {
                    isTwoFingerGesture = true
                    lastTwoFingerX = (event.getX(0) + event.getX(1)) / 2f
                    lastTwoFingerY = (event.getY(0) + event.getY(1)) / 2f
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (isTwoFingerGesture && pointerCount >= 2) {
                    // Two-finger scroll
                    val currentMidX = (event.getX(0) + event.getX(1)) / 2f
                    val currentMidY = (event.getY(0) + event.getY(1)) / 2f
                    val deltaY = currentMidY - lastTwoFingerY
                    val deltaX = currentMidX - lastTwoFingerX

                    val scrollAmountY = (deltaY * scrollSensitivity).toInt()
                    val scrollAmountX = (deltaX * scrollSensitivity).toInt()

                    if (abs(scrollAmountY) >= 1) {
                        // Invert so swiping up scrolls up
                        RootMouseController.scroll(scrollAmountY)
                        lastTwoFingerY = currentMidY
                    }
                    if (abs(scrollAmountX) >= 1) {
                        RootMouseController.scrollHorizontal(scrollAmountX)
                        lastTwoFingerX = currentMidX
                    }
                } else if (!isTwoFingerGesture) {
                    // Single finger move or drag
                    val dx = (event.x - lastX) * sensitivity
                    val dy = (event.y - lastY) * sensitivity

                    // Cancel long press if finger moved significantly
                    val distFromDown = Math.hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()).toFloat()
                    if (distFromDown > TAP_MAX_DISTANCE) {
                        handler.removeCallbacks(longPressRunnable)
                    }

                    if (abs(dx) >= 0.5f || abs(dy) >= 0.5f) {
                        RootMouseController.move(dx.toInt(), dy.toInt())
                        lastX = event.x
                        lastY = event.y
                    }
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (pointerCount == 2 && isTwoFingerGesture) {
                    val duration = SystemClock.uptimeMillis() - downTime
                    val distFromDown = Math.hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()).toFloat()
                    if (duration < TAP_TIMEOUT_MS && distFromDown < TAP_MAX_DISTANCE * 2) {
                        // Two finger tap -> Right Click!
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        RootMouseController.click(2)
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)

                if (isDragging) {
                    // Release left button
                    RootMouseController.mouseUp(1)
                    isDragging = false
                } else if (!isTwoFingerGesture) {
                    val duration = SystemClock.uptimeMillis() - downTime
                    val distFromDown = Math.hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()).toFloat()

                    if (duration < TAP_TIMEOUT_MS && distFromDown < TAP_MAX_DISTANCE) {
                        // 1 Finger Tap -> Left Click!
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        RootMouseController.click(1)
                    }
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
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
