package com.example.gabay.camera

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.gabay.R
import kotlinx.coroutines.delay

@Composable
fun UvcCameraScreen(launchUsbIntent: Intent? = null) {
    val context = LocalContext.current
    val uvcController = remember { UvcCameraController(context) }

    var usbRefreshKey by remember { mutableIntStateOf(0) }
    var uvcDevices by remember { mutableStateOf<List<String>>(emptyList()) }
    var uvcStatus by remember { mutableStateOf(UvcCameraController.UvcStatus.WAITING) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showDebug by remember { mutableStateOf(false) }
    var streamFps by remember { mutableStateOf(0f) }
    var frameSize by remember { mutableStateOf("—") }

    LaunchedEffect(launchUsbIntent, usbRefreshKey) {
        uvcController.handleUsbIntent(launchUsbIntent)
    }

    LaunchedEffect(uvcStatus) {
        while (uvcStatus == UvcCameraController.UvcStatus.CONNECTED) {
            val fps = uvcController.frameSource.lastFps
            if (fps >= 0f) streamFps = fps
            val w = uvcController.frameSource.lastFrameWidth
            val h = uvcController.frameSource.lastFrameHeight
            if (w > 0 && h > 0) frameSize = "${w}x$h"
            delay(500)
        }
    }

    DisposableEffect(uvcController) {
        val listener = object : UvcCameraController.Listener {
            override fun onUvcStatusChanged(status: UvcCameraController.UvcStatus) {
                uvcStatus = status
                if (status == UvcCameraController.UvcStatus.CONNECTED) {
                    errorMessage = null
                }
            }

            override fun onUvcError(message: String) {
                errorMessage = message
            }

            override fun onDevicesUpdated(devices: List<String>) {
                uvcDevices = devices
            }
        }

        uvcController.setListener(listener)
        uvcController.start()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != UsbCameraReceiver.ACTION_USB_CAMERA_ATTACHED) return
                Toast.makeText(ctx, ctx.getString(R.string.usb_camera_detected), Toast.LENGTH_SHORT).show()
                uvcController.handleUsbIntent(intent)
                usbRefreshKey++
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(UsbCameraReceiver.ACTION_USB_CAMERA_ATTACHED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        onDispose {
            uvcController.setListener(null)
            uvcController.stop()
            context.unregisterReceiver(receiver)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.uvc_status_label, uvcStatus.name),
            style = MaterialTheme.typography.titleMedium,
        )

        if (errorMessage != null) {
            Text(
                text = errorMessage!!,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (uvcStatus == UvcCameraController.UvcStatus.WAITING ||
            uvcStatus == UvcCameraController.UvcStatus.PERMISSION_NEEDED
        ) {
            Text(stringResource(R.string.uvc_waiting))
            Button(onClick = { uvcController.requestPermissionForFirstDevice() }) {
                Text(stringResource(R.string.uvc_permission_needed))
            }
        }

        UvcCameraPreview(
            controller = uvcController,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )

        if (uvcStatus == UvcCameraController.UvcStatus.CONNECTED && streamFps > 0f) {
            Text(
                text = stringResource(R.string.uvc_stream_fps, streamFps, frameSize),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        TextButton(onClick = { showDebug = !showDebug }) {
            Text(
                if (showDebug) stringResource(R.string.hide_debug)
                else stringResource(R.string.show_debug)
            )
        }

        if (showDebug) {
            Text("USB devices: ${uvcDevices.size}")
            uvcDevices.forEach { device ->
                Text(device, style = MaterialTheme.typography.bodySmall)
            }
            Text("Preview: ${uvcController.frameWidth}x${uvcController.frameHeight}")
        }
    }
}
