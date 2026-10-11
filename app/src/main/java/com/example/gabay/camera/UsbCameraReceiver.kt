package com.example.gabay.camera

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.util.Log

class UsbCameraReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        Log.d(TAG, "USB device attached")
        context.sendBroadcast(
            Intent(ACTION_USB_CAMERA_ATTACHED).setPackage(context.packageName)
        )
    }

    companion object {
        private const val TAG = "UsbCameraReceiver"
        const val ACTION_USB_CAMERA_ATTACHED = "com.example.gabay.USB_CAMERA_ATTACHED"
    }
}
