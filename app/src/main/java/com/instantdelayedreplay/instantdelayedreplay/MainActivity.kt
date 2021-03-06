package com.instantdelayedreplay.instantdelayedreplay


import android.Manifest
import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.NumberPicker
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.instantdelayedreplay.instantdelayedreplay.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

// DelayedPreviewListener return type
typealias DelayedPreviewListener = (bitmap: Bitmap) -> Unit

// Phone in portrait or landscape
var portrait : Boolean = true
// Choose front or back camera
var lensFacing = CameraSelector.DEFAULT_BACK_CAMERA

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding

    // Camera variables
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private lateinit var cameraExecutor: ExecutorService

    // Buffer of bitmaps for delayed viewfinder
    var buffersize = 500
    var bufferId = buffersize-1
    var bitmapBuffer = mutableListOf<Bitmap>()

    // Permission request result
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

    // Viewfinder update thread
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
            try {
                viewBinding.bufferAmount.setText("Buffer size: " + bitmapBuffer.size.toString())
                // Set viewfinder to bitmap according to slider
                bufferId = max(0, bitmapBuffer.size - viewBinding.bufferSize.value.toInt() - 1)
                if (bitmapBuffer.size > 0) {
                    viewBinding.imageView.setImageBitmap(bitmapBuffer[bufferId])
                }
            }
            catch (exc : Exception) {
                // Drop frame when changing the buffer size causes a ConcurrentModificationException
                Log.e(TAG, "Bitmap setting exception: ", exc)
            }
            viewBinding.imageView.postDelayed(this, 10)
        }
    }

    // Initialization
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

        // Set up the listeners for take photo and video capture buttons
        viewBinding.changeCamera.setOnClickListener {
            if (lensFacing == CameraSelector.DEFAULT_BACK_CAMERA) {
                lensFacing = CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                lensFacing = CameraSelector.DEFAULT_BACK_CAMERA
            }
            startCamera()
        }
        viewBinding.videoCaptureButton.setOnClickListener { captureVideo() }

        cameraExecutor = Executors.newSingleThreadExecutor()
        runOnUiThread(r)
    }

    // Implements VideoCapture use case, including start and stop capturing.
    private fun captureVideo() {
        val videoCapture = this.videoCapture ?: return

        viewBinding.videoCaptureButton.isEnabled = false

        val curRecording = recording
        if (curRecording != null) {
            // Stop the current recording session.
            curRecording.stop()
            recording = null
            return
        }

        // create and start a new recording session
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video")
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()
        recording = videoCapture.output
            .prepareRecording(this, mediaStoreOutputOptions)
            .apply {
                if (PermissionChecker.checkSelfPermission(this@MainActivity,
                        Manifest.permission.RECORD_AUDIO) ==
                    PermissionChecker.PERMISSION_GRANTED)
                {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when(recordEvent) {
                    is VideoRecordEvent.Start -> {
                        viewBinding.videoCaptureButton.apply {
                            text = getString(R.string.stop_capture)
                            isEnabled = true
                        }
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            val msg = "Video capture succeeded: " +
                                    "${recordEvent.outputResults.outputUri}"
                            Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT)
                                .show()
                            Log.d(TAG, msg)
                        } else {
                            recording?.close()
                            recording = null
                            Log.e(TAG, "Video capture ends with error: " +
                                    "${recordEvent.error}")
                        }
                        viewBinding.videoCaptureButton.apply {
                            text = getString(R.string.start_capture)
                            isEnabled = true
                        }
                    }
                }
            }
    }

    // Initialize camera
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
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST,
                    FallbackStrategy.higherQualityOrLowerThan(Quality.SD)))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            val imageAnalyzer = ImageAnalysis.Builder()
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, DelayedPreview { luma ->
                        if (bitmapBuffer.size > buffersize) { if (bitmapBuffer.size > 1) {bitmapBuffer.removeAt(0)}}
                        bitmapBuffer.add(luma)
                        Log.d(TAG, requestedOrientation.toString())
                    })
                }

            // Select back camera as a default
            val cameraSelector = lensFacing

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                try {
                    viewBinding.videoCaptureButton.isEnabled = true
                    viewBinding.videoCaptureButton.setText("Start Video Capture")
                    cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, imageAnalyzer, videoCapture
                    )
                } catch (exc: Exception) {
                    viewBinding.videoCaptureButton.isEnabled = false
                    viewBinding.videoCaptureButton.setText("Analysis and recording not supported")
                    cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, imageAnalyzer)
                }

            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    // Get permissions
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    // Clean up on exit
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
