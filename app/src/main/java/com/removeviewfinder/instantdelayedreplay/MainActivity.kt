package com.removeviewfinder.instantdelayedreplay

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.removeviewfinder.instantdelayedreplay.databinding.ActivityMainBinding
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


import android.content.Context
import android.content.res.Configuration


typealias DelayedPreviewListener = (luma: Bitmap) -> Unit
var portrait : Boolean = true

class DelayedPreview(private val listener: DelayedPreviewListener) : ImageAnalysis.Analyzer {

    private fun ByteBuffer.toByteArray(): ByteArray {
        rewind()    // Rewind the buffer to zero
        val data = ByteArray(remaining())
        get(data)   // Copy the buffer into a byte array
        return data // Return the byte array
    }

    override fun analyze(image: ImageProxy) {
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

        val matrix = Matrix()
        matrix.postRotate(90F)

        if (portrait){
                bitmap = Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.getWidth(),
                bitmap.getHeight(),
                matrix,
                true
            )
        }

        listener(bitmap)
        image.close()
    }
}

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding

    private var imageCapture: ImageCapture? = null

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private lateinit var cameraExecutor: ExecutorService

    private lateinit var binding: ActivityMainBinding

    private val pickerVals = arrayOf("0", "50", "100", "150", "200", "250")

    var buffersize = 0
    var bufferid = 0

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    var currentBitmap = mutableListOf<Bitmap>()

    val r: Runnable = object : Runnable {
        // extension property to get screen orientation
        val Context.orientation:String
            get() {
                return when(resources.configuration.orientation){
                    Configuration.ORIENTATION_PORTRAIT -> "Portrait"
                    Configuration.ORIENTATION_LANDSCAPE -> "Landscape"
                    Configuration.ORIENTATION_UNDEFINED -> "Undefined"
                    else -> "Error"
                }
            }

        override fun run() {
            when (orientation) {
                "Portrait" -> portrait = true
                "Landscape" -> portrait = false
                "Undefined" -> portrait = false
            }
            viewBinding.bufferAmount.setText("Frames in buffer: " + currentBitmap.size.toString())
            if (currentBitmap.size > 0) {
                viewBinding.imageView.setImageBitmap(currentBitmap.first())
            }
            viewBinding.imageView.postDelayed(this, 10)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        // Set up the listeners for take photo and video capture buttons\
        viewBinding.videoCaptureButton.setOnClickListener { captureVideo() }
        viewBinding.btnMore.setOnClickListener { increaseBuffer() }
        viewBinding.btnLess.setOnClickListener { decreaseBuffer() }
        viewBinding.bufferSize.setText("0")
        cameraExecutor = Executors.newSingleThreadExecutor()

        runOnUiThread(r)
    }

    fun increaseBuffer() {
        if (bufferid < pickerVals.size-1) {
            bufferid += 1
            viewBinding.bufferSize.setText(pickerVals[bufferid])
            buffersize = pickerVals[bufferid].toInt()
        }
    }

    fun decreaseBuffer() {
        if (bufferid > 0) {
            bufferid -= 1
            viewBinding.bufferSize.setText(pickerVals[bufferid])
            buffersize = pickerVals[bufferid].toInt()
//            currentBitmap = currentBitmap.subList(currentBitmap.size-pickerVals[bufferid].toInt(), currentBitmap.size)
            currentBitmap = mutableListOf<Bitmap>()
        }
    }

    fun setupImageView(bitmap: Bitmap) {
        viewBinding.imageView.setImageBitmap(bitmap)
    }


    private fun captureVideo() {}

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder().build()

            val imageAnalyzer = ImageAnalysis.Builder()
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, DelayedPreview { luma ->
                        if (currentBitmap.size > buffersize) { if (currentBitmap.size > 1) {currentBitmap.removeAt(0)}}
                        currentBitmap.add(luma)
                        Log.d(TAG, requestedOrientation.toString())
                    })
                }

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, imageAnalyzer)


            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }


    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }
}
