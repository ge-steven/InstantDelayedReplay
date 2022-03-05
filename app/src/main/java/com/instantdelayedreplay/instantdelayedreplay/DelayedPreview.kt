package com.instantdelayedreplay.instantdelayedreplay

import android.graphics.*
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

class DelayedPreview(private val listener: DelayedPreviewListener) : ImageAnalysis.Analyzer {

    // Convert camera image data to bitmap
    override fun analyze(image: ImageProxy) {
        // To bitmap conversion
        val yBuffer = image.planes[0].buffer // Y
        val vuBuffer = image.planes[2].buffer // VU

        val ySize = yBuffer.remaining()
        val vuSize = vuBuffer.remaining()

        val nv21 = ByteArray(ySize + vuSize)

        yBuffer.get(nv21, 0, ySize)
        vuBuffer.get(nv21, ySize, vuSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 50, out)
        val imageBytes = out.toByteArray()
        var bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

        // Rotate bitmap to correct orientation for viewfinder
        val matrix = Matrix()
        if (portrait){
            matrix.postRotate(90F)
            if (lensFacing == CameraSelector.DEFAULT_FRONT_CAMERA) {
                matrix.postRotate(180F)
            }
        }

        // Apply rotation
        bitmap = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.getWidth(),
            bitmap.getHeight(),
            matrix,
            true
        )

        listener(bitmap)
        image.close()
    }
}