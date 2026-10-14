package com.al.pluginsecure

import android.Manifest
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
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

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
        options = VerifiedPhotoOptions.fromJson(intent.getStringExtra("options") ?: "{}")

        if (options?.direction == "front") {
            lensFacing = CameraSelector.LENS_FACING_FRONT
        }

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
        cameraScope.launch {
            try {
                cameraProvider = ProcessCameraProvider.getInstance(this@CameraVerifiedActivity).await()
                bindCameraUseCases()
            } catch (e: Exception) {
                Log.e(TAG, "Camera start failed", e)
                runOnUiThread { finishWithError("Camera initialization failed") }
            }
        }
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

                    returnResult(savedFile, age)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Capture failed", exception)
                    finishWithError("Capture failed: ${exception.message}")
                }
            }
        )
    }

    private fun applyWatermark(photoFile: File) {
        // TODO: Implement bitmap watermark with GPS coords + timestamp
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

        val exif = JSObject().apply {
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
        result.put("exif", exif)

        if (options?.resultType == "base64" || options?.resultType == "dataUrl") {
            cameraScope.launch {
                val base64 = photoFile.readBytes().encodeToBase64()
                runOnUiThread {
                    if (options?.resultType == "base64") result.put("base64", base64)
                    else result.put("dataUrl", "data:image/jpeg;base64,$base64")
                    setResult(RESULT_OK, Intent().putExtra("result", result.toString()))
                    finish()
                }
            }
        } else {
            setResult(RESULT_OK, Intent().putExtra("result", result.toString()))
            finish()
        }
    }

    private fun finishWithError(message: String) {
        setResult(RESULT_CANCELED, Intent().putExtra("error", message))
        finish()
    }

    override fun onDestroy() {
        cameraExecutor.shutdown()
        cameraScope.cancel()
        super.onDestroy()
    }
}