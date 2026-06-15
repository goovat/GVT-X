package com.gvtx

import android.Manifest
import android.app.AlertDialog
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var textureView: TextureView
    private lateinit var btnCapture: Button
    private lateinit var btnRecord: Button
    private lateinit var btnSwitchLens: Button
    private lateinit var btnSettings: Button
    private lateinit var btnGallery: Button
    private lateinit var btnLowLight: Button
    private lateinit var btnHDR: Button
    private lateinit var btnZoom1x: Button
    private lateinit var btnZoom2x: Button
    private lateinit var btnZoom5x: Button
    private lateinit var btnModePhoto: Button
    private lateinit var btnModeVideo: Button
    private lateinit var btnModeAICam: Button
    private lateinit var btnModeBeauty: Button
    private lateinit var statusText: TextView

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var mediaRecorder: MediaRecorder? = null
    private lateinit var cameraManager: CameraManager
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    private var previewSize: Size? = null
    
    private var currentLensFacing = CameraCharacteristics.LENS_FACING_BACK
    private var isRecording = false
    private var isVideoMode = false
    private var currentZoom = 1.0f
    private var videoPath: String = ""

    companion object {
        private const val REQUEST_CAMERA_PERMISSION = 200
        private const val TAG = "GVTX"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.textureView)
        btnCapture = findViewById(R.id.btnCapture)
        btnRecord = findViewById(R.id.btnRecord)
        btnSwitchLens = findViewById(R.id.btnSwitchLens)
        btnSettings = findViewById(R.id.btnSettings)
        btnGallery = findViewById(R.id.btnGallery)
        btnLowLight = findViewById(R.id.btnLowLight)
        btnHDR = findViewById(R.id.btnHDR)
        btnZoom1x = findViewById(R.id.btnZoom1x)
        btnZoom2x = findViewById(R.id.btnZoom2x)
        btnZoom5x = findViewById(R.id.btnZoom5x)
        btnModePhoto = findViewById(R.id.btnModePhoto)
        btnModeVideo = findViewById(R.id.btnModeVideo)
        btnModeAICam = findViewById(R.id.btnModeAICam)
        btnModeBeauty = findViewById(R.id.btnModeBeauty)
        statusText = findViewById(R.id.statusText)

        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        
        setupButtons()
        setupTextureView()
        setupModes()

        startBackgroundThread()
        checkPermissions()
    }

    private fun setupModes() {
        btnModePhoto.setOnClickListener {
            isVideoMode = false
            btnModePhoto.backgroundTintList = androidx.core.content.ContextCompat.getColorStateList(this, androidx.core.R.color.system_accent1_accent1_light)
            btnModeVideo.backgroundTintList = androidx.core.content.ContextCompat.getColorStateList(this, androidx.core.R.color.system_neutral1_1000)
            btnCapture.visibility = Button.VISIBLE
            btnRecord.visibility = Button.GONE
            Toast.makeText(this, "Photo Mode", Toast.LENGTH_SHORT).show()
        }
        
        btnModeVideo.setOnClickListener {
            isVideoMode = true
            btnModeVideo.backgroundTintList = androidx.core.content.ContextCompat.getColorStateList(this, androidx.core.R.color.system_accent1_accent1_light)
            btnModePhoto.backgroundTintList = androidx.core.content.ContextCompat.getColorStateList(this, androidx.core.R.color.system_neutral1_1000)
            btnCapture.visibility = Button.GONE
            btnRecord.visibility = Button.VISIBLE
            Toast.makeText(this, "Video Mode", Toast.LENGTH_SHORT).show()
        }
        
        btnModeAICam.setOnClickListener {
            Toast.makeText(this, "AI Camera - Coming Soon", Toast.LENGTH_SHORT).show()
        }
        
        btnModeBeauty.setOnClickListener {
            Toast.makeText(this, "Beauty Mode - Coming Soon", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupButtons() {
        btnCapture.setOnClickListener {
            if (!isVideoMode) {
                statusText.text = "Capturing photo..."
                takePicture()
            }
        }

        btnRecord.setOnClickListener {
            if (isVideoMode) {
                if (isRecording) {
                    stopRecording()
                } else {
                    startRecording()
                }
            }
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
            showSettingsDialog()
        }
        
        btnGallery.setOnClickListener {
            Toast.makeText(this, "Gallery - Coming Soon", Toast.LENGTH_SHORT).show()
        }
        
        btnLowLight.setOnClickListener {
            btnLowLight.backgroundTintList = androidx.core.content.ContextCompat.getColorStateList(this, androidx.core.R.color.system_accent1_accent1_light)
            btnHDR.backgroundTintList = androidx.core.content.ContextCompat.getColorStateList(this, androidx.core.R.color.system_neutral1_1000)
            Toast.makeText(this, "Low-Light Mode", Toast.LENGTH_SHORT).show()
        }
        
        btnHDR.setOnClickListener {
            btnHDR.backgroundTintList = androidx.core.content.ContextCompat.getColorStateList(this, androidx.core.R.color.system_accent1_accent1_light)
            btnLowLight.backgroundTintList = androidx.core.content.ContextCompat.getColorStateList(this, androidx.core.R.color.system_neutral1_1000)
            Toast.makeText(this, "HDR Mode", Toast.LENGTH_SHORT).show()
        }
        
        btnZoom1x.setOnClickListener {
            currentZoom = 1.0f
            btnZoom1x.backgroundTintList = androidx.core.content.ContextCompat.getColorStateList(this, androidx.core.R.color.system_accent1_accent1_light)
            btnZoom2x.backgroundTintList = androidx.core.content.ContextCompat.getColorStateList(this, androidx.core.R.color.system_neutral1_1000)
            btnZoom5x.backgroundTintList = androidx.core.content.ContextCompat.getColorStateList(this, androidx.core.R.color.system_neutral1_1000)
            Toast.makeText(this, "1x Zoom", Toast.LENGTH_SHORT).show()
        }
        
        btnZoom2x.setOnClickListener {
            currentZoom = 2.0f
            btnZoom2x.backgroundTintList = androidx.core.content.ContextCompat.getColorStateList(this, androidx.core.R.color.system_accent1_accent1_light)
            btnZoom1x.backgroundTintList = androidx.core.content.ContextCompat.getColorStateList(this, androidx.core.R.color.system_neutral1_1000)
            btnZoom5x.backgroundTintList = androidx.core.content.ContextCompat.getColorStateList(this, androidx.core.R.color.system_neutral1_1000)
            Toast.makeText(this, "2x Zoom", Toast.LENGTH_SHORT).show()
        }
        
        btnZoom5x.setOnClickListener {
            currentZoom = 5.0f
            btnZoom5x.backgroundTintList = androidx.core.content.ContextCompat.getColorStateList(this, androidx.core.R.color.system_accent1_accent1_light)
            btnZoom1x.backgroundTintList = androidx.core.content.ContextCompat.getColorStateList(this, androidx.core.R.color.system_neutral1_1000)
            btnZoom2x.backgroundTintList = androidx.core.content.ContextCompat.getColorStateList(this, androidx.core.R.color.system_neutral1_1000)
            Toast.makeText(this, "5x Zoom", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("GVT-X Settings")
            .setMessage("Resolution: 1080p\nFrame Rate: 30fps\nAudio: Enabled\n\nMore features coming soon!")
            .setPositiveButton("OK", null)
            .show()
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
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
                permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_CAMERA_PERMISSION)
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
                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val supportedSizes = map?.getOutputSizes(MediaRecorder::class.java)
                previewSize = supportedSizes?.firstOrNull { it.width == 1280 && it.height == 720 }
                    ?: supportedSizes?.firstOrNull()
                    ?: Size(1280, 720)
                return id
            }
        }
        return cameraManager.cameraIdList.firstOrNull() ?: "0"
    }

    private fun createCaptureSession() {
        val texture = textureView.surfaceTexture
        texture?.setDefaultBufferSize(previewSize?.width ?: 1280, previewSize?.height ?: 720)
        val surface = Surface(texture)

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
                    statusText.text = "Ready"
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    statusText.text = "Configuration failed"
                }
            },
            backgroundHandler
        )
    }

    private fun startPreview(surface: Surface) {
        val camera = cameraDevice ?: return
        val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        captureRequest.addTarget(surface)
        captureRequest.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        captureRequest.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        captureRequest.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        captureSession?.setRepeatingRequest(captureRequest.build(), null, backgroundHandler)
    }

    private fun takePicture() {
        val camera = cameraDevice ?: return
        val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        val imageSurface = imageReader?.surface
        if (imageSurface == null) return
        captureRequest.addTarget(imageSurface)
        captureRequest.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        captureRequest.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        captureRequest.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        captureSession?.capture(captureRequest.build(), null, backgroundHandler)
        statusText.text = "Photo captured!"
    }

    private fun startRecording() {
        // Simplified for now - full implementation from previous version
        Toast.makeText(this, "Video recording coming soon", Toast.LENGTH_SHORT).show()
    }

    private fun stopRecording() {
        Toast.makeText(this, "Video recording stopped", Toast.LENGTH_SHORT).show()
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
                    statusText.text = "Photo saved"
                    Toast.makeText(this, "Photo saved", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            statusText.text = "Failed to save"
        }
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
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                openCamera()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        closeCamera()
        stopBackgroundThread()
    }
}
