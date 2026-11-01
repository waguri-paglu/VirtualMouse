package com.virtualmouse.app

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.virtualmouse.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var isServiceActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply Google Pixel Monet Dynamic Wallpaper Colors (Android 12+)
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.registerOnSharedPreferenceChangeListener(this)

        setupBottomNavigation()
        setupListeners()
        setupCalibrationSliders()
        setupPresetChips()
        setupPreferenceSwitches()

        RootMouseController.onStateChanged = { state, message ->
            runOnUiThread {
                handleDriverState(state, message)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        prefs.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onResume() {
        super.onResume()
        updateRootStatus()
        updateOverlayStatus()
        syncServiceState()
        syncSwitchesFromPrefs()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == "show_dock") {
            runOnUiThread { syncSwitchesFromPrefs() }
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    binding.pageHome.visibility = View.VISIBLE
                    binding.pageSettings.visibility = View.GONE
                    binding.tvPageTitle.text = "Virtual Mouse"
                    true
                }
                R.id.nav_settings -> {
                    binding.pageHome.visibility = View.GONE
                    binding.pageSettings.visibility = View.VISIBLE
                    binding.tvPageTitle.text = "Settings"
                    true
                }
                else -> false
            }
        }
    }

    private fun setupPreferenceSwitches() {
        syncSwitchesFromPrefs()

        binding.switchHomeShowDock.setOnCheckedChangeListener { _, isChecked ->
            if (prefs.getBoolean("show_dock", true) != isChecked) {
                prefs.edit().putBoolean("show_dock", isChecked).apply()
            }
            binding.switchShowDock.isChecked = isChecked
        }

        binding.switchShowDock.setOnCheckedChangeListener { _, isChecked ->
            if (prefs.getBoolean("show_dock", true) != isChecked) {
                prefs.edit().putBoolean("show_dock", isChecked).apply()
            }
            binding.switchHomeShowDock.isChecked = isChecked
        }

        binding.switchAutoIme.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_ime_switch", isChecked).apply()
        }

        binding.switchThreeFinger.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("three_finger_tap", isChecked).apply()
        }

        binding.switchHaptics.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("haptics_enabled", isChecked).apply()
        }
    }

    private fun syncSwitchesFromPrefs() {
        val showDock = prefs.getBoolean("show_dock", true)
        binding.switchHomeShowDock.isChecked = showDock
        binding.switchShowDock.isChecked = showDock
        binding.switchAutoIme.isChecked = prefs.getBoolean("auto_ime_switch", true)
        binding.switchThreeFinger.isChecked = prefs.getBoolean("three_finger_tap", true)
        binding.switchHaptics.isChecked = prefs.getBoolean("haptics_enabled", true)
    }

    private fun setupListeners() {
        binding.btnGrantOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                Toast.makeText(this, "Overlay permission already granted!", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnStartStop.setOnClickListener {
            if (isServiceActive) {
                stopVirtualMouse()
            } else {
                startVirtualMouse()
            }
        }

        binding.btnInfo.setOnClickListener {
            showInfoDialog()
        }
    }

    private fun startVirtualMouse() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please grant Overlay Permission first!", Toast.LENGTH_LONG).show()
            binding.btnGrantOverlay.performClick()
            return
        }

        setLoadingState(true, "Requesting superuser access...")

        RootMouseController.startAsync(this) { success, errorMsg ->
            runOnUiThread {
                setLoadingState(false)
                if (success) {
                    val serviceIntent = Intent(this, OverlayService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                    isServiceActive = true
                    updateUiActiveState(true)
                    Toast.makeText(this, "Virtual Mouse v1.2 Active!", Toast.LENGTH_SHORT).show()
                } else {
                    updateUiActiveState(false)
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Initialization Failed")
                        .setMessage(errorMsg ?: "Could not initialize kernel virtual mouse driver.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun stopVirtualMouse() {
        val serviceIntent = Intent(this, OverlayService::class.java)
        stopService(serviceIntent)
        RootMouseController.stop()
        isServiceActive = false
        updateUiActiveState(false)
        Toast.makeText(this, "Virtual Mouse Stopped", Toast.LENGTH_SHORT).show()
    }

    private fun syncServiceState() {
        isServiceActive = RootMouseController.isAttached()
        updateUiActiveState(isServiceActive)
    }

    private fun setLoadingState(isLoading: Boolean, message: String = "") {
        if (isLoading) {
            binding.progressBarLaunch.visibility = View.VISIBLE
            binding.tvLaunchStatus.visibility = View.VISIBLE
            binding.tvLaunchStatus.text = message
            binding.btnStartStop.isEnabled = false
            binding.btnStartStop.text = "Initializing..."
        } else {
            binding.progressBarLaunch.visibility = View.GONE
            binding.btnStartStop.isEnabled = true
        }
    }

    private fun handleDriverState(state: RootMouseController.DriverState, message: String) {
        when (state) {
            RootMouseController.DriverState.CHECKING_ROOT,
            RootMouseController.DriverState.DEPLOYING,
            RootMouseController.DriverState.CONNECTING -> {
                binding.progressBarLaunch.visibility = View.VISIBLE
                binding.tvLaunchStatus.visibility = View.VISIBLE
                binding.tvLaunchStatus.text = message
            }
            RootMouseController.DriverState.RUNNING -> {
                binding.progressBarLaunch.visibility = View.GONE
                binding.tvLaunchStatus.text = "Virtual mouse active"
                isServiceActive = true
                updateUiActiveState(true)
            }
            RootMouseController.DriverState.ERROR -> {
                binding.progressBarLaunch.visibility = View.GONE
                binding.tvLaunchStatus.text = "Error: $message"
            }
            RootMouseController.DriverState.IDLE -> {
                binding.progressBarLaunch.visibility = View.GONE
                binding.tvLaunchStatus.text = "Ready to launch"
                isServiceActive = false
                updateUiActiveState(false)
            }
        }
    }

    private fun updateUiActiveState(active: Boolean) {
        if (active) {
            binding.btnStartStop.text = "Stop Virtual Mouse"
            binding.chipServiceStatus.text = "RUNNING"
            binding.tvLaunchStatus.text = "Hardware pointer & trackpad active"
        } else {
            binding.btnStartStop.text = "Start Virtual Mouse"
            binding.chipServiceStatus.text = "STANDBY"
            binding.tvLaunchStatus.text = "Ready to launch"
        }
    }

    private fun setupCalibrationSliders() {
        val isTablet = resources.configuration.smallestScreenWidthDp >= 600
        val defaultSens = if (isTablet) 1.6f else 1.4f

        val currentSens = prefs.getFloat("sensitivity", defaultSens)
        binding.sbSensitivity.progress = (currentSens * 10).toInt()
        binding.tvSensitivityVal.text = String.format("%.1fx", currentSens)

        binding.sbSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = Math.max(0.5f, progress / 10f)
                binding.tvSensitivityVal.text = String.format("%.1fx", value)
                prefs.edit().putFloat("sensitivity", value).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        val currentScrollSens = prefs.getFloat("scroll_sensitivity", 0.5f)
        binding.sbScrollSensitivity.progress = (currentScrollSens * 10).toInt()
        binding.tvScrollVal.text = String.format("%.1fx", currentScrollSens)

        binding.sbScrollSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = Math.max(0.1f, progress / 10f)
                binding.tvScrollVal.text = String.format("%.1fx", value)
                prefs.edit().putFloat("scroll_sensitivity", value).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun setupPresetChips() {
        binding.chipSensSlow.setOnClickListener { binding.sbSensitivity.progress = 8 }
        binding.chipSensNormal.setOnClickListener { binding.sbSensitivity.progress = 14 }
        binding.chipSensFast.setOnClickListener { binding.sbSensitivity.progress = 20 }

        binding.chipScrollSmooth.setOnClickListener { binding.sbScrollSensitivity.progress = 3 }
        binding.chipScrollStandard.setOnClickListener { binding.sbScrollSensitivity.progress = 5 }
        binding.chipScrollSwift.setOnClickListener { binding.sbScrollSensitivity.progress = 10 }
    }

    private fun updateRootStatus() {
        Thread {
            val rootOk = RootMouseController.isRootAvailable()
            runOnUiThread {
                binding.tvRootStatus.text = if (rootOk) "Granted" else "Denied"
            }
        }.start()
    }

    private fun updateOverlayStatus() {
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }

        if (hasOverlay) {
            binding.tvOverlayStatus.text = "Enabled"
            binding.btnGrantOverlay.visibility = View.GONE
        } else {
            binding.tvOverlayStatus.text = "Missing"
            binding.btnGrantOverlay.visibility = View.VISIBLE
        }
    }

    private fun showInfoDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Virtual Mouse v1.2.0")
            .setMessage("• 1-Finger Hold + 2nd-Finger Drag: Hold 1 finger stationary and drag with a 2nd finger to hold Left Click and drag items/text.\n• 3-Finger Tap: Instantly swap between Trackpad and Touch mode anytime.\n• Auto-Keyboard: Automatically switches to Touch mode when the keyboard opens, and restores Trackpad when done.\n• Hide Overlay: Tap ✕ on the dock or toggle in Home/Settings.")
            .setPositiveButton("Close", null)
            .show()
    }
}
