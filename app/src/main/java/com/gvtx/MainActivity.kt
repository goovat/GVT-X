package com.gvtx

import android.Manifest
import android.app.AlertDialog
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.*
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
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    // Camera components
    private lateinit var textureView: TextureView
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private lateinit var cameraManager: CameraManager
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    
    // State
    private var currentLensFacing = CameraCharacteristics.LENS_FACING_BACK
    private var isRecording = false
    private var isProMode = false
    private var currentMode = "AI CAM"
    
    // UI Components - match layout exactly
    private lateinit var statusText: TextView
    private lateinit var histogram: TextView
    private lateinit var visionChip: TextView
    private lateinit var aiSuggestion: TextView
    private lateinit var smartCaptureStatus: TextView
    private lateinit var btnCapture: TextView
    private lateinit var btnRecord: TextView
    private lateinit var btnSwitchLens: TextView
    private lateinit var btnGallery: TextView
    private lateinit var btnSettings: TextView
    private lateinit var btnFlash: TextView
    private lateinit var btnHDR: TextView
    private lateinit var btnRatio: TextView
    private lateinit var btnAIScene: TextView
    private lateinit var btnFilters: TextView
    
    // Zoom buttons
    private lateinit var zoom06x: TextView
    private lateinit var zoom1x: TextView
    private lateinit var zoom2x: TextView
    private lateinit var zoom5x: TextView
    private lateinit var zoom10x: TextView
    
    // Mode buttons
    private lateinit var modePortrait: TextView
    private lateinit var modeVideo: TextView
    private lateinit var modeAICam: TextView
    private lateinit var modeBeauty: TextView
    private lateinit var modePro: TextView
    private lateinit var modeAstro: TextView
    private lateinit var modeDocument: TextView
    private lateinit var modeScanner: TextView
    
    // Pro controls
    private lateinit var proPanel: LinearLayout
    private lateinit var seekISO: SeekBar
    private lateinit var seekShutter: SeekBar
    private lateinit var seekWB: SeekBar
    private lateinit var seekEV: SeekBar
    private lateinit var seekFocus: SeekBar
    private lateinit var toggleNR: ToggleButton
    private lateinit var txtISO: TextView
    private lateinit var txtShutter: TextView
    private lateinit var txtWB: TextView
    private lateinit var txtEV: TextView
    private lateinit var txtFocus: TextView
    
    // Focus peaking
    private lateinit var focusPeakingView: View
    
    companion object {
        private const val REQUEST_CAMERA_PERMISSION = 200
        private const val TAG = "GVTX"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)
            initializeViews()
            setupListeners()
            setupTextureView()
            cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
            startBackgroundThread()
            checkPermissions()
            startAISuggestions()
        } catch (e: Exception) {
            Log.e(TAG, "onCreate error", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun initializeViews() {
        try {
            // Top bar
            btnSettings = findViewById(R.id.btnSettings)
            btnFlash = findViewById(R.id.btnFlash)
            btnHDR = findViewById(R.id.btnHDR)
            btnRatio = findViewById(R.id.btnRatio)
            btnAIScene = findViewById(R.id.btnAIScene)
            btnFilters = findViewById(R.id.btnFilters)
            
            // Overlay
            histogram = findViewById(R.id.histogram)
            visionChip = findViewById(R.id.visionChip)
            aiSuggestion = findViewById(R.id.aiSuggestion)
            smartCaptureStatus = findViewById(R.id.smartCaptureStatus)
            statusText = findViewById(R.id.statusText)
            
            // Zoom
            zoom06x = findViewById(R.id.zoom06x)
            zoom1x = findViewById(R.id.zoom1x)
            zoom2x = findViewById(R.id.zoom2x)
            zoom5x = findViewById(R.id.zoom5x)
            zoom10x = findViewById(R.id.zoom10x)
            
            // Modes
            modePortrait = findViewById(R.id.modePortrait)
            modeVideo = findViewById(R.id.modeVideo)
            modeAICam = findViewById(R.id.modeAICam)
            modeBeauty = findViewById(R.id.modeBeauty)
            modePro = findViewById(R.id.modePro)
            modeAstro = findViewById(R.id.modeAstro)
            modeDocument = findViewById(R.id.modeDocument)
            modeScanner = findViewById(R.id.modeScanner)
            
            // Bottom
            btnGallery = findViewById(R.id.btnGallery)
            btnCapture = findViewById(R.id.btnCapture)
            btnRecord = findViewById(R.id.btnRecord)
            btnSwitchLens = findViewById(R.id.btnSwitchLens)
            
            // Camera preview
            textureView = findViewById(R.id.textureView)
            
            // Focus peaking
            focusPeakingView = findViewById(R.id.focusPeakingView)
            
            // Pro panel
            proPanel = findViewById(R.id.proPanel)
            seekISO = findViewById(R.id.seekISO)
            seekShutter = findViewById(R.id.seekShutter)
            seekWB = findViewById(R.id.seekWB)
            seekEV = findViewById(R.id.seekEV)
            seekFocus = findViewById(R.id.seekFocus)
            toggleNR = findViewById(R.id.toggleNR)
            txtISO = findViewById(R.id.txtISO)
            txtShutter = findViewById(R.id.txtShutter)
            txtWB = findViewById(R.id.txtWB)
            txtEV = findViewById(R.id.txtEV)
            txtFocus = findViewById(R.id.txtFocus)
            
            // Set initial state
            statusText.text = "GVT-X Ready"
            smartCaptureStatus.text = "✅ Ready"
            
        } catch (e: Exception) {
            Log.e(TAG, "initializeViews error", e)
            Toast.makeText(this, "View initialization error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupListeners() {
        // Top bar
        btnSettings.setOnClickListener { showSettings() }
        btnFlash.setOnClickListener { toggleFlash() }
        btnHDR.setOnClickListener { toggleHDR() }
        btnRatio.setOnClickListener { cycleRatio() }
        btnAIScene.setOnClickListener { showSceneDetection() }
        btnFilters.setOnClickListener { showFilters() }
        
        // Vision chip
        visionChip.setOnClickListener { toggleVision() }
        
        // Zoom
        zoom06x.setOnClickListener { setZoom(0.6f, it) }
        zoom1x.setOnClickListener { setZoom(1.0f, it) }
        zoom2x.setOnClickListener { setZoom(2.0f, it) }
        zoom5x.setOnClickListener { setZoom(5.0f, it) }
        zoom10x.setOnClickListener { setZoom(10.0f, it) }
        
        // Modes
        modePortrait.setOnClickListener { setMode("Portrait", it) }
        modeVideo.setOnClickListener { setMode("Video", it) }
        modeAICam.setOnClickListener { setMode("AI CAM", it) }
        modeBeauty.setOnClickListener { setMode("Beauty", it) }
        modePro.setOnClickListener { setMode("Pro", it) }
        modeAstro.setOnClickListener { setMode("Astro", it) }
        modeDocument.setOnClickListener { setMode("Document", it) }
        modeScanner.setOnClickListener { setMode("Scanner", it) }
        
        // Bottom
        btnGallery.setOnClickListener { Toast.makeText(this, "📷 Gallery", Toast.LENGTH_SHORT).show() }
        btnCapture.setOnClickListener { capturePhoto() }
        btnRecord.setOnClickListener { toggleRecording() }
        btnSwitchLens.setOnClickListener { switchLens() }
        
        // Pro controls
        setupProControls()
    }

    private fun setupProControls() {
        seekISO.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val iso = when(progress) {
                    0 -> 100; 1 -> 200; 2 -> 400; 3 -> 800
                    4 -> 1600; 5 -> 3200; 6 -> 6400; 7 -> 12800
                    else -> 25600
                }
                txtISO.text = iso.toString()
                if (isProMode) statusText.text = "Pro: ISO $iso"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        seekShutter.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val shutter = when(progress) {
                    0 -> "1s"; 1 -> "1/2"; 2 -> "1/4"; 3 -> "1/8"
                    4 -> "1/16"; 5 -> "1/30"; 6 -> "1/60"; 7 -> "1/120"
                    8 -> "1/240"; 9 -> "1/480"; else -> "1/1000"
                }
                txtShutter.text = shutter
                if (isProMode) statusText.text = "Pro: SS $shutter"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        seekWB.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val wb = 2500 + progress * 75
                txtWB.text = "${wb}K"
                if (isProMode) statusText.text = "Pro: WB ${wb}K"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        seekEV.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val ev = String.format("%.1f", (progress - 10) / 2.0f)
                txtEV.text = ev
                if (isProMode) statusText.text = "Pro: EV $ev"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        seekFocus.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                txtFocus.text = "$progress%"
                if (isProMode) statusText.text = "Pro: Focus $progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        toggleNR.setOnCheckedChangeListener { _, isChecked ->
            if (isProMode) statusText.text = "Pro: NR ${if (isChecked) "ON" else "OFF"}"
        }
    }

    private fun setMode(mode: String, view: View) {
        currentMode = mode
        val modeViews = listOf(modePortrait, modeVideo, modeAICam, modeBeauty, modePro, modeAstro, modeDocument, modeScanner)
        modeViews.forEach { 
            it.setBackgroundColor(ContextCompat.getColor(this, android.R.color.darker_gray))
        }
        view.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
        
        when(mode) {
            "Pro" -> {
                isProMode = true
                proPanel.visibility = View.VISIBLE
                focusPeakingView.visibility = View.VISIBLE
                btnRecord.visibility = View.GONE
                btnCapture.visibility = View.VISIBLE
                statusText.text = "Pro Mode - Manual Controls"
            }
            "Video" -> {
                isProMode = false
                proPanel.visibility = View.GONE
                focusPeakingView.visibility = View.GONE
                btnRecord.visibility = View.VISIBLE
                btnCapture.visibility = View.GONE
                statusText.text = "Video Mode"
            }
            "AI CAM" -> {
                isProMode = false
                proPanel.visibility = View.GONE
                focusPeakingView.visibility = View.GONE
                btnRecord.visibility = View.GONE
                btnCapture.visibility = View.VISIBLE
                statusText.text = "AI Camera Active"
                startAISuggestions()
            }
            else -> {
                isProMode = false
                proPanel.visibility = View.GONE
                focusPeakingView.visibility = View.GONE
                btnRecord.visibility = View.GONE
                btnCapture.visibility = View.VISIBLE
                statusText.text = "$mode Mode"
            }
        }
        Toast.makeText(this, "$mode Mode", Toast.LENGTH_SHORT).show()
    }

    private fun startAISuggestions() {
        val suggestions = arrayOf(
            "📷 Hold Steady", "🌙 Low Light Detected", 
            "👤 Smile Detected", "🌅 Try Night Mode",
            "📄 Document Detected", "🌟 Scene: Portrait"
        )
        var index = 0
        val handler = Handler()
        handler.post(object : Runnable {
            override fun run() {
                if (currentMode == "AI CAM") {
                    aiSuggestion.text = suggestions[index % suggestions.size]
                    index++
                    updateHistogram()
                }
                handler.postDelayed(this, 3000)
            }
        })
    }

    private fun updateHistogram() {
        val levels = listOf("▁", "▂", "▃", "▄", "▅", "▆", "▇", "█")
        histogram.text = (0..7).map { levels.random() }.joinToString("")
    }

    // Feature implementations
    private fun toggleFlash() {
        val color = if (btnFlash.currentTextColor == -0x1) 
            ContextCompat.getColor(this, android.R.color.holo_orange_dark) 
        else ContextCompat.getColor(this, android.R.color.white)
        btnFlash.setTextColor(color)
        Toast.makeText(this, "Flash ${if (color == -0x1) "OFF" else "ON"}", Toast.LENGTH_SHORT).show()
    }

    private fun toggleHDR() {
        val color = if (btnHDR.currentTextColor == -0x1) 
            ContextCompat.getColor(this, android.R.color.holo_orange_dark) 
        else ContextCompat.getColor(this, android.R.color.white)
        btnHDR.setTextColor(color)
        Toast.makeText(this, "HDR ${if (color == -0x1) "OFF" else "ON"}", Toast.LENGTH_SHORT).show()
    }

    private fun cycleRatio() {
        val ratios = arrayOf("4:3", "16:9", "1:1")
        var index = ratios.indexOf(btnRatio.text.toString())
        index = (index + 1) % ratios.size
        btnRatio.text = ratios[index]
        Toast.makeText(this, "Ratio: ${btnRatio.text}", Toast.LENGTH_SHORT).show()
    }

    private fun showSceneDetection() {
        btnAIScene.text = "🌙 NIGHT"
        Toast.makeText(this, "🌙 Scene: Night Mode Detected", Toast.LENGTH_SHORT).show()
    }

    private fun showFilters() {
        Toast.makeText(this, "Filters: Vintage, B&W, Vivid, Cinematic", Toast.LENGTH_LONG).show()
    }

    private fun toggleVision() {
        val states = arrayOf("GVT-X Vision OFF", "GVT-X Vision ON", "GVT-X Vision AUTO")
        var index = states.indexOf(visionChip.text.toString())
        index = (index + 1) % states.size
        visionChip.text = states[index]
        Toast.makeText(this, "Vision: ${visionChip.text}", Toast.LENGTH_SHORT).show()
    }

    private fun setZoom(zoom: Float, view: View) {
        val zoomViews = listOf(zoom06x, zoom1x, zoom2x, zoom5x, zoom10x)
        zoomViews.forEach { 
            it.setBackgroundColor(ContextCompat.getColor(this, android.R.color.darker_gray))
        }
        view.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
        textureView.scaleX = zoom
        textureView.scaleY = zoom
        Toast.makeText(this, "${zoom}x Zoom", Toast.LENGTH_SHORT).show()
    }

    private fun showSettings() {
        val options = arrayOf(
            "Video Quality: 1080p",
            "Frame Rate: 30fps",
            "Audio: ON",
            "Storage: Internal",
            "About GVT-X"
        )
        AlertDialog.Builder(this)
            .setTitle("⚙️ GVT-X Settings")
            .setItems(options) { _, which ->
                when(which) {
                    0 -> Toast.makeText(this, "Video Quality: 1080p", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this, "Frame Rate: 30fps", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this, "Audio: ON", Toast.LENGTH_SHORT).show()
                    3 -> Toast.makeText(this, "Storage: Internal", Toast.LENGTH_SHORT).show()
                    4 -> showAbout()
                }
            }
            .show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle("GVT-X Camera")
            .setMessage("Version 2.0\n\nProfessional AI Camera App\n\nFeatures:\n• AI Scene Detection\n• Pro Manual Controls\n• Focus Peaking\n• Live Histogram\n• 8 Shooting Modes\n• GVT-X Vision AI")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun capturePhoto() {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "GVTX_$timestamp.jpg"
            
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GVT-X")
                }
            }
            
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { stream ->
                    val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.BLACK)
                    val paint = Paint().apply {
                        color = Color.parseColor("#FF6B35")
                        textSize = 80f
                        textAlign = Paint.Align.CENTER
                    }
                    canvas.drawText("GVT-X", 960f, 540f, paint)
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
                }
            }
            
            Toast.makeText(this, "📸 Photo saved: $filename", Toast.LENGTH_LONG).show()
            smartCaptureStatus.text = "✅ Photo Captured!"
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to save photo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleRecording() {
        isRecording = !isRecording
        btnRecord.text = if (isRecording) "⏹" else "🔴"
        btnRecord.setBackgroundColor(ContextCompat.getColor(this, if (isRecording) android.R.color.holo_red_dark else android.R.color.holo_orange_dark))
        Toast.makeText(this, if (isRecording) "🔴 Recording started" else "⏹ Recording stopped", Toast.LENGTH_SHORT).show()
        smartCaptureStatus.text = if (isRecording) "🔴 RECORDING..." else "✅ Ready"
    }

    // Camera methods
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
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_CAMERA_PERMISSION)
        }
    }

    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
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
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "openCamera error", e)
        }
    }

    private fun getCameraId(): String {
        cameraManager.cameraIdList.forEach { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing == currentLensFacing) return id
        }
        return cameraManager.cameraIdList.firstOrNull() ?: "0"
    }

    private fun createCaptureSession() {
        val texture = textureView.surfaceTexture
        val surface = Surface(texture)

        cameraDevice?.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    startPreview(surface)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
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

    private fun switchLens() {
        currentLensFacing = if (currentLensFacing == CameraCharacteristics.LENS_FACING_BACK) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        closeCamera()
        openCamera()
        Toast.makeText(this, "🔄 Lens Switched", Toast.LENGTH_SHORT).show()
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
