package com.al.pluginsecure

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.getcapacitor.JSObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Full-screen native video recorder backed by CameraX VideoCapture.
// No @NativePlugin annotation on purpose (same reason as CameraVerifiedActivity).
class VideoRecordActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "VideoRecordActivity"

        @Volatile
        var active: VideoRecordActivity? = null

        fun requestStop() {
            active?.runOnUiThread { active?.stopRecording() }
        }
    }

    private lateinit var previewView: PreviewView
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var outputFile: File? = null
    private var recordingStartMs = 0L
    private var maxDurationMs = 0L
    private var qualityName = "high"

    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.values.all { it }) {
            startCamera()
        } else {
            finishWithError("Permissions denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        active = this
        setContentView(R.layout.activity_video_record)

        previewView = findViewById(R.id.previewView)
        val facingMode = intent.getStringExtra("facingMode") ?: "environment"
        qualityName = intent.getStringExtra("quality") ?: "high"
        maxDurationMs = intent.getLongExtra("maxDurationMs", 0L)
        if (facingMode == "user") lensFacing = CameraSelector.LENS_FACING_FRONT

        findViewById<View>(R.id.btnCancel).setOnClickListener { finishWithError("Cancelled by user") }
        findViewById<View>(R.id.btnSwitch).setOnClickListener { switchCamera() }
        findViewById<View>(R.id.btnRecord).setOnClickListener { toggleRecording() }

        checkPermissionsAndStartCamera()
    }

    private fun checkPermissionsAndStartCamera() {
        val required = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val ungranted = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (ungranted.isNotEmpty()) {
            requestPermissions.launch(ungranted.toTypedArray())
        } else {
            startCamera()
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                bindUseCases()
            } catch (e: Exception) {
                Log.e(TAG, "Camera start failed", e)
                finishWithError("Camera initialization failed")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun qualityFor(name: String): Quality = when (name) {
        "low" -> Quality.SD
        "medium" -> Quality.HD
        else -> Quality.FHD
    }

    private fun bindUseCases() {
        cameraProvider?.unbindAll()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(qualityFor(qualityName)))
            .build()
        val capture = VideoCapture.withOutput(recorder)
        videoCapture = capture

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        cameraProvider?.bindToLifecycle(this, selector, preview, capture)
    }

    private fun switchCamera() {
        if (recording != null) return
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        bindUseCases()
    }

    private fun toggleRecording() {
        if (recording != null) stopRecording() else startRecording()
    }

    private fun startRecording() {
        val capture = videoCapture ?: return
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(getExternalFilesDir(null), "Videos/PluginSecure/VID_$timestamp.mp4").apply {
            parentFile?.mkdirs()
        }
        outputFile = file

        val outputOptions = FileOutputOptions.Builder(file).build()
        val pending = capture.output.prepareRecording(this, outputOptions).withAudioEnabled()

        recordingStartMs = System.currentTimeMillis()
        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            if (event is VideoRecordEvent.Finalize) {
                recording = null
                updateRecordUi(false)
                if (event.hasError()) {
                    finishWithError("Recording failed: ${event.error}")
                } else {
                    returnResult(file)
                }
            }
        }
        updateRecordUi(true)

        if (maxDurationMs > 0) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (recording != null) stopRecording()
            }, maxDurationMs)
        }
    }

    private fun stopRecording() {
        val current = recording ?: return
        current.stop()
    }

    private fun updateRecordUi(recordingNow: Boolean) {
        (findViewById<View>(R.id.btnRecord) as? android.widget.Button)?.text =
            if (recordingNow) "Stop" else "Rekam"
    }

    private fun returnResult(file: File) {
        val duration = System.currentTimeMillis() - recordingStartMs
        val uri = if (Build.VERSION.SDK_INT >= 24) {
            FileProvider.getUriForFile(this, "${packageName}.pluginsecure.fileprovider", file).toString()
        } else {
            Uri.fromFile(file).toString()
        }
        val result = JSObject().apply {
            put("uri", uri)
            put("path", file.absolutePath)
            put("durationMs", duration)
            put("hasAudio", true)
        }
        setResult(Activity.RESULT_OK, Intent().putExtra("result", result.toString()))
        finish()
    }

    private fun finishWithError(message: String) {
        setResult(Activity.RESULT_CANCELED, Intent().putExtra("error", message))
        finish()
    }

    override fun onDestroy() {
        recording?.stop()
        recording = null
        cameraProvider?.unbindAll()
        active = null
        super.onDestroy()
    }
}
