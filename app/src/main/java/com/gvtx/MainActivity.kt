package com.gvtx

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    
    private lateinit var surfaceView: SurfaceView
    private lateinit var textInfo: TextView
    private lateinit var seekIso: SeekBar
    private lateinit var seekShutter: SeekBar
    private lateinit var seekFocus: SeekBar
    private lateinit var btnCapture: Button
    private lateinit var btnSwitchLens: Button
    private lateinit var btnRecord: Button
    private lateinit var statusText: TextView
    
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private lateinit var cameraManager: CameraManager
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    
    private var currentLensFacing = CameraCharacteristics.LENS_FACING_BACK
    private var currentIso = 100
    private var currentShutterUs = 16666L
    private var currentFocus = 0f
    private var isRecording = false
    
    companion object {
        private const val REQUEST_CAMERA_PERMISSION = 200
        private const val TAG = "GVTX"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        surfaceView = findViewById(R.id.surfaceView)
        textInfo = findViewById(R.id.textInfo)
        seekIso = findViewById(R.id.seekIso)
        seekShutter = findViewById(R.id.seekShutter)
        seekFocus = findViewById(R.id.seekFocus)
        btnCapture = findViewById(R.id.btnCapture)
        btnSwitchLens = findViewById(R.id.btnSwitchLens)
        btnRecord = findViewById(R.id.btnRecord)
        statusText = findViewById(R.id.statusText)
        
        statusText.text = "GVT-X Ready"
        
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        
        setupSeekBars()
        setupButtons()
        setupSurfaceView()
        
        startBackgroundThread()
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
        }
    }
    
    private fun setupSeekBars() {
        seekIso.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentIso = when (progress) {
                    0 -> 100; 1 -> 200; 2 -> 400; 3 -> 800
                    4 -> 1600; 5 -> 3200; 6 -> 6400; 7 -> 12800
                    8 -> 25600; else -> 100
                }
                updateCameraControls()
                updateInfoText()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        seekShutter.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentShutterUs = when (progress) {
                    0 -> 1000000L; 1 -> 500000L; 2 -> 250000L; 3 -> 125000L
                    4 -> 62500L; 5 -> 33333L; 6 -> 16666L; 7 -> 8333L
                    8 -> 4167L; 9 -> 2083L; 10 -> 1000L; else -> 16666L
                }
                updateCameraControls()
                updateInfoText()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        seekFocus.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentFocus = progress / 100f
                updateCameraControls()
                updateInfoText()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }
    
    private fun updateInfoText() {
        val shutterString = if (currentShutterUs >= 1000000) {
            "${currentShutterUs / 1000000}s"
        } else {
            "1/${1000000 / currentShutterUs}"
        }
        textInfo.text = "ISO: $currentIso | Shutter: $shutterString | Focus: ${(currentFocus * 100).toInt()}%"
    }
    
    private fun setupButtons() {
        btnCapture.setOnClickListener {
            statusText.text = "Capturing photo..."
            takePicture()
        }
        
        btnRecord.setOnClickListener {
            isRecording = !isRecording
            btnRecord.text = if (isRecording) "RECORDING..." else "RECORD"
            statusText.text = if (isRecording) "Recording started" else "Recording stopped"
        }
        
        btnSwitchLens.setOnClickListener {
            currentLensFacing = if (currentLensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                CameraCharacteristics.LENS_FACING_FRONT
            } else {
                CameraCharacteristics.LENS_FACING_BACK
            }
            statusText.text = "Switching lens..."
            closeCamera()
            openCamera()
        }
    }
    
    private fun setupSurfaceView() {
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.d(TAG, "Surface created")
                statusText.text = "Opening camera..."
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    openCamera()
                }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.d(TAG, "Surface changed: $width x $height")
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                closeCamera()
            }
        })
    }
    
    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            statusText.text = "No camera permission"
            return
        }
        
        try {
            val cameraId = getCameraId()
            Log.d(TAG, "Opening camera: $cameraId")
            statusText.text = "Opening camera $cameraId"
            
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.d(TAG, "Camera opened")
                    cameraDevice = camera
                    statusText.text = "Camera ready, creating session..."
                    createCaptureSession()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    cameraDevice = null
                    statusText.text = "Camera disconnected"
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    cameraDevice = null
                    statusText.text = "Camera error: $error"
                    Log.e(TAG, "Camera error: $error")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera", e)
            statusText.text = "Failed: ${e.message}"
        }
    }
    
    private fun getCameraId(): String {
        cameraManager.cameraIdList.forEach { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing == currentLensFacing) {
                Log.d(TAG, "Found camera ID: $id")
                return id
            }
        }
        return cameraManager.cameraIdList.firstOrNull() ?: "0"
    }
    
    private fun createCaptureSession() {
        val surface = surfaceView.holder.surface
        
        // Setup ImageReader for photo capture
        imageReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                saveImage(image)
                image.close()
            }
        }, backgroundHandler)
        
        val targets = mutableListOf<Surface>()
        targets.add(surface)
        imageReader?.surface?.let { targets.add(it) }
        
        cameraDevice?.createCaptureSession(
            targets,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    startPreview(surface)
                    statusText.text = "Preview running"
                    Log.d(TAG, "Capture session configured, preview started")
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    statusText.text = "Configuration failed"
                    Log.e(TAG, "Capture session configuration failed")
                    Toast.makeText(this@MainActivity, "Camera config failed", Toast.LENGTH_SHORT).show()
                }
            },
            backgroundHandler
        )
    }
    
    private fun startPreview(surface: Surface) {
        val camera = cameraDevice ?: return
        val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        captureRequest.addTarget(surface)
        
        // Set manual controls
        captureRequest.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        captureRequest.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
        captureRequest.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentShutterUs)
        captureRequest.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        captureRequest.set(CaptureRequest.LENS_FOCUS_DISTANCE, currentFocus)
        
        captureSession?.setRepeatingRequest(captureRequest.build(), null, backgroundHandler)
        Log.d(TAG, "Preview started with ISO=$currentIso, Shutter=$currentShutterUs")
    }
    
    private fun takePicture() {
        val camera = cameraDevice ?: return
        val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        captureRequest.addTarget(imageReader?.surface!!)
        
        captureRequest.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        captureRequest.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
        captureRequest.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentShutterUs)
        captureRequest.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        captureRequest.set(CaptureRequest.LENS_FOCUS_DISTANCE, currentFocus)
        
        captureSession?.capture(captureRequest.build(), null, backgroundHandler)
        statusText.text = "Photo captured! Saving..."
    }
    
    private fun saveImage(image: android.media.Image) {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "GVTX_$timestamp.jpg"
        
        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GVT-X")
                }
            }
            
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { outputStream ->
                    outputStream.write(bytes)
                    statusText.text = "Photo saved: $filename"
                    Toast.makeText(this, "Photo saved to Gallery!", Toast.LENGTH_LONG).show()
                    Log.d(TAG, "Photo saved: $filename")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save image", e)
            statusText.text = "Failed to save: ${e.message}"
        }
    }
    
    private fun updateCameraControls() {
        val camera = cameraDevice ?: return
        val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        captureRequest.addTarget(surfaceView.holder.surface)
        
        captureRequest.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        captureRequest.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
        captureRequest.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentShutterUs)
        captureRequest.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        captureRequest.set(CaptureRequest.LENS_FOCUS_DISTANCE, currentFocus)
        
        captureSession?.setRepeatingRequest(captureRequest.build(), null, backgroundHandler)
    }
    
    private fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
    }
    
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }
    
    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                statusText.text = "Camera permission granted"
            } else {
                statusText.text = "Camera permission denied"
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        closeCamera()
        stopBackgroundThread()
    }
}
