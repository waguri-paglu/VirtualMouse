package com.virtualmouse.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.virtualmouse.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        updateRootStatus()
        updateOverlayStatus()

        // Cursor Sensitivity slider
        val currentSens = prefs.getFloat("sensitivity", 1.3f)
        binding.sbSensitivity.progress = (currentSens * 10).toInt()
        binding.tvSensitivityVal.text = String.format("%.1fx", currentSens)

        binding.sbSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress / 10f
                binding.tvSensitivityVal.text = String.format("%.1fx", value)
                prefs.edit().putFloat("sensitivity", value).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Scroll Sensitivity slider
        val currentScrollSens = prefs.getFloat("scroll_sensitivity", 0.5f)
        binding.sbScrollSensitivity.progress = (currentScrollSens * 10).toInt()
        binding.tvScrollVal.text = String.format("%.1fx", currentScrollSens)

        binding.sbScrollSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress / 10f
                binding.tvScrollVal.text = String.format("%.1fx", value)
                prefs.edit().putFloat("scroll_sensitivity", value).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

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

        binding.btnStartService.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Please grant Overlay Permission first!", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val serviceIntent = Intent(this, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, "Virtual Mouse Started!", Toast.LENGTH_SHORT).show()
        }

        binding.btnStopService.setOnClickListener {
            val serviceIntent = Intent(this, OverlayService::class.java)
            stopService(serviceIntent)
            Toast.makeText(this, "Virtual Mouse Stopped", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        updateRootStatus()
        updateOverlayStatus()
    }

    private fun updateRootStatus() {
        Thread {
            val rootOk = RootMouseController.isRootAvailable()
            runOnUiThread {
                if (rootOk) {
                    binding.tvRootStatus.text = "Root Access: GRANTED"
                    binding.tvRootStatus.setTextColor(0xFF2E7D32.toInt())
                } else {
                    binding.tvRootStatus.text = "Root Access: NOT FOUND / DENIED"
                    binding.tvRootStatus.setTextColor(0xFFC62828.toInt())
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
            binding.tvOverlayStatus.text = "Overlay Permission: GRANTED"
            binding.tvOverlayStatus.setTextColor(0xFF2E7D32.toInt())
            binding.btnGrantOverlay.isEnabled = false
        } else {
            binding.tvOverlayStatus.text = "Overlay Permission: MISSING"
            binding.tvOverlayStatus.setTextColor(0xFFC62828.toInt())
            binding.btnGrantOverlay.isEnabled = true
        }
    }
}
