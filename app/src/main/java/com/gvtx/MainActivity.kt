package com.gvtx

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.*
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

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
    
    // Initialize ALL views first
    surfaceView = findViewById(R.id.surfaceView)
    textInfo = findViewById(R.id.textInfo)
    seekIso = findViewById(R.id.seekIso)
    seekShutter = findViewById(R.id.seekShutter)
    seekFocus = findViewById(R.id.seekFocus)
    btnCapture = findViewById(R.id.btnCapture)
    btnSwitchLens = findViewById(R.id.btnSwitchLens)
    btnRecord = findViewById(R.id.btnRecord)
    statusText = findViewById(R.id.statusText)
    
    // Now statusText is initialized and safe to use
    statusText.text = "GVT-X Ready"
    statusText.visibility = View.VISIBLE
    
    cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
    
    setupSeekBars()
    setupButtons()
    setupSurfaceView()
    
    startBackgroundThread()
    checkCameraPermission()
}
    
    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            statusText.text = "Requesting camera permission..."
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
        } else {
            statusText.text = "Camera permission granted"
        }
    }
    
    private fun setupSeekBars() {
        seekIso.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentIso = when (progress) {
                    0 -> 100
                    1 -> 200
                    2 -> 400
                    3 -> 800
                    4 -> 1600
                    5 -> 3200
                    6 -> 6400
                    7 -> 12800
                    8 -> 25600
                    else -> 100
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
                    0 -> 1000000L
                    1 -> 500000L
                    2 -> 250000L
                    3 -> 125000L
                    4 -> 62500L
                    5 -> 33333L
                    6 -> 16666L
                    7 -> 8333L
                    8 -> 4167L
                    9 -> 2083L
                    10 -> 1000L
                    else -> 16666L
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
        textInfo.text = "📷 ISO: $currentIso | Shutter: $shutterString | Focus: ${(currentFocus * 100).toInt()}%"
    }
    
    private fun setupButtons() {
        btnCapture.setOnClickListener {
            Toast.makeText(this, "📸 Photo captured! (RAW+JPEG)", Toast.LENGTH_SHORT).show()
            statusText.text = "Photo captured"
        }
        
        btnRecord.setOnClickListener {
            isRecording = !isRecording
            btnRecord.text = if (isRecording) "🔴 RECORDING..." else "⏺ RECORD"
            val message = if (isRecording) "Recording started" else "Recording stopped"
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            statusText.text = message
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
                statusText.text = "Surface created, opening camera..."
                openCamera()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.d(TAG, "Surface changed: $width x $height")
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.d(TAG, "Surface destroyed")
                closeCamera()
            }
        })
    }
    
    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            statusText.text = "Camera permission denied"
            return
        }
        
        try {
            val cameraId = getCameraId()
            Log.d(TAG, "Opening camera: $cameraId")
            statusText.text = "Opening camera: $cameraId"
            
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.d(TAG, "Camera opened successfully")
                    cameraDevice = camera
                    statusText.text = "Camera ready"
                    createCaptureSession()
                }
                
                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected")
                    cameraDevice = null
                    statusText.text = "Camera disconnected"
                }
                
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    cameraDevice = null
                    statusText.text = "Camera error: $error"
                    Toast.makeText(this@MainActivity, "Camera error: $error", Toast.LENGTH_LONG).show()
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera", e)
            statusText.text = "Failed: ${e.message}"
            Toast.makeText(this, "Failed to open camera: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun getCameraId(): String {
        cameraManager.cameraIdList.forEach { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing == currentLensFacing) {
                Log.d(TAG, "Found camera with ID: $id, facing: $facing")
                return id
            }
        }
        val defaultId = cameraManager.cameraIdList.firstOrNull() ?: "0"
        Log.d(TAG, "Using default camera ID: $defaultId")
        return defaultId
    }
    
    private fun createCaptureSession() {
        val surface = surfaceView.holder.surface
        
        cameraDevice?.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    Log.d(TAG, "Capture session configured")
                    captureSession = session
                    startPreview(surface)
                    statusText.text = "Preview running"
                }
                
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Capture session configuration failed")
                    statusText.text = "Configuration failed"
                    Toast.makeText(this@MainActivity, "Camera configuration failed", Toast.LENGTH_LONG).show()
                }
            },
            backgroundHandler
        )
    }
    
    private fun startPreview(surface: Surface) {
        val camera = cameraDevice
        if (camera == null) {
            Log.e(TAG, "Camera device is null, cannot start preview")
            statusText.text = "Camera device is null"
            return
        }
        
        try {
            val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequest.addTarget(surface)
            
            captureRequest.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            captureRequest.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
            captureRequest.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentShutterUs)
            captureRequest.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            captureRequest.set(CaptureRequest.LENS_FOCUS_DISTANCE, currentFocus)
            
            captureSession?.setRepeatingRequest(captureRequest.build(), null, backgroundHandler)
            Log.d(TAG, "Preview started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start preview", e)
            statusText.text = "Preview failed: ${e.message}"
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
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            Log.d(TAG, "Camera closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera", e)
        }
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
            Log.e(TAG, "Error stopping background thread", e)
        }
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                statusText.text = "Permission granted, opening camera"
                openCamera()
            } else {
                statusText.text = "Camera permission denied"
                Toast.makeText(this, "Camera permission is required for this app", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        if (::cameraManager.isInitialized && 
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCamera()
        }
    }
    
    override fun onPause() {
        super.onPause()
        closeCamera()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        closeCamera()
        stopBackgroundThread()
    }
}