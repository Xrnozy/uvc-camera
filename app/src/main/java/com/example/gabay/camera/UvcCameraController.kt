package com.example.gabay.camera

import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.example.gabay.R
import com.serenegiant.usb.DeviceFilter
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera
import java.nio.ByteBuffer

class UvcCameraController(
    private val context: Context,
) {
    interface Listener {
        fun onUvcStatusChanged(status: UvcStatus)
        fun onUvcError(message: String)
        fun onDevicesUpdated(devices: List<String>)
    }

    enum class UvcStatus {
        WAITING,
        PERMISSION_NEEDED,
        CONNECTED,
        DISCONNECTED,
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var usbMonitor: USBMonitor? = null
    private var uvcCamera: UVCCamera? = null
    private var previewSurface: Surface? = null
    private var listener: Listener? = null
    val frameSource = UvcFrameSource()

    var frameWidth: Int = UVCCamera.DEFAULT_PREVIEW_WIDTH
        private set
    var frameHeight: Int = UVCCamera.DEFAULT_PREVIEW_HEIGHT
        private set

    val previewAspectRatio: Float
        get() = if (frameHeight > 0) frameWidth.toFloat() / frameHeight else 16f / 9f

    private val frameCallback = IFrameCallback { frame: ByteBuffer ->
        val size = frameWidth * frameHeight * 3 / 2
        if (frame.remaining() < frameWidth * frameHeight) {
            frameSource.deliverRgbx(frame, frameWidth, frameHeight, System.nanoTime())
            return@IFrameCallback
        }
        val data = ByteArray(size)
        frame.rewind()
        val toCopy = minOf(size, frame.remaining())
        frame.get(data, 0, toCopy)
        frameSource.deliverNv21(data, frameWidth, frameHeight, System.nanoTime())
    }

    private val deviceListener = object : USBMonitor.OnDeviceConnectListener {
        override fun onAttach(device: UsbDevice) {
            Log.d(TAG, "UVC attached: ${device.deviceName}")
            publishDevices()
            listener?.onUvcStatusChanged(UvcStatus.PERMISSION_NEEDED)
            usbMonitor?.requestPermission(device)
        }

        override fun onConnect(
            device: UsbDevice,
            ctrlBlock: USBMonitor.UsbControlBlock,
            createNew: Boolean,
        ) {
            Log.d(TAG, "UVC permission granted: ${device.deviceName}")
            openCamera(ctrlBlock)
        }

        override fun onDisconnect(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock) {
            Log.d(TAG, "UVC disconnected: ${device.deviceName}")
            closeCameraOnly()
            listener?.onUvcStatusChanged(UvcStatus.DISCONNECTED)
        }

        override fun onDettach(device: UsbDevice) {
            Log.d(TAG, "UVC detached: ${device.deviceName}")
            closeCameraOnly()
            publishDevices()
            listener?.onUvcStatusChanged(UvcStatus.WAITING)
        }

        override fun onCancel(device: UsbDevice) {
            Log.d(TAG, "UVC permission cancelled: ${device.deviceName}")
            listener?.onUvcStatusChanged(UvcStatus.PERMISSION_NEEDED)
        }
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
        if (listener != null) {
            publishDevices()
        }
    }

    fun start() {
        if (usbMonitor != null) return
        usbMonitor = USBMonitor(context, deviceListener).apply {
            val filters = DeviceFilter.getDeviceFilters(context, R.xml.device_filter)
            if (filters.isNotEmpty()) {
                setDeviceFilter(filters)
            }
            register()
        }
        listener?.onUvcStatusChanged(UvcStatus.WAITING)
        publishDevices()
        mainHandler.postDelayed({ requestPermissionForFirstDevice() }, 500)
    }

    fun stop() {
        closeCameraOnly()
        usbMonitor?.unregister()
        usbMonitor?.destroy()
        usbMonitor = null
        frameSource.shutdown()
    }

    fun handleUsbIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            ?: intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        if (device != null) {
            publishDevices()
            listener?.onUvcStatusChanged(UvcStatus.PERMISSION_NEEDED)
            usbMonitor?.requestPermission(device)
        } else {
            requestPermissionForFirstDevice()
        }
    }

    fun requestPermissionForFirstDevice() {
        val monitor = usbMonitor ?: return
        val target = pickTargetDevice(monitor) ?: run {
            publishDevices()
            listener?.onUvcStatusChanged(UvcStatus.WAITING)
            return
        }
        listener?.onUvcStatusChanged(UvcStatus.PERMISSION_NEEDED)
        monitor.requestPermission(target)
    }

    fun bindPreview(surfaceTexture: SurfaceTexture) {
        previewSurface?.release()
        previewSurface = Surface(surfaceTexture)
        uvcCamera?.let { startPreview(it) }
    }

    fun unbindPreview() {
        uvcCamera?.stopPreview()
        previewSurface?.release()
        previewSurface = null
    }

    fun listUsbDevices(): List<String> = buildDeviceLines(includePermission = true)

    private fun pickTargetDevice(monitor: USBMonitor): UsbDevice? {
        val filtered = monitor.deviceList.orEmpty()
        if (filtered.isNotEmpty()) return filtered.first()
        return allUsbDevices().firstOrNull(::isLikelyUvcDevice)
    }

    private fun allUsbDevices(): List<UsbDevice> {
        return usbManager.deviceList?.values?.toList() ?: emptyList()
    }

    private fun isLikelyUvcDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
        return (0 until device.interfaceCount).any { index ->
            device.getInterface(index).interfaceClass == UsbConstants.USB_CLASS_VIDEO
        }
    }

    private fun buildDeviceLines(includePermission: Boolean): List<String> {
        return allUsbDevices().map { device ->
            val uvc = if (isLikelyUvcDevice(device)) "UVC" else "USB"
            val permitted = if (includePermission && usbManager.hasPermission(device)) "granted" else "no-permission"
            "$uvc ${device.deviceName} (vid=${device.vendorId}, pid=${device.productId}, $permitted)"
        }
    }

    private fun publishDevices() {
        listener?.onDevicesUpdated(buildDeviceLines(includePermission = true))
    }

    private fun openCamera(ctrlBlock: USBMonitor.UsbControlBlock) {
        mainHandler.post {
            closeCameraOnly()
            try {
                val camera = UVCCamera()
                camera.open(ctrlBlock)
                setPreviewSize(camera)
                uvcCamera = camera
                listener?.onUvcStatusChanged(UvcStatus.CONNECTED)
                previewSurface?.let { startPreview(camera) }
                publishDevices()
            } catch (error: Exception) {
                Log.e(TAG, "Failed to open UVC camera", error)
                listener?.onUvcError(error.message ?: "Failed to open USB camera")
                listener?.onUvcStatusChanged(UvcStatus.PERMISSION_NEEDED)
            }
        }
    }

    private fun startPreview(camera: UVCCamera) {
        val surface = previewSurface ?: return
        try {
            camera.setPreviewDisplay(surface)
            camera.startPreview()
            startFrameCallback(camera)
        } catch (error: Exception) {
            Log.e(TAG, "Failed to start UVC preview", error)
            listener?.onUvcError(error.message ?: "Failed to start USB preview")
        }
    }

    private fun startFrameCallback(camera: UVCCamera) {
        try {
            camera.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_YUV420SP)
        } catch (error: Exception) {
            Log.w(TAG, "NV21 frame callback failed, trying RGBX", error)
            try {
                camera.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_RGBX)
            } catch (fallback: Exception) {
                Log.e(TAG, "Frame callback unavailable", fallback)
            }
        }
    }

    private fun setPreviewSize(camera: UVCCamera) {
        val candidates = listOf(
            Triple(1280, 720, UVCCamera.FRAME_FORMAT_MJPEG),
            Triple(960, 540, UVCCamera.FRAME_FORMAT_MJPEG),
            Triple(848, 480, UVCCamera.FRAME_FORMAT_MJPEG),
            Triple(640, 480, UVCCamera.FRAME_FORMAT_MJPEG),
        )
        for ((w, h, fmt) in candidates) {
            try {
                camera.setPreviewSize(w, h, fmt)
                frameWidth = w
                frameHeight = h
                Log.i(TAG, "UVC preview: ${w}x$h MJPEG")
                return
            } catch (_: Exception) {
                // try next
            }
        }
        try {
            camera.setPreviewSize(
                UVCCamera.DEFAULT_PREVIEW_WIDTH,
                UVCCamera.DEFAULT_PREVIEW_HEIGHT,
                UVCCamera.DEFAULT_PREVIEW_MODE,
            )
            frameWidth = UVCCamera.DEFAULT_PREVIEW_WIDTH
            frameHeight = UVCCamera.DEFAULT_PREVIEW_HEIGHT
        } catch (error: Exception) {
            Log.e(TAG, "Failed to set preview size", error)
        }
    }

    private fun closeCameraOnly() {
        uvcCamera?.let { camera ->
            runCatching {
                camera.setFrameCallback(null, 0)
                camera.stopPreview()
                camera.destroy()
            }
        }
        uvcCamera = null
    }

    companion object {
        private const val TAG = "UvcCameraController"
    }
}
