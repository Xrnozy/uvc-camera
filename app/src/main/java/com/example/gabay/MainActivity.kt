package com.example.gabay

import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.gabay.camera.UvcCameraScreen
import com.example.gabay.ui.theme.GabayTheme

class MainActivity : ComponentActivity() {
    private var usbLaunchIntent by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        usbLaunchIntent = extractUsbIntent(intent)
        setContent {
            GabayTheme {
                UvcCameraScreen(launchUsbIntent = usbLaunchIntent)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        usbLaunchIntent = extractUsbIntent(intent)
    }

    private fun extractUsbIntent(intent: Intent?): Intent? {
        return if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) intent else null
    }
}
