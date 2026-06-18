package com.gvtx

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.TextureView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var textureView: TextureView
    private lateinit var statusText: TextView
    private lateinit var btnCapture: Button
    private lateinit var btnSwitchLens: Button
    private lateinit var btnSettings: TextView

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private lateinit var cameraManager: CameraManager
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null

    private var currentLensFacing = CameraCharacteristics.LENS_FACING_BACK

    companion object {
        private const val REQUEST_CAMERA_PERMISSION = 200
        private const val TAG = "GVTX"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize views
        textureView = findViewById(R.id.textureView)
        statusText = findViewById(R.id.statusText)
        btnCapture = findViewById(R.id.btnCapture)
        btnSwitchLens = findViewById(R.id.btnSwitchLens)
        btnSettings = findViewById(R.id.btnSettings)

        statusText.text = "GVT-X Ready"

        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager

        setupButtons()
        setupTextureView()
        startBackgroundThread()
        checkPermissions()
    }

    private fun setupButtons() {
        btnCapture.setOnClickListener {
            statusText.text = "Photo captured!"
            Toast.makeText(this, "Photo captured!", Toast.LENGTH_SHORT).show()
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

        btnSettings.setOnClickListener {
            Toast.makeText(this, "Settings - Coming Soon", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupTextureView() {
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                openCamera()
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                closeCamera()
                return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
        }
    }

    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            statusText.text = "No camera permission"
            return
        }

        try {
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
                    statusText.text = "Camera error: $error"
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            statusText.text = "Failed: ${e.message}"
        }
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
        val texture = textureView.surfaceTexture
        val surface = android.view.Surface(texture)

        cameraDevice?.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    startPreview(surface)
                    statusText.text = "Preview running"
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    statusText.text = "Configuration failed"
                }
            },
            backgroundHandler
        )
    }

    private fun startPreview(surface: android.view.Surface) {
        val camera = cameraDevice ?: return
        val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        captureRequest.addTarget(surface)
        captureRequest.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        captureRequest.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        captureRequest.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
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
