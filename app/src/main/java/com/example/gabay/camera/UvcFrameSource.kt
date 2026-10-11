package com.example.gabay.camera

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import java.nio.ByteBuffer

interface FrameListener {
    fun onFrame(gray: ByteArray, width: Int, height: Int, timestampNs: Long)
}

interface RgbFrameListener {
    fun onRgbFrame(bitmap: Bitmap, width: Int, height: Int, timestampNs: Long)
}

/**
 * Delivers grayscale frames from UVC on a dedicated thread (not main/UI).
 */
class UvcFrameSource {
    private var thread: HandlerThread? = HandlerThread("uvc-frames").apply { start() }
    private var handler: Handler? = thread?.let { Handler(it.looper) }

    private fun postFrameWork(block: () -> Unit) {
        val h = handler ?: run {
            val t = HandlerThread("uvc-frames").apply { start() }
            thread = t
            Handler(t.looper).also { handler = it }
        }
        h.post(block)
    }

    @Volatile
    var listener: FrameListener? = null

    @Volatile
    var rgbListener: RgbFrameListener? = null

    private val fpsCounter = FpsCounter()
    @Volatile
    var lastFps: Float = 0f

    @Volatile
    var lastFrameWidth: Int = 0

    @Volatile
    var lastFrameHeight: Int = 0

    private var grayBuffer: ByteArray? = null
    private var rgbBuffer: IntArray? = null

    fun deliverNv21(nv21: ByteArray, width: Int, height: Int, timestampNs: Long) {
        postFrameWork {
            val ySize = width * height
            if (nv21.size < ySize) return@postFrameWork
            val gray = grayBuffer?.takeIf { it.size == ySize } ?: ByteArray(ySize).also { grayBuffer = it }
            System.arraycopy(nv21, 0, gray, 0, ySize)
            lastFrameWidth = width
            lastFrameHeight = height
            lastFps = fpsCounter.tick()
            listener?.onFrame(gray, width, height, timestampNs)
            rgbListener?.let { rgb ->
                val pixels = nv21ToArgb(nv21, width, height)
                val bmp = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
                rgb.onRgbFrame(bmp, width, height, timestampNs)
            }
        }
    }

    private fun nv21ToArgb(nv21: ByteArray, width: Int, height: Int): IntArray {
        val out = rgbBuffer?.takeIf { it.size == width * height } ?: IntArray(width * height).also { rgbBuffer = it }
        val frameSize = width * height
        var yp = 0
        for (j in 0 until height) {
            var uvp = frameSize + (j shr 1) * width
            var u = 0; var v = 0
            for (i in 0 until width) {
                val y = (nv21[yp].toInt() and 0xFF) - 16
                if ((i and 1) == 0) {
                    v = (nv21[uvp++].toInt() and 0xFF) - 128
                    u = (nv21[uvp++].toInt() and 0xFF) - 128
                }
                val y1192 = 1192 * y
                var r = (y1192 + 1634 * v)
                var g = (y1192 - 833 * v - 400 * u)
                var b = (y1192 + 2066 * u)
                r = r.coerceIn(0, 262143)
                g = g.coerceIn(0, 262143)
                b = b.coerceIn(0, 262143)
                out[yp] = (0xFF000000.toInt()) or ((r shl 6) and 0xFF0000) or ((g shr 2) and 0xFF00) or ((b shr 10) and 0xFF)
                yp++
            }
        }
        return out
    }

    fun deliverRgbx(buffer: ByteBuffer, width: Int, height: Int, timestampNs: Long) {
        postFrameWork {
            val ySize = width * height
            val gray = grayBuffer?.takeIf { it.size == ySize } ?: ByteArray(ySize).also { grayBuffer = it }
            val pixels = rgbBuffer?.takeIf { it.size == ySize } ?: IntArray(ySize).also { rgbBuffer = it }
            buffer.rewind()
            var i = 0
            while (i < ySize && buffer.remaining() >= 4) {
                val r = buffer.get().toInt() and 0xFF
                val g = buffer.get().toInt() and 0xFF
                val b = buffer.get().toInt() and 0xFF
                buffer.get()
                gray[i] = ((r * 77 + g * 150 + b * 29) shr 8).toByte()
                pixels[i] = (0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
                i++
            }
            lastFrameWidth = width
            lastFrameHeight = height
            lastFps = fpsCounter.tick()
            listener?.onFrame(gray, width, height, timestampNs)
            rgbListener?.let { rgb ->
                val bmp = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
                rgb.onRgbFrame(bmp, width, height, timestampNs)
            }
        }
    }

    fun shutdown() {
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        handler = null
        thread = null
    }
}

class FpsCounter {
    private var frameCount = 0
    private var lastTimeNs = System.nanoTime()

    fun tick(): Float {
        frameCount++
        val now = System.nanoTime()
        val elapsed = now - lastTimeNs
        if (elapsed >= 1_000_000_000L) {
            val fps = frameCount * 1_000_000_000f / elapsed
            frameCount = 0
            lastTimeNs = now
            return fps
        }
        return -1f
    }
}
