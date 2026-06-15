package com.gvtx

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.ImageFormat
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
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var surfaceView: SurfaceView
    private lateinit var textInfo: TextView
    private lateinit var btnCapture: Button
    private lateinit var btnSwitchLens: Button
    private lateinit var btnRecord: Button
    private lateinit var statusText: TextView

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var mediaRecorder: MediaRecorder? = null
    private lateinit var cameraManager: CameraManager
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null

    private var currentLensFacing = CameraCharacteristics.LENS_FACING_BACK
    private var isRecording = false
    private var videoPath: String = ""

    companion object {
        private const val REQUEST_CAMERA_PERMISSION = 200
        private const val TAG = "GVTX"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.surfaceView)
        textInfo = findViewById(R.id.textInfo)
        btnCapture = findViewById(R.id.btnCapture)
        btnSwitchLens = findViewById(R.id.btnSwitchLens)
        btnRecord = findViewById(R.id.btnRecord)
        statusText = findViewById(R.id.statusText)

        statusText.text = "GVT-X Initializing..."
        textInfo.text = "Ready | Tap CAPTURE for photo | RECORD for video"

        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        
        setupButtons()
        setupSurfaceView()

        startBackgroundThread()
        checkPermissions()
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA)
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
    }

    private fun setupSurfaceView() {
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.d(TAG, "Surface created")
                statusText.text = "Opening camera..."
                openCamera()
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
                    startPreview()
                    statusText.text = "Preview running"
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

    private fun startPreview() {
        val camera = cameraDevice ?: return
        val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        val surface = surfaceView.holder.surface
        captureRequest.addTarget(surface)
        
        // Use AUTO mode for reliable preview
        captureRequest.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        captureRequest.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        captureRequest.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        
        captureSession?.setRepeatingRequest(captureRequest.build(), null, backgroundHandler)
        Log.d(TAG, "Preview started in AUTO mode")
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
        statusText.text = "Photo captured! Saving..."
    }

    private fun startRecording() {
        try {
            // Prepare video file
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val videoFile = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "GVTX_$timestamp.mp4")
            videoPath = videoFile.absolutePath
            
            // Setup MediaRecorder
            mediaRecorder = MediaRecorder().apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoEncodingBitRate(5000000)
                setVideoFrameRate(30)
                setVideoSize(1920, 1080)
                setOutputFile(videoPath)
                
                prepare()
                start()
            }
            
            // Update UI
            isRecording = true
            btnRecord.text = "⏹️ STOP"
            statusText.text = "Recording video..."
            Toast.makeText(this@MainActivity, "Video recording started", Toast.LENGTH_SHORT).show()
            
            // Update preview for recording
            val camera = cameraDevice ?: return
            val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            val surface = surfaceView.holder.surface
            captureRequest.addTarget(surface)
            mediaRecorder?.surface?.let { captureRequest.addTarget(it) }
            
            captureRequest.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            captureRequest.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            captureRequest.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            
            captureSession?.setRepeatingRequest(captureRequest.build(), null, backgroundHandler)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            statusText.text = "Recording failed: ${e.message}"
            Toast.makeText(this, "Failed to start recording", Toast.LENGTH_SHORT).show()
            mediaRecorder?.release()
            mediaRecorder = null
        }
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            
            // Add video to gallery
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, File(videoPath).name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/GVT-X")
                }
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    contentResolver.openOutputStream(it)?.use { outputStream ->
                        File(videoPath).inputStream().use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                }
            }
            
            isRecording = false
            btnRecord.text = "⏺ RECORD"
            statusText.text = "Video saved to Gallery"
            Toast.makeText(this, "Video saved", Toast.LENGTH_LONG).show()
            
            // Restart preview
            startPreview()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recording", e)
            statusText.text = "Stop recording failed"
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
                statusText.text = "All permissions granted"
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
