package com.example.gabay.camera

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun UvcCameraPreview(
    controller: UvcCameraController,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val aspect = controller.previewAspectRatio

    val textureView = remember {
        TextureView(context).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    surface: SurfaceTexture,
                    width: Int,
                    height: Int,
                ) {
                    controller.bindPreview(surface)
                    applyPreviewTransform(this@apply, width, height, controller)
                }

                override fun onSurfaceTextureSizeChanged(
                    surface: SurfaceTexture,
                    width: Int,
                    height: Int,
                ) {
                    applyPreviewTransform(this@apply, width, height, controller)
                }

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    controller.unbindPreview()
                    return true
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            }
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { textureView },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspect),
            update = {
                applyPreviewTransform(it, it.width, it.height, controller)
            },
        )
    }
}

private fun applyPreviewTransform(textureView: TextureView, viewW: Int, viewH: Int, controller: UvcCameraController) {
    if (viewW == 0 || viewH == 0) return
    val videoW = controller.frameWidth.toFloat().coerceAtLeast(1f)
    val videoH = controller.frameHeight.toFloat().coerceAtLeast(1f)
    val viewAspect = viewW / viewH.toFloat()
    val videoAspect = videoW / videoH

    val matrix = Matrix()
    // Center-crop: fill the view without stretching (correct for horizontal webcam).
    if (viewAspect > videoAspect) {
        val scale = viewAspect / videoAspect
        matrix.setScale(scale, 1f, viewW / 2f, viewH / 2f)
    } else {
        val scale = videoAspect / viewAspect
        matrix.setScale(1f, scale, viewW / 2f, viewH / 2f)
    }
    textureView.setTransform(matrix)
}
