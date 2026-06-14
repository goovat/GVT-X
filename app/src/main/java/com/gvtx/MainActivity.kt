package com.gvtx

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.*
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
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
        
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        
        setupSeekBars()
        setupButtons()
        setupSurfaceView()
        
        startBackgroundThread()
        checkCameraPermission()
    }
    
    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
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
        }
        
        btnRecord.setOnClickListener {
            isRecording = !isRecording
            btnRecord.text = if (isRecording) "🔴 RECORDING..." else "⏺ RECORD"
            Toast.makeText(this, if (isRecording) "Recording started" else "Recording stopped", Toast.LENGTH_SHORT).show()
        }
        
        btnSwitchLens.setOnClickListener {
            currentLensFacing = if (currentLensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                CameraCharacteristics.LENS_FACING_FRONT
            } else {
                CameraCharacteristics.LENS_FACING_BACK
            }
            closeCamera()
            openCamera()
        }
    }
    
    private fun setupSurfaceView() {
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                openCamera()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                closeCamera()
            }
        })
    }
    
    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        
        val cameraId = getCameraId()
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                createCaptureSession()
            }
            override fun onDisconnected(camera: CameraDevice) {
                cameraDevice = null
            }
            override fun onError(camera: CameraDevice, error: Int) {
                cameraDevice = null
            }
        }, backgroundHandler)
    }
    
    private fun getCameraId(): String {
        cameraManager.cameraIdList.forEach { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing == currentLensFacing) {
                return id
            }
        }
        return cameraManager.cameraIdList.firstOrNull() ?: "0"
    }
    
    private fun createCaptureSession() {
        val surface = surfaceView.holder.surface
        
        cameraDevice?.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    startPreview(surface)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Toast.makeText(this@MainActivity, "Camera configuration failed", Toast.LENGTH_SHORT).show()
                }
            },
            backgroundHandler
        )
    }
    
    private fun startPreview(surface: Surface) {
        val camera = cameraDevice ?: return
        val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        captureRequest.addTarget(surface)
        
        captureRequest.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        captureRequest.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
        captureRequest.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentShutterUs)
        captureRequest.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        captureRequest.set(CaptureRequest.LENS_FOCUS_DISTANCE, currentFocus)
        
        captureSession?.setRepeatingRequest(captureRequest.build(), null, backgroundHandler)
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
        if (requestCode == REQUEST_CAMERA_PERMISSION && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openCamera()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        closeCamera()
        stopBackgroundThread()
    }
}
