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
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager

/**
 * Foreground Service for the EVIOCGRAB + Original /dev/uinput Virtual Mouse.
 * Does NOT place any full-screen overlay over the screen, so all native /dev/uinput
 * left and right clicks hit underlying apps directly!
 */
class OverlayService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private var controlsView: View? = null
    private lateinit var controlsLayoutParams: WindowManager.LayoutParams

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isTrackpadActive = true
    private var sensitivity: Float = 1.4f
    private var scrollSensitivity: Float = 0.5f

    private var screenWidth: Int = 1080
    private var screenHeight: Int = 2400
    private var screenRotation: Int = Surface.ROTATION_0

    companion object {
        const val CHANNEL_ID = "vmouse_service_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.virtualmouse.app.ACTION_STOP"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        updateScreenMetrics()

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

        RootMouseController.onGrabModeChangedFromDaemon = { grabbed ->
            mainHandler.post {
                isTrackpadActive = grabbed
                updateDockUiState(grabbed)
            }
        }

        initFloatingControls()

        // Ensure daemon is running, push config & dock bounds, and activate EVIOCGRAB after 180ms
        // so the user's finger from tapping 'Start Virtual Mouse' has completely lifted!
        RootMouseController.startAsync(this) { success, _ ->
            if (!success) {
                stopSelf()
            } else {
                mainHandler.postDelayed({
                    syncDaemonConfigAndBounds()
                    RootMouseController.setTouchGrab(true)
                }, 180L)
            }
        }
    }

    private fun updateScreenMetrics() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
            screenRotation = display?.rotation ?: Surface.ROTATION_0
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            val disp = windowManager.defaultDisplay
            @Suppress("DEPRECATION")
            disp.getRealMetrics(dm)
            screenWidth = dm.widthPixels
            screenHeight = dm.heightPixels
            @Suppress("DEPRECATION")
            screenRotation = disp.rotation
        }
    }

    private fun syncDaemonConfigAndBounds() {
        updateScreenMetrics()
        RootMouseController.updateConfig(
            sensitivity,
            scrollSensitivity,
            screenWidth,
            screenHeight,
            screenRotation
        )
        controlsView?.let { view ->
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            val left = loc[0]
            val top = loc[1]
            val right = left + view.width
            val bottom = top + view.height
            if (view.width > 0 && view.height > 0) {
                RootMouseController.updateDockBounds(left, top, right, bottom)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        mainHandler.postDelayed({ syncDaemonConfigAndBounds() }, 100L)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (sharedPreferences == null) return
        when (key) {
            "sensitivity" -> {
                sensitivity = sharedPreferences.getFloat("sensitivity", 1.4f)
                syncDaemonConfigAndBounds()
            }
            "scroll_sensitivity" -> {
                scrollSensitivity = sharedPreferences.getFloat("scroll_sensitivity", 0.5f)
                syncDaemonConfigAndBounds()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun initFloatingControls() {
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
        val dragHandle = controlsView?.findViewById<View>(R.id.drag_handle)

        // Works when clicked via the mouse cursor or in Normal Touch mode
        btnLeftClick?.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    RootMouseController.mouseDown(1)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    RootMouseController.mouseUp(1)
                    true
                }
                else -> false
            }
        }

        btnRightClick?.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            RootMouseController.click(2)
        }

        // Toggle Trackpad (EVIOCGRAB=1) vs Normal Touch (EVIOCGRAB=0)
        btnToggle?.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val targetGrab = !isTrackpadActive
            isTrackpadActive = targetGrab
            updateDockUiState(targetGrab)
            if (targetGrab) {
                // Delay 150ms so finger lift from tapping MODE completes before grabbing touchscreen
                mainHandler.postDelayed({
                    syncDaemonConfigAndBounds()
                    RootMouseController.setTouchGrab(true)
                }, 150L)
            } else {
                RootMouseController.setTouchGrab(false)
            }
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
                        syncDaemonConfigAndBounds()
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        syncDaemonConfigAndBounds()
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(controlsView, controlsLayoutParams)
        controlsView?.post {
            syncDaemonConfigAndBounds()
        }
    }

    private fun updateDockUiState(grabbed: Boolean) {
        val tvStatus = controlsView?.findViewById<TextView>(R.id.tv_mode_status)
        controlsView?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (grabbed) {
            tvStatus?.text = "TRACKPAD"
            tvStatus?.setTextColor(0xFF10B981.toInt())
        } else {
            tvStatus?.text = "TOUCH"
            tvStatus?.setTextColor(0xFF38BDF8.toInt())
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Virtual Mouse Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps virtual trackpad and mouse active"
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
            .setContentTitle("Virtual Mouse Active (uinput + EVIOCGRAB)")
            .setContentText("Tap MODE on dock to switch between Trackpad and Touch.")
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
        RootMouseController.setTouchGrab(false)
        controlsView?.let { windowManager.removeView(it) }
        RootMouseController.stop()
    }
}
