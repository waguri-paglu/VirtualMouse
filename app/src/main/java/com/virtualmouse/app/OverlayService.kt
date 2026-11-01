package com.virtualmouse.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
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
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager

/**
 * Virtual Mouse v1.2.0 Foreground Service:
 * - Hosts the floating controls dock (with Hide button)
 * - Hosts a 1px invisible IME sensor window that automatically switches to Touch Mode
 *   when the soft keyboard appears, and restores Trackpad Mode when the keyboard closes.
 */
class OverlayService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private var controlsView: View? = null
    private var imeSensorView: View? = null
    private lateinit var controlsLayoutParams: WindowManager.LayoutParams

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isTrackpadActive = true
    private var pausedByKeyboard = false
    private var isKeyboardVisible = false

    private var sensitivity: Float = 1.4f
    private var scrollSensitivity: Float = 0.5f
    private var showFloatingDock: Boolean = true
    private var autoKeyboardSwitch: Boolean = true
    private var threeFingerTap: Boolean = true
    private var hapticsEnabled: Boolean = true

    private var screenWidth: Int = 1080
    private var screenHeight: Int = 2400
    private var screenRotation: Int = Surface.ROTATION_0

    companion object {
        const val CHANNEL_ID = "vmouse_service_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.virtualmouse.app.ACTION_STOP"
        const val ACTION_TOGGLE_DOCK = "com.virtualmouse.app.ACTION_TOGGLE_DOCK"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        updateScreenMetrics()

        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        loadPreferences()
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

        RootMouseController.onImeVisibilityChanged = { visible ->
            mainHandler.post {
                handleKeyboardVisibilityChange(visible)
            }
        }

        RootMouseController.onDaemonEvent = { eventCode ->
            mainHandler.post {
                when (eventCode) {
                    0 -> { // CB_MODE_TOUCH
                        isTrackpadActive = false
                        updateDockUiState(false)
                    }
                    1 -> { // CB_MODE_TRACKPAD
                        isTrackpadActive = true
                        updateDockUiState(true)
                    }
                    2 -> { // CB_HIDE_OVERLAY (Hide button tapped on dock while in Trackpad mode)
                        prefs.edit().putBoolean("show_dock", false).apply()
                        Toast.makeText(
                            this,
                            "Buttons hidden. 3-finger tap swaps mode; use notification or app to restore.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

        initFloatingControls()
        VirtualMouseTileService.requestSync(this)

        RootMouseController.startAsync(this) { success, _ ->
            if (!success) {
                stopSelf()
            } else {
                mainHandler.postDelayed({
                    syncDaemonConfigAndBounds()
                    RootMouseController.setTouchGrab(true)
                    VirtualMouseTileService.requestSync(this)
                }, 180L)
            }
        }
    }

    private fun loadPreferences() {
        sensitivity = prefs.getFloat("sensitivity", 1.4f)
        scrollSensitivity = prefs.getFloat("scroll_sensitivity", 0.5f)
        showFloatingDock = prefs.getBoolean("show_dock", true)
        autoKeyboardSwitch = prefs.getBoolean("auto_ime_switch", true)
        threeFingerTap = prefs.getBoolean("three_finger_tap", true)
        hapticsEnabled = prefs.getBoolean("haptics_enabled", true)
    }

    private fun updateScreenMetrics() {
        try {
            val displayManager = getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            val defaultDisplay = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            screenRotation = defaultDisplay?.rotation ?: Surface.ROTATION_0

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.maximumWindowMetrics.bounds
                screenWidth = bounds.width()
                screenHeight = bounds.height()
            } else if (defaultDisplay != null) {
                val dm = DisplayMetrics()
                @Suppress("DEPRECATION")
                defaultDisplay.getRealMetrics(dm)
                screenWidth = dm.widthPixels
                screenHeight = dm.heightPixels
            }
        } catch (e: Exception) {
            val dm = resources.displayMetrics
            screenWidth = dm.widthPixels
            screenHeight = dm.heightPixels
            screenRotation = Surface.ROTATION_0
        }
    }

    /**
     * Invisible 1px edge sensor window with FLAG_NOT_FOCUSABLE | FLAG_ALT_FOCUSABLE_IM
     * and SOFT_INPUT_ADJUST_RESIZE so Android WindowManager notifies us immediately
     * whenever the soft keyboard (IME) opens or closes in ANY app!
     */
    private fun initImeSensorWindow() {
        val sensor = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            1,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSPARENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        sensor.setOnApplyWindowInsetsListener { v, insets ->
            val imeVisible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.isVisible(WindowInsets.Type.ime()) ||
                        insets.getInsets(WindowInsets.Type.ime()).bottom > (screenHeight * 0.12)
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom > (screenHeight * 0.15)
            }
            handleKeyboardVisibilityChange(imeVisible)
            v.onApplyWindowInsets(insets)
        }

        sensor.viewTreeObserver.addOnGlobalLayoutListener {
            val h = sensor.height
            if (h > 0 && screenHeight > 0) {
                val shrunkByKeyboard = h < (screenHeight * 0.80)
                handleKeyboardVisibilityChange(shrunkByKeyboard)
            }
        }

        imeSensorView = sensor
        try {
            windowManager.addView(sensor, params)
        } catch (ignored: Exception) {}
    }

    private fun handleKeyboardVisibilityChange(visible: Boolean) {
        if (visible == isKeyboardVisible) return
        isKeyboardVisible = visible

        if (!autoKeyboardSwitch) return

        if (visible) {
            // Soft keyboard appeared -> automatically switch to Touch mode so user can type!
            if (isTrackpadActive) {
                pausedByKeyboard = true
                isTrackpadActive = false
                RootMouseController.setTouchGrab(false)
                updateDockUiState(false, isKeyboardAuto = true)
            }
        } else {
            // Soft keyboard closed -> automatically switch back to Trackpad mode!
            if (pausedByKeyboard) {
                pausedByKeyboard = false
                mainHandler.postDelayed({
                    if (!isKeyboardVisible) {
                        isTrackpadActive = true
                        syncDaemonConfigAndBounds()
                        RootMouseController.setTouchGrab(true)
                        updateDockUiState(true)
                    }
                }, 160L)
            }
        }
    }

    private fun syncDaemonConfigAndBounds() {
        updateScreenMetrics()
        RootMouseController.updateConfig(
            sensitivity,
            scrollSensitivity,
            screenWidth,
            screenHeight,
            screenRotation,
            threeFingerTap
        )
        val view = controlsView
        if (!showFloatingDock || view == null || view.visibility != View.VISIBLE) {
            RootMouseController.updateDockBounds(0, 0, 0, 0)
        } else {
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
        loadPreferences()
        when (key) {
            "show_dock" -> {
                updateDockVisibility()
            }
            "sensitivity", "scroll_sensitivity", "three_finger_tap" -> {
                syncDaemonConfigAndBounds()
            }
        }
    }

    private fun updateDockVisibility() {
        controlsView?.visibility = if (showFloatingDock) View.VISIBLE else View.GONE
        controlsView?.post {
            syncDaemonConfigAndBounds()
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, createNotification())
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun initFloatingControls() {
        val themedContext = ContextThemeWrapper(this, R.style.Theme_VirtualMouse)
        val inflater = LayoutInflater.from(themedContext)
        controlsView = inflater.inflate(R.layout.layout_overlay_controls, null)
        controlsView?.visibility = if (showFloatingDock) View.VISIBLE else View.GONE

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
        val btnHideDock = controlsView?.findViewById<View>(R.id.btn_hide_dock)
        val dragHandle = controlsView?.findViewById<View>(R.id.drag_handle)

        btnLeftClick?.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (hapticsEnabled) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
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
            if (hapticsEnabled) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            RootMouseController.click(2)
        }

        btnToggle?.setOnClickListener { v ->
            if (hapticsEnabled) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            pausedByKeyboard = false
            val targetGrab = !isTrackpadActive
            isTrackpadActive = targetGrab
            updateDockUiState(targetGrab)
            if (targetGrab) {
                mainHandler.postDelayed({
                    syncDaemonConfigAndBounds()
                    RootMouseController.setTouchGrab(true)
                }, 150L)
            } else {
                RootMouseController.setTouchGrab(false)
            }
        }

        btnHideDock?.setOnClickListener { v ->
            if (hapticsEnabled) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            prefs.edit().putBoolean("show_dock", false).apply()
            Toast.makeText(
                this,
                "Buttons hidden. 3-finger tap swaps mode; use notification or app to restore.",
                Toast.LENGTH_SHORT
            ).show()
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

    private fun updateDockUiState(grabbed: Boolean, isKeyboardAuto: Boolean = false) {
        val tvStatus = controlsView?.findViewById<TextView>(R.id.tv_mode_status)
        if (hapticsEnabled) {
            controlsView?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
        if (grabbed) {
            tvStatus?.text = "TRACKPAD"
            tvStatus?.setTextColor(0xFF10B981.toInt())
        } else {
            tvStatus?.text = if (isKeyboardAuto) "TOUCH (KB)" else "TOUCH"
            tvStatus?.setTextColor(0xFF38BDF8.toInt())
        }
        VirtualMouseTileService.requestSync(this)
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

        val toggleDockIntent = Intent(this, OverlayService::class.java).apply {
            action = ACTION_TOGGLE_DOCK
        }
        val toggleDockPending = PendingIntent.getService(
            this, 2, toggleDockIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, OverlayService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val dockActionLabel = if (showFloatingDock) "Hide Buttons" else "Show Buttons"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Virtual Mouse v1.2 Active")
            .setContentText("3-finger tap swaps mode • Auto-keyboard touch enabled")
            .setSmallIcon(R.drawable.ic_mouse)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_mouse, dockActionLabel, toggleDockPending)
            .addAction(R.drawable.ic_toggle, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_DOCK -> {
                val next = !prefs.getBoolean("show_dock", true)
                prefs.edit().putBoolean("show_dock", next).apply()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        RootMouseController.setTouchGrab(false)
        controlsView?.let {
            try { windowManager.removeView(it) } catch (ignored: Exception) {}
        }
        RootMouseController.stop()
        VirtualMouseTileService.requestSync(this)
    }
}

/**
 * Quick Settings (QS) Tile to start and stop the Virtual Mouse in a single tap.
 * - Tapping when OFF -> Starts Virtual Mouse & turns Tile ON (Active)
 * - Tapping when ON  -> Stops Virtual Mouse & turns Tile OFF (Inactive)
 */
class VirtualMouseTileService : TileService() {

    companion object {
        fun requestSync(context: Context) {
            try {
                requestListeningState(
                    context,
                    ComponentName(context, VirtualMouseTileService::class.java)
                )
            } catch (ignored: Exception) {}
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        syncTileState()
    }

    override fun onClick() {
        super.onClick()

        val currentlyRunning = RootMouseController.isAttached()

        if (currentlyRunning) {
            val stopIntent = Intent(this, OverlayService::class.java)
            stopService(stopIntent)
            RootMouseController.stop()
            updateTileUi(active = false, subtitleText = "Off")
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Grant Overlay Permission first", Toast.LENGTH_LONG).show()
                val actIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val pending = PendingIntent.getActivity(
                        this, 0, actIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    startActivityAndCollapse(pending)
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(actIntent)
                }
                return
            }

            updateTileUi(active = true, subtitleText = "Starting...")

            RootMouseController.startAsync(this) { success, errorMsg ->
                if (success) {
                    val serviceIntent = Intent(this, OverlayService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                    updateTileUi(active = true, subtitleText = "Active")
                } else {
                    updateTileUi(active = false, subtitleText = "Error")
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(
                            this,
                            errorMsg ?: "Failed to start Virtual Mouse (check root)",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    private fun syncTileState() {
        val active = RootMouseController.isAttached()
        updateTileUi(active = active, subtitleText = if (active) "Active" else "Off")
    }

    private fun updateTileUi(active: Boolean, subtitleText: String) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Virtual Mouse"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = subtitleText
        }
        tile.updateTile()
    }
}
