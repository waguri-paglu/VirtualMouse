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
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
 * Foreground Service hosting the full-screen trackpad overlay
 * and the floating draggable controls dock (Left Click, Right Click, Toggle).
 */
class OverlayService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private var trackpadOverlayView: GestureTrackpadView? = null
    private var controlsView: View? = null

    private lateinit var trackpadLayoutParams: WindowManager.LayoutParams
    private lateinit var controlsLayoutParams: WindowManager.LayoutParams

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isTrackpadActive = true
    private var sensitivity: Float = 1.4f
    private var scrollSensitivity: Float = 0.5f

    companion object {
        const val CHANNEL_ID = "vmouse_service_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.virtualmouse.app.ACTION_STOP"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        sensitivity = prefs.getFloat("sensitivity", 1.4f)
        scrollSensitivity = prefs.getFloat("scroll_sensitivity", 0.5f)
        prefs.registerOnSharedPreferenceChangeListener(this)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        if (!RootMouseController.isAttached()) {
            RootMouseController.startAsync(this) { success, _ ->
                if (!success) {
                    stopSelf()
                }
            }
        }

        initTrackpadOverlay()
        initFloatingControls()
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
            onPerformClickPassthrough = { button ->
                performPassthroughClick(button)
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
     * Temporarily makes the full-screen trackpad overlay non-touchable for a split second
     * while emitting the hardware uinput click so the click hits the underlying app at the cursor!
     */
    private fun performPassthroughClick(button: Int) {
        if (!isTrackpadActive || trackpadOverlayView == null) {
            RootMouseController.click(button)
            return
        }

        mainHandler.removeCallbacks(restoreTouchableRunnable)
        setOverlayTouchable(false)

        mainHandler.postDelayed({
            RootMouseController.click(button)
            mainHandler.postDelayed(restoreTouchableRunnable, 45L)
        }, 20L)
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
        val inflater = LayoutInflater.from(this)
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
            y = 100
        }

        val btnLeftClick = controlsView?.findViewById<View>(R.id.btn_left_click)
        val btnRightClick = controlsView?.findViewById<View>(R.id.btn_right_click)
        val btnToggle = controlsView?.findViewById<View>(R.id.btn_toggle_mode)
        val tvStatus = controlsView?.findViewById<TextView>(R.id.tv_mode_status)
        val dragHandle = controlsView?.findViewById<View>(R.id.drag_handle)

        // Left Click button: passes click through to underlying app
        btnLeftClick?.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            performPassthroughClick(1)
        }

        // Right Click button: passes right-click through to underlying app
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
                when (event.action) {
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
        } else {
            setOverlayTouchable(false)
            statusTextView?.text = "TOUCH"
            statusTextView?.setTextColor(0xFF38BDF8.toInt())
            trackpadOverlayView?.visibility = View.GONE
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
        controlsView?.let { windowManager.removeView(it) }
        RootMouseController.stop()
    }
}
