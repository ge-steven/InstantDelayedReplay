package com.removeviewfinder.instantdelayedreplay


import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.NumberPicker
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


typealias DelayedPreviewListener = (luma: Bitmap) -> Unit
var portrait : Boolean = true
var lensFacing = CameraSelector.DEFAULT_BACK_CAMERA

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding

    private var imageCapture: ImageCapture? = null

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private lateinit var cameraExecutor: ExecutorService

    private lateinit var binding: ActivityMainBinding

    private val pickerVals = arrayOf("0", "50", "100", "150", "200", "250", "300",
                                    "350", "400", "450", "500", "550", "600")

    var buffersize = 0
    var bitmapBuffer = mutableListOf<Bitmap>()

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
            viewBinding.bufferAmount.setText("Frames in buffer: " + bitmapBuffer.size.toString())
            if (bitmapBuffer.size > 0) {
                viewBinding.imageView.setImageBitmap(bitmapBuffer.first())
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
//        viewBinding.videoCaptureButton.setOnClickListener { captureVideo() }
        viewBinding.bufferSize.setText("Buffer size: 0")
        viewBinding.bufferSize.setOnClickListener { showNumberPickerDialog() }
        viewBinding.changeCamera.setOnClickListener {
            if (lensFacing == CameraSelector.DEFAULT_BACK_CAMERA) {
                lensFacing = CameraSelector.DEFAULT_FRONT_CAMERA
                viewBinding.changeCamera.setText("back camera")
            } else {
                lensFacing = CameraSelector.DEFAULT_BACK_CAMERA
                viewBinding.changeCamera.setText("front camera")
            }
            startCamera()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        runOnUiThread(r)
    }

    fun showNumberPickerDialog() {
        val d = Dialog(this@MainActivity)
        d.setTitle("NumberPicker")
        d.setContentView(R.layout.bufferdialog)
        val b1: Button = d.findViewById(R.id.button1) as Button
        val b2: Button = d.findViewById(R.id.button2) as Button
        val np = d.findViewById(R.id.numberPicker1) as NumberPicker
        np.minValue = 0
        np.maxValue = pickerVals.size - 1
        np.displayedValues = pickerVals
        np.wrapSelectorWheel = false
        np.value = pickerVals.indexOf(buffersize.toString())
        b1.setOnClickListener {
            if (pickerVals[np.value].toInt() < buffersize) {
                bitmapBuffer = mutableListOf<Bitmap>()
            }
            buffersize = pickerVals[np.value].toInt()
            viewBinding.bufferSize.setText("Buffer size: " + pickerVals[np.value])
            d.dismiss()
        }
        b2.setOnClickListener {d.dismiss()}
        d.show()
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
