package com.virtualmouse.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * Full-screen trackpad interceptor that updates the live on-screen cursor arrow
 * in real time on every single touch sample (120Hz/240Hz) without waiting for finger lift.
 */
class GestureTrackpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    @Volatile var sensitivity: Float = 1.4f
    @Volatile var scrollSensitivity: Float = 0.5f

    var onMoveCursor: ((Float, Float) -> Unit)? = null
    var onPerformClick: ((Int) -> Unit)? = null
    var onStartDrag: (( Boolean ) -> Unit)? = null
    var onDragMove: (() -> Unit)? = null
    var onEndDrag: (() -> Unit)? = null
    var onPerformScroll: ((Float, Float) -> Unit)? = null

    private var lastX: Float = 0f
    private var lastY: Float = 0f
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var downTime: Long = 0L

    private var lastTwoFingerY: Float = 0f
    private var lastTwoFingerX: Float = 0f

    private var isDragging = false
    private var isTwoFingerGesture = false
    private var isScrollStreamStarted = false
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private val handler = Handler(Looper.getMainLooper())

    private val longPressRunnable = Runnable {
        if (!isTwoFingerGesture) {
            isDragging = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onStartDrag?.invoke(true)
        }
    }

    private companion object {
        const val TAP_TIMEOUT_MS = 210L
        const val TAP_MAX_DISTANCE = 26f
        const val LONG_PRESS_TIMEOUT_MS = 400L
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val pointerCount = event.pointerCount

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                requestUnbufferedDispatch(event)

                activePointerId = event.getPointerId(0)
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                downTime = SystemClock.uptimeMillis()
                isDragging = false
                isTwoFingerGesture = false
                isScrollStreamStarted = false

                handler.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT_MS)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                handler.removeCallbacks(longPressRunnable)
                if (pointerCount == 2) {
                    isTwoFingerGesture = true
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

                    if (hypot(deltaX.toDouble(), deltaY.toDouble()) > 1.5) {
                        onPerformScroll?.invoke(deltaX * scrollSensitivity * 2.0f, deltaY * scrollSensitivity * 2.0f)
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

                    val distFromDown = hypot((currX - downX).toDouble(), (currY - downY).toDouble()).toFloat()
                    if (distFromDown > TAP_MAX_DISTANCE) {
                        handler.removeCallbacks(longPressRunnable)
                    }

                    val dx = rawDx * sensitivity * 1.4f
                    val dy = rawDy * sensitivity * 1.4f

                    if (dx != 0f || dy != 0f) {
                        // Immediately move the visible cursor arrow on screen in real time!
                        onMoveCursor?.invoke(dx, dy)
                        if (isDragging) {
                            onDragMove?.invoke()
                        }
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
                        onPerformClick?.invoke(2)
                    }
                }
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
                    isDragging = false
                    onEndDrag?.invoke()
                } else if (!isTwoFingerGesture) {
                    val duration = SystemClock.uptimeMillis() - downTime
                    val distFromDown = hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()).toFloat()

                    if (duration < TAP_TIMEOUT_MS && distFromDown < TAP_MAX_DISTANCE) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onPerformClick?.invoke(1)
                    }
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                activePointerId = MotionEvent.INVALID_POINTER_ID
                if (isDragging) {
                    isDragging = false
                    onEndDrag?.invoke()
                }
                return true
            }
        }
        return true
    }
}

/**
 * Dedicated 120Hz Hardware-Accelerated System Cursor Arrow View.
 * Always remains visible while your finger touches the screen and moves with 0.0ms latency.
 */
class CursorArrowView(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density
    private val arrowPath = Path()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#0F172A")
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#5538BDF8")
    }

    var isPressedState: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                fillPaint.color = if (value) Color.parseColor("#38BDF8") else Color.WHITE
                invalidate()
            }
        }

    init {
        // Construct classic crisp desktop arrow polygon with tip at (0, 0)
        val s = density * 1.15f
        arrowPath.moveTo(0f, 0f)
        arrowPath.lineTo(0f, 21f * s)
        arrowPath.lineTo(5.8f * s, 16.2f * s)
        arrowPath.lineTo(9.8f * s, 24.5f * s)
        arrowPath.lineTo(13.0f * s, 23.0f * s)
        arrowPath.lineTo(9.0f * s, 14.8f * s)
        arrowPath.lineTo(15.5f * s, 14.8f * s)
        arrowPath.close()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isPressedState) {
            canvas.drawCircle(3f * density, 3f * density, 12f * density, glowPaint)
        }
        canvas.drawPath(arrowPath, fillPaint)
        canvas.drawPath(arrowPath, strokePaint)
    }
}
