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
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var textureView: TextureView
    private lateinit var textInfo: TextView
    private lateinit var btnCapture: Button
    private lateinit var btnSwitchLens: Button
    private lateinit var btnRecord: Button
    private lateinit var btnSettings: Button
    private lateinit var statusText: TextView

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var mediaRecorder: MediaRecorder? = null
    private lateinit var cameraManager: CameraManager
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    private var previewSize: Size? = null
    private var videoSize: Size = Size(1280, 720)  // Start with 720p for reliability

    private var currentLensFacing = CameraCharacteristics.LENS_FACING_BACK
    private var isRecording = false
    private var videoPath: String = ""
    
    // Settings
    private var videoQuality = "720p"
    private var frameRate = 30
    private var audioEnabled = true

    companion object {
        private const val REQUEST_CAMERA_PERMISSION = 200
        private const val TAG = "GVTX"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.textureView)
        textInfo = findViewById(R.id.textInfo)
        btnCapture = findViewById(R.id.btnCapture)
        btnSwitchLens = findViewById(R.id.btnSwitchLens)
        btnRecord = findViewById(R.id.btnRecord)
        btnSettings = findViewById(R.id.btnSettings)
        statusText = findViewById(R.id.statusText)

        statusText.text = "GVT-X Ready"
        textInfo.text = "Photo | Video | Settings"

        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        
        setupButtons()
        setupTextureView()

        startBackgroundThread()
        checkPermissions()
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

    private fun setupButtons() {
        btnCapture.setOnClickListener {
            statusText.text = "Capturing photo..."
            takePicture()
        }

        btnRecord.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
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
    }
    
    private fun showSettingsDialog() {
        val options = arrayOf("Video Quality: $videoQuality", "Frame Rate: $frameRate fps", "Audio: ${if (audioEnabled) "ON" else "OFF"}", "About")
        
        AlertDialog.Builder(this)
            .setTitle("GVT-X Settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showVideoQualityDialog()
                    1 -> showFrameRateDialog()
                    2 -> {
                        audioEnabled = !audioEnabled
                        Toast.makeText(this, "Audio: ${if (audioEnabled) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
                    }
                    3 -> showAboutDialog()
                }
            }
            .show()
    }
    
    private fun showVideoQualityDialog() {
        val qualities = arrayOf("720p", "1080p")
        AlertDialog.Builder(this)
            .setTitle("Video Quality")
            .setItems(qualities) { _, which ->
                videoQuality = qualities[which]
                videoSize = if (videoQuality == "720p") Size(1280, 720) else Size(1920, 1080)
                Toast.makeText(this, "Quality: $videoQuality", Toast.LENGTH_SHORT).show()
            }
            .show()
    }
    
    private fun showFrameRateDialog() {
        val fpsOptions = arrayOf("24", "30", "60")
        AlertDialog.Builder(this)
            .setTitle("Frame Rate")
            .setItems(fpsOptions) { _, which ->
                frameRate = fpsOptions[which].toInt()
                Toast.makeText(this, "FPS: $frameRate", Toast.LENGTH_SHORT).show()
            }
            .show()
    }
    
    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("GVT-X Camera")
            .setMessage("Version 1.0\n\nProfessional Camera App\n\nFeatures:\n- Photo Capture\n- Video Recording\n- Front/Back Camera\n- Settings Menu")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun setupTextureView() {
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                Log.d(TAG, "TextureView surface available")
                statusText.text = "Opening camera..."
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

    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            statusText.text = "No camera permission"
            return
        }

        try {
            val cameraId = getCameraId()
            Log.d(TAG, "Opening camera: $cameraId")
            
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.d(TAG, "Camera opened")
                    cameraDevice = camera
                    statusText.text = "Camera ready"
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
                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                // Get supported video size for MediaRecorder
                val supportedSizes = map?.getOutputSizes(MediaRecorder::class.java)
                previewSize = supportedSizes?.firstOrNull { it.width == 1280 && it.height == 720 }
                    ?: supportedSizes?.firstOrNull()
                    ?: Size(1280, 720)
                Log.d(TAG, "Found camera ID: $id, preview size: ${previewSize?.width}x${previewSize?.height}")
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
                    Log.d(TAG, "Capture session configured")
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    statusText.text = "Configuration failed"
                    Log.e(TAG, "Capture session configuration failed")
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
        Log.d(TAG, "Preview started")
    }

    private fun takePicture() {
        val camera = cameraDevice ?: return
        val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        val imageSurface = imageReader?.surface
        if (imageSurface == null) {
            statusText.text = "Error: ImageReader not ready"
            return
        }
        captureRequest.addTarget(imageSurface)
        
        captureRequest.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        captureRequest.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        captureRequest.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        
        captureSession?.capture(captureRequest.build(), null, backgroundHandler)
        statusText.text = "Photo captured!"
    }

    private fun setupMediaRecorder(): Boolean {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val videoFile = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "GVTX_$timestamp.mp4")
            videoPath = videoFile.absolutePath
            
            // Use proper constructor for Android 12+
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                MediaRecorder()
            }
            
            mediaRecorder?.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                
                // Only add audio if permission granted
                if (audioEnabled && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                }
                
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                
                if (audioEnabled && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                }
                
                // Use safe 720p resolution first
                setVideoSize(videoSize.width, videoSize.height)
                setVideoFrameRate(frameRate)
                setVideoEncodingBitRate(5000000)
                setOutputFile(videoPath)
                setOrientationHint(90)
                
                prepare()
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "MediaRecorder setup failed", e)
            statusText.text = "MediaRecorder error: ${e.message}"
            return false
        }
    }

    private fun startRecording() {
        try {
            if (!setupMediaRecorder()) {
                return
            }
            
            val recorderSurface = mediaRecorder?.surface
            if (recorderSurface == null) {
                statusText.text = "Recorder surface not available"
                return
            }
            
            val texture = textureView.surfaceTexture
            val previewSurface = Surface(texture)
            val camera = cameraDevice
            
            if (camera == null) {
                statusText.text = "Camera not available"
                return
            }
            
            // Close current session
            captureSession?.close()
            captureSession = null
            
            camera.createCaptureSession(
                listOf(previewSurface, recorderSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        captureRequest.addTarget(previewSurface)
                        captureRequest.addTarget(recorderSurface)
                        
                        captureRequest.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                        captureRequest.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        captureRequest.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        
                        session.setRepeatingRequest(captureRequest.build(), null, backgroundHandler)
                        
                        try {
                            mediaRecorder?.start()
                            isRecording = true
                            btnRecord.text = "STOP"
                            statusText.text = "Recording video..."
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "Recording started", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "MediaRecorder start failed", e)
                            statusText.text = "Recorder start failed"
                        }
                    }
                    
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        statusText.text = "Recording session failed"
                        Log.e(TAG, "Recording session configuration failed")
                        createCaptureSession()
                    }
                },
                backgroundHandler
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            statusText.text = "Recording failed: ${e.message}"
            Toast.makeText(this, "Failed to start recording", Toast.LENGTH_SHORT).show()
            mediaRecorder?.release()
            mediaRecorder = null
            createCaptureSession()
        }
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.apply {
                try {
                    stop()
                } catch (e: Exception) {
                    Log.e(TAG, "Stop failed (may be normal if recording was short)", e)
                }
                release()
            }
            mediaRecorder = null
            
            // Add video to gallery
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, File(videoPath).name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/GVT-X")
                    }
                }
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    contentResolver.openOutputStream(it)?.use { outputStream ->
                        File(videoPath).inputStream().use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save video to gallery", e)
            }
            
            isRecording = false
            btnRecord.text = "RECORD"
            statusText.text = "Video saved"
            Toast.makeText(this, "Video saved to Gallery", Toast.LENGTH_LONG).show()
            
            // Restore preview session
            captureSession?.close()
            captureSession = null
            createCaptureSession()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recording", e)
            statusText.text = "Stop recording failed"
            createCaptureSession()
        }
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
            Log.e(TAG, "Failed to save image", e)
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
        mediaRecorder?.release()
        mediaRecorder = null
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
                statusText.text = "Permissions granted"
                openCamera()
            } else {
                statusText.text = "Permissions required"
                Toast.makeText(this, "Camera and storage permissions required", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        closeCamera()
        stopBackgroundThread()
    }
}
