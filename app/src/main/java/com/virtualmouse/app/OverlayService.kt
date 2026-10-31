package com.virtualmouse.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager

/**
 * Foreground Service hosting:
 * 1) Full-screen trackpad touch surface
 * 2) Real-time 120Hz hardware-accelerated CursorArrowView (never hidden by TouchInputMapper!)
 * 3) Draggable floating controls dock (Left Click, Mode Toggle, Right Click)
 */
class OverlayService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private var trackpadOverlayView: GestureTrackpadView? = null
    private var cursorArrowView: CursorArrowView? = null
    private var controlsView: View? = null

    private lateinit var trackpadLayoutParams: WindowManager.LayoutParams
    private lateinit var cursorLayoutParams: WindowManager.LayoutParams
    private lateinit var controlsLayoutParams: WindowManager.LayoutParams

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isTrackpadActive = true
    private var sensitivity: Float = 1.4f
    private var scrollSensitivity: Float = 0.5f

    private var screenWidth: Int = 1080
    private var screenHeight: Int = 2400
    private var cursorX: Float = 540f
    private var cursorY: Float = 1200f
    private var isHoldingLeftButton = false

    companion object {
        const val CHANNEL_ID = "vmouse_service_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.virtualmouse.app.ACTION_STOP"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        updateScreenDimensions()

        cursorX = screenWidth * 0.5f
        cursorY = screenHeight * 0.5f

        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        sensitivity = prefs.getFloat("sensitivity", 1.4f)
        scrollSensitivity = prefs.getFloat("scroll_sensitivity", 0.5f)
        prefs.registerOnSharedPreferenceChangeListener(this)

        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }

        if (!RootMouseController.isAttached()) {
            RootMouseController.startAsync(this) { success, _ ->
                if (!success) {
                    stopSelf()
                }
            }
        }

        initTrackpadOverlay()
        initCursorOverlay()
        initFloatingControls()
    }

    private fun updateScreenDimensions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            screenWidth = dm.widthPixels
            screenHeight = dm.heightPixels
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenDimensions()
        cursorX = cursorX.coerceIn(0f, screenWidth.toFloat())
        cursorY = cursorY.coerceIn(0f, screenHeight.toFloat())
        cursorArrowView?.translationX = cursorX
        cursorArrowView?.translationY = cursorY
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (sharedPreferences == null) return
        when (key) {
            "sensitivity" -> {
                sensitivity = sharedPreferences.getFloat("sensitivity", 1.4f)
                trackpadOverlayView?.sensitivity = sensitivity
            }
            "scroll_sensitivity" -> {
                scrollSensitivity = sharedPreferences.getFloat("scroll_sensitivity", 0.5f)
                trackpadOverlayView?.scrollSensitivity = scrollSensitivity
            }
        }
    }

    private fun initTrackpadOverlay() {
        trackpadOverlayView = GestureTrackpadView(this).apply {
            this.sensitivity = this@OverlayService.sensitivity
            this.scrollSensitivity = this@OverlayService.scrollSensitivity
            setBackgroundColor(Color.TRANSPARENT)

            onMoveCursor = { dx, dy ->
                updateScreenDimensions()
                cursorX = (cursorX + dx).coerceIn(0f, (screenWidth - 2).toFloat())
                cursorY = (cursorY + dy).coerceIn(0f, (screenHeight - 2).toFloat())
                // Update hardware-accelerated cursor position on screen with 0.0ms latency!
                cursorArrowView?.translationX = cursorX
                cursorArrowView?.translationY = cursorY

                if (isHoldingLeftButton) {
                    RootMouseController.dragMoveAt(cursorX, cursorY, screenWidth, screenHeight)
                }
            }

            onPerformClick = { button ->
                performPassthroughClick(button)
            }

            onStartDrag = { _ ->
                startPassthroughDrag()
            }

            onDragMove = {
                RootMouseController.dragMoveAt(cursorX, cursorY, screenWidth, screenHeight)
            }

            onEndDrag = {
                endPassthroughDrag()
            }

            onPerformScroll = { dx, dy ->
                performPassthroughScroll(dx, dy)
            }
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        trackpadLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSPARENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        windowManager.addView(trackpadOverlayView, trackpadLayoutParams)
    }

    /**
     * Initializes the dedicated non-touchable full-screen layer that renders
     * the 120Hz hardware-accelerated mouse cursor arrow.
     */
    private fun initCursorOverlay() {
        val density = resources.displayMetrics.density
        val cursorSizePx = (36 * density).toInt()

        cursorArrowView = CursorArrowView(this).apply {
            translationX = cursorX
            translationY = cursorY
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // Full-screen transparent non-touchable container so translationX/Y can move anywhere effortlessly
        cursorLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        windowManager.addView(cursorArrowView, cursorLayoutParams)
    }

    private fun performPassthroughClick(button: Int) {
        updateScreenDimensions()
        cursorArrowView?.isPressedState = true

        if (!isTrackpadActive || trackpadOverlayView == null) {
            if (button == 2) {
                RootMouseController.rightClickAt(cursorX, cursorY, screenWidth, screenHeight)
            } else {
                RootMouseController.leftClickAt(cursorX, cursorY, screenWidth, screenHeight)
            }
            mainHandler.postDelayed({ cursorArrowView?.isPressedState = false }, 120L)
            return
        }

        mainHandler.removeCallbacks(restoreTouchableRunnable)
        setOverlayTouchable(false)

        mainHandler.postDelayed({
            if (button == 2) {
                RootMouseController.rightClickAt(cursorX, cursorY, screenWidth, screenHeight)
                mainHandler.postDelayed(restoreTouchableRunnable, 560L)
            } else {
                RootMouseController.leftClickAt(cursorX, cursorY, screenWidth, screenHeight)
                mainHandler.postDelayed(restoreTouchableRunnable, 50L)
            }
            mainHandler.postDelayed({ cursorArrowView?.isPressedState = false }, 120L)
        }, 22L)
    }

    private fun startPassthroughDrag() {
        updateScreenDimensions()
        isHoldingLeftButton = true
        cursorArrowView?.isPressedState = true

        // Temporarily make trackpad passthrough for 25ms so ACTION_DOWN locks onto the underlying app!
        mainHandler.removeCallbacks(restoreTouchableRunnable)
        setOverlayTouchable(false)

        mainHandler.postDelayed({
            RootMouseController.touchDownAt(cursorX, cursorY, screenWidth, screenHeight)
            mainHandler.postDelayed(restoreTouchableRunnable, 25L)
        }, 20L)
    }

    private fun endPassthroughDrag() {
        isHoldingLeftButton = false
        cursorArrowView?.isPressedState = false
        RootMouseController.touchUpAt()
    }

    private var isScrollingUnderlying = false
    private var scrollXCurrent = 0f
    private var scrollYCurrent = 0f

    private fun performPassthroughScroll(dx: Float, dy: Float) {
        updateScreenDimensions()
        if (!isScrollingUnderlying) {
            isScrollingUnderlying = true
            scrollXCurrent = cursorX
            scrollYCurrent = cursorY
            mainHandler.removeCallbacks(endScrollRunnable)
            mainHandler.removeCallbacks(restoreTouchableRunnable)
            setOverlayTouchable(false)

            mainHandler.postDelayed({
                RootMouseController.touchDownAt(scrollXCurrent, scrollYCurrent, screenWidth, screenHeight)
                mainHandler.postDelayed(restoreTouchableRunnable, 22L)
            }, 18L)
        }

        scrollXCurrent = (scrollXCurrent + dx).coerceIn(10f, (screenWidth - 10).toFloat())
        scrollYCurrent = (scrollYCurrent + dy).coerceIn(10f, (screenHeight - 10).toFloat())
        RootMouseController.dragMoveAt(scrollXCurrent, scrollYCurrent, screenWidth, screenHeight)

        mainHandler.removeCallbacks(endScrollRunnable)
        mainHandler.postDelayed(endScrollRunnable, 160L)
    }

    private val endScrollRunnable = Runnable {
        if (isScrollingUnderlying) {
            isScrollingUnderlying = false
            RootMouseController.touchUpAt()
        }
    }

    private val restoreTouchableRunnable = Runnable {
        if (isTrackpadActive) {
            setOverlayTouchable(true)
        }
    }

    private fun setOverlayTouchable(touchable: Boolean) {
        val view = trackpadOverlayView ?: return
        try {
            if (touchable) {
                trackpadLayoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            } else {
                trackpadLayoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            }
            windowManager.updateViewLayout(view, trackpadLayoutParams)
        } catch (ignored: Exception) {}
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun initFloatingControls() {
        // Wrap Service context in AppTheme so layout inflation never fails on any ROM
        val themedContext = ContextThemeWrapper(this, R.style.Theme_VirtualMouse)
        val inflater = LayoutInflater.from(themedContext)
        controlsView = inflater.inflate(R.layout.layout_overlay_controls, null)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        controlsLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 120
        }

        val btnLeftClick = controlsView?.findViewById<View>(R.id.btn_left_click)
        val btnRightClick = controlsView?.findViewById<View>(R.id.btn_right_click)
        val btnToggle = controlsView?.findViewById<View>(R.id.btn_toggle_mode)
        val tvStatus = controlsView?.findViewById<TextView>(R.id.tv_mode_status)
        val dragHandle = controlsView?.findViewById<View>(R.id.drag_handle)

        // Left Click button: supports both instant click AND hold-to-drag!
        btnLeftClick?.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    startPassthroughDrag()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    endPassthroughDrag()
                    true
                }
                else -> false
            }
        }

        // Right Click button
        btnRightClick?.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            performPassthroughClick(2)
        }

        // Toggle Trackpad vs Passthrough touch mode
        btnToggle?.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            toggleTrackpadMode(tvStatus)
        }

        dragHandle?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = controlsLayoutParams.x
                        initialY = controlsLayoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        controlsLayoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                        controlsLayoutParams.y = initialY - (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(controlsView, controlsLayoutParams)
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(controlsView, controlsLayoutParams)
    }

    private fun toggleTrackpadMode(statusTextView: TextView?) {
        isTrackpadActive = !isTrackpadActive

        if (isTrackpadActive) {
            setOverlayTouchable(true)
            statusTextView?.text = "TRACKPAD"
            statusTextView?.setTextColor(0xFF10B981.toInt())
            trackpadOverlayView?.visibility = View.VISIBLE
            cursorArrowView?.visibility = View.VISIBLE
        } else {
            setOverlayTouchable(false)
            statusTextView?.text = "TOUCH"
            statusTextView?.setTextColor(0xFF38BDF8.toInt())
            trackpadOverlayView?.visibility = View.GONE
            cursorArrowView?.visibility = View.GONE
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Virtual Mouse Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps virtual trackpad and mouse overlay active"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val appIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, appIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, OverlayService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Virtual Mouse Running")
            .setContentText("Screen acting as trackpad. Tap notification to open settings.")
            .setSmallIcon(R.drawable.ic_mouse)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_toggle, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        trackpadOverlayView?.let { windowManager.removeView(it) }
        cursorArrowView?.let { windowManager.removeView(it) }
        controlsView?.let { windowManager.removeView(it) }
        RootMouseController.stop()
    }
}
