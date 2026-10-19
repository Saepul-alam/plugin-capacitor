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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.getcapacitor.JSObject
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

// No @NativePlugin here: this is an Activity, not a plugin. It is launched with
// startActivityForResult() by PluginSecurePlugin. Leaving the annotation on it makes
// Capacitor 3's CLI regex (`^@(?:CapacitorPlugin|NativePlugin)[\s\S]+?class ([\w]+)`)
// register the Activity as a plugin, which crashes at startup with ClassCastException.
class CameraVerifiedActivity : AppCompatActivity() {
    private val TAG = "CameraVerifiedActivity"
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val cameraScope = CoroutineScope(Dispatchers.IO)

    private lateinit var previewView: PreviewView
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var options: VerifiedPhotoOptions? = null
    private var currentBestLocation: android.location.Location? = null
    private var locationRequestTime = 0L
    private var pendingPhotoFile: File? = null
    private var burstCount = 1
    private var burstIntervalMs = 250L
    private val burstFrames = mutableListOf<JSObject>()

    private val fusedLocationClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }

    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.values.all { it }) {
            startCamera()
        } else {
            finishWithError("Permissions denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_verified)

        previewView = findViewById(R.id.previewView)
        val optionsJson = intent.getStringExtra("options") ?: "{}"
        options = VerifiedPhotoOptions.fromJson(optionsJson)

        if (options?.direction == "front") {
            lensFacing = CameraSelector.LENS_FACING_FRONT
        }
        burstCount = (options?.burstCount ?: 1).coerceIn(1, 20)
        burstIntervalMs = (options?.burstIntervalMs ?: 250L).coerceIn(0L, 5000L)

        setupClickListeners()
        requestLocation()
        checkPermissionsAndStartCamera()
    }

    private fun setupClickListeners() {
        findViewById<View>(R.id.btnCapture).setOnClickListener { capturePhoto() }
        findViewById<View>(R.id.btnCancel).setOnClickListener { finishWithError("Cancelled by user") }
        findViewById<View>(R.id.btnSwitchCamera).setOnClickListener { switchCamera() }
    }

    private fun checkPermissionsAndStartCamera() {
        val requiredPermissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= 28) {
            requiredPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val ungranted = requiredPermissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (ungranted.isNotEmpty()) {
            requestPermissions.launch(ungranted.toTypedArray())
        } else {
            startCamera()
        }
    }

    private fun requestLocation() {
        locationRequestTime = System.currentTimeMillis()
        cameraScope.launch {
            try {
                val request = LocationRequest.create()
                    .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
                    .setMaxWaitTime(5000)
                    .setNumUpdates(1)

                currentBestLocation = fusedLocationClient.getCurrentLocation(
                    LocationRequest.PRIORITY_HIGH_ACCURACY, null
                ).await()
                runOnUiThread { updateGPSStatus() }
            } catch (e: Exception) {
                Log.e(TAG, "Location request failed", e)
                runOnUiThread { updateGPSStatus() }
            }
        }
    }

    private fun updateGPSStatus() {
        val statusView = findViewById<View>(R.id.tvGpsStatus)
        if (currentBestLocation != null) {
            val age = System.currentTimeMillis() - locationRequestTime
            statusView.visibility = View.VISIBLE
            (statusView as? android.widget.TextView)?.text = String.format(
                Locale.getDefault(),
                "GPS: %.5f, %.5f ±%.1fm (age %ds)",
                currentBestLocation!!.latitude,
                currentBestLocation!!.longitude,
                currentBestLocation!!.accuracy,
                age / 1000
            )
        } else {
            statusView.visibility = View.VISIBLE
            (statusView as? android.widget.TextView)?.text = "Getting GPS fix..."
        }
    }

    private fun startCamera() {
        // ProcessCameraProvider.getInstance() returns a Guava ListenableFuture, not a
        // com.google.android.gms.tasks.Task, so kotlinx.coroutines.tasks.await() does not
        // apply to it (receiver type mismatch). Attach a listener instead. The listener
        // runs on the main executor, which is also what CameraX requires to bind use cases.
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                Log.e(TAG, "Camera start failed", e)
                finishWithError("Camera initialization failed")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        cameraProvider?.unbindAll()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val captureMode = if (options?.resultType == "base64" || options?.resultType == "dataUrl") {
            ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        } else {
            ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
        }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(captureMode)
            .setTargetRotation(previewView.display.rotation)
            .build()

        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        cameraProvider?.bindToLifecycle(this, cameraSelector, preview, imageCapture)
    }

    private fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        bindCameraUseCases()
    }

    private fun capturePhoto() {
        val imgCapture = imageCapture ?: return
        val opts = options ?: return

        val gpsAge = System.currentTimeMillis() - locationRequestTime
        if (currentBestLocation == null) {
            Toast.makeText(this, "Waiting for GPS fix...", Toast.LENGTH_SHORT).show()
            requestLocation()
            return
        }

        if (opts.requireGPSVerification && (gpsAge > opts.maxGPSAgeMs || currentBestLocation!!.accuracy > opts.minAccuracyMeters)) {
            Toast.makeText(this, "GPS not accurate enough (${currentBestLocation!!.accuracy}m). Try outdoors.", Toast.LENGTH_LONG).show()
            requestLocation()
            return
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val photoFile = File(getExternalFilesDir(null), "Pictures/PluginSecure/IMG_$timestamp.jpg").apply {
            parentFile?.mkdirs()
        }
        pendingPhotoFile = photoFile

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imgCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val savedFile = outputFileResults.savedUri?.let { File(it.path) } ?: photoFile
                    val age = System.currentTimeMillis() - locationRequestTime

                    if (opts.embedEXIF && currentBestLocation != null) {
                        EXIFHelper.writeVerifiedEXIF(savedFile, currentBestLocation!!, age, currentBestLocation!!.provider)
                    }

                    if (opts.watermark && currentBestLocation != null) {
                        applyWatermark(savedFile)
                    }

                    if (burstCount > 1) {
                        collectBurstFrame(savedFile, age)
                    } else {
                        returnResult(savedFile, age)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Capture failed", exception)
                    finishWithError("Capture failed: ${exception.message}")
                }
            }
        )
    }

    private fun applyWatermark(photoFile: File) {
        try {
            val decoded = android.graphics.BitmapFactory.decodeFile(photoFile.absolutePath) ?: return
            val bitmap = decoded.copy(android.graphics.Bitmap.Config.ARGB_8888, true) ?: decoded
            val canvas = android.graphics.Canvas(bitmap)
            val loc = currentBestLocation
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

            val lines = mutableListOf("Plugin Secure Verified")
            if (loc != null) {
                lines.add(String.format(Locale.US, "%.6f, %.6f", loc.latitude, loc.longitude))
                lines.add(String.format(Locale.US, "\u00b1%.1fm  %s", loc.accuracy, stamp))
            } else {
                lines.add(stamp)
            }

            val textSize = (bitmap.width / 34f).coerceAtLeast(14f)
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                this.textSize = textSize
                isAntiAlias = true
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setShadowLayer(textSize / 4f, 0f, 0f, android.graphics.Color.BLACK)
            }
            val background = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(150, 0, 0, 0)
            }

            val padding = textSize * 0.6f
            val lineHeight = textSize * 1.35f
            val blockHeight = lineHeight * lines.size + padding * 2
            val blockWidth = (lines.maxOfOrNull { paint.measureText(it) } ?: 0f) + padding * 2
            val left = padding
            val bottom = bitmap.height - padding
            val top = bottom - blockHeight

            canvas.drawRect(left, top, left + blockWidth, bottom, background)
            lines.forEachIndexed { index, line ->
                val baseline = top + padding + lineHeight * index + textSize
                canvas.drawText(line, left + padding, baseline, paint)
            }

            java.io.FileOutputStream(photoFile).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, options?.quality ?: 90, out)
            }
            bitmap.recycle()
            if (bitmap !== decoded) decoded.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply watermark", e)
        }
    }

    private fun exifJson(gpsAgeMs: Long): JSObject = JSObject().apply {
        if (currentBestLocation != null) {
            put("latitude", currentBestLocation!!.latitude)
            put("longitude", currentBestLocation!!.longitude)
            put("altitude", currentBestLocation!!.altitude)
            put("accuracy", currentBestLocation!!.accuracy)
            put("timestamp", currentBestLocation!!.time)
            put("isVerified", true)
            put("verificationDetails", String.format(
                Locale.US, "VERIFIED|ACCURACY:%.1f|AGE_MS:%d",
                currentBestLocation!!.accuracy, gpsAgeMs
            ))
        } else {
            put("isVerified", false)
            put("verificationDetails", "NO_GPS_FIX")
        }
    }

    private fun collectBurstFrame(photoFile: File, gpsAgeMs: Long) {
        cameraScope.launch {
            val base64 = android.util.Base64.encodeToString(photoFile.readBytes(), android.util.Base64.DEFAULT)
            runOnUiThread {
                burstFrames.add(JSObject().apply {
                    put("base64", base64)
                    put("timestamp", System.currentTimeMillis())
                    put("exif", exifJson(gpsAgeMs))
                })
                if (burstFrames.size >= burstCount) {
                    returnBurstResult(gpsAgeMs)
                } else {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        capturePhoto()
                    }, burstIntervalMs)
                }
            }
        }
    }

    private fun returnBurstResult(gpsAgeMs: Long) {
        val result = JSObject()
        result.put("frames", com.getcapacitor.JSArray().apply { burstFrames.forEach { put(it) } })
        result.put("exif", exifJson(gpsAgeMs))
        setResult(Activity.RESULT_OK, Intent().putExtra("result", result.toString()))
        finish()
    }

    private fun returnResult(photoFile: File, gpsAgeMs: Long) {
        val result = JSObject()
        val uri = if (Build.VERSION.SDK_INT >= 24) {
            FileProvider.getUriForFile(this, "${packageName}.pluginsecure.fileprovider", photoFile).toString()
        } else {
            Uri.fromFile(photoFile).toString()
        }

        result.put("uri", uri)
        result.put("path", photoFile.absolutePath)
        result.put("exif", exifJson(gpsAgeMs))

        if (options?.resultType == "base64" || options?.resultType == "dataUrl") {
            cameraScope.launch {
                val base64 = android.util.Base64.encodeToString(photoFile.readBytes(), android.util.Base64.DEFAULT)
                runOnUiThread {
                    if (options?.resultType == "base64") result.put("base64", base64)
                    else result.put("dataUrl", "data:image/jpeg;base64,$base64")
                    setResult(Activity.RESULT_OK, Intent().putExtra("result", result.toString()))
                    finish()
                }
            }
        } else {
            setResult(Activity.RESULT_OK, Intent().putExtra("result", result.toString()))
            finish()
        }
    }

    private fun finishWithError(message: String) {
        setResult(Activity.RESULT_CANCELED, Intent().putExtra("error", message))
        finish()
    }

    override fun onDestroy() {
        cameraExecutor.shutdown()
        cameraScope.cancel()
        super.onDestroy()
    }
}