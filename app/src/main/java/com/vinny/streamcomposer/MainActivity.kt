package com.vinny.streamcomposer

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.util.UnstableApi
import com.vinny.streamcomposer.ui.AppViewModel
import com.vinny.streamcomposer.ui.BatteryGate
import com.vinny.streamcomposer.ui.MainApp
import com.vinny.streamcomposer.ui.StreamTheme

@UnstableApi
class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private var batteryOk by mutableStateOf(false)
    private var skipped by mutableStateOf(false)

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            StreamTheme {
                if (batteryOk || skipped) MainApp(vm)
                else BatteryGate(onRequest = ::requestIgnoreBattery, onSkip = { skipped = true })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        batteryOk = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            // một số ROM chặn hộp thoại trực tiếp -> mở danh sách cài đặt
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
