package com.virtualmouse.app

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.virtualmouse.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isServiceActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        setupListeners()
        setupCalibrationSliders(prefs)
        setupPresetChips()

        RootMouseController.onStateChanged = { state, message ->
            runOnUiThread {
                handleDriverState(state, message)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateRootStatus()
        updateOverlayStatus()
        syncServiceState()
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
                    Toast.makeText(this, "Virtual Mouse Active! Cursor visible.", Toast.LENGTH_SHORT).show()
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
            binding.btnStartStop.text = "Initializing Driver..."
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
                binding.tvLaunchStatus.text = "Virtual mouse driver running"
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
            binding.btnStartStop.setBackgroundColor(0xFFEF4444.toInt())
            binding.chipServiceStatus.text = "RUNNING"
            binding.chipServiceStatus.setChipBackgroundColorResource(android.R.color.holo_green_dark)
            binding.tvLaunchStatus.text = "Virtual mouse hardware pointer active"
        } else {
            binding.btnStartStop.text = "Start Virtual Mouse"
            binding.btnStartStop.setBackgroundColor(0xFF10B981.toInt())
            binding.chipServiceStatus.text = "STANDBY"
            binding.chipServiceStatus.setChipBackgroundColorResource(android.R.color.darker_gray)
            binding.tvLaunchStatus.text = "Ready to launch"
        }
    }

    private fun setupCalibrationSliders(prefs: android.content.SharedPreferences) {
        val isTablet = resources.configuration.smallestScreenWidthDp >= 600
        val defaultSens = if (isTablet) 1.6f else 1.3f

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
        binding.chipSensSlow.setOnClickListener {
            binding.sbSensitivity.progress = 8
        }
        binding.chipSensNormal.setOnClickListener {
            binding.sbSensitivity.progress = 13
        }
        binding.chipSensFast.setOnClickListener {
            binding.sbSensitivity.progress = 20
        }

        binding.chipScrollSmooth.setOnClickListener {
            binding.sbScrollSensitivity.progress = 3
        }
        binding.chipScrollStandard.setOnClickListener {
            binding.sbScrollSensitivity.progress = 5
        }
        binding.chipScrollSwift.setOnClickListener {
            binding.sbScrollSensitivity.progress = 10
        }
    }

    private fun updateRootStatus() {
        Thread {
            val rootOk = RootMouseController.isRootAvailable()
            runOnUiThread {
                if (rootOk) {
                    binding.tvRootStatus.text = "GRANTED"
                    binding.tvRootStatus.setTextColor(0xFF10B981.toInt())
                } else {
                    binding.tvRootStatus.text = "NOT FOUND"
                    binding.tvRootStatus.setTextColor(0xFFEF4444.toInt())
                }
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
            binding.tvOverlayStatus.text = "ENABLED"
            binding.tvOverlayStatus.setTextColor(0xFF10B981.toInt())
            binding.btnGrantOverlay.visibility = View.GONE
        } else {
            binding.tvOverlayStatus.text = "ACTION REQUIRED"
            binding.tvOverlayStatus.setTextColor(0xFFF59E0B.toInt())
            binding.btnGrantOverlay.visibility = View.VISIBLE
        }
    }

    private fun showInfoDialog() {
        val isTablet = resources.configuration.smallestScreenWidthDp >= 600
        val modeLabel = if (isTablet) "Tablet Multi-Column UI" else "Mobile Compact UI"
        MaterialAlertDialogBuilder(this)
            .setTitle("Virtual Mouse ($modeLabel)")
            .setMessage("Virtual Mouse turns your entire display into a low-latency hardware trackpad using the Linux /dev/uinput kernel subsystem.\n\n• Tap MODE on the floating dock to toggle between Trackpad and regular touchscreen touch at any time.\n• Drag the left handle on the dock to position it anywhere on your screen.")
            .setPositiveButton("Got it", null)
            .show()
    }
}
