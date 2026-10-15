package com.al.pluginsecure

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class BarcodeScanActivity : AppCompatActivity() {
    private val TAG = "BarcodeScanActivity"

    private lateinit var previewView: PreviewView
    private lateinit var tvPrompt: TextView
    private lateinit var btnTorch: ImageButton
    private lateinit var btnCancel: ImageButton

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var isTorchOn = false
    private val isScanned = AtomicBoolean(false)
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            finishWithError("Camera permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_barcode_scan)

        previewView = findViewById(R.id.previewView)
        tvPrompt = findViewById(R.id.tvPrompt)
        btnTorch = findViewById(R.id.btnTorch)
        btnCancel = findViewById(R.id.btnCancel)

        val promptText = intent.getStringExtra("prompt")
        if (!promptText.isNullOrEmpty()) {
            tvPrompt.text = promptText
        }

        btnCancel.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        btnTorch.setOnClickListener {
            toggleTorch()
        }

        checkPermissionAndStart()
    }

    private fun checkPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCamera()
            } catch (e: Exception) {
                Log.e(TAG, "Error starting camera", e)
                finishWithError("Failed to initialize camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_CODE_93,
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_DATA_MATRIX
            )
            .build()

        val scanner = BarcodeScanning.getClient(options)

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            processImageProxy(scanner, imageProxy)
        }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            camera = provider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalysis
            )
        } catch (e: Exception) {
            Log.e(TAG, "Binding use cases failed", e)
            finishWithError("Failed to bind camera use cases: ${e.message}")
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun processImageProxy(
        scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
        imageProxy: ImageProxy
    ) {
        val mediaImage = imageProxy.image
        if (mediaImage != null && !isScanned.get()) {
            val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            scanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    if (barcodes.isNotEmpty() && isScanned.compareAndSet(false, true)) {
                        val first = barcodes[0]
                        vibrateFeedback()
                        returnResult(first)
                    }
                }
                .addOnFailureListener {
                    // Ignore transient frame analysis errors
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    private fun vibrateFeedback() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(100)
                }
            }
        } catch (_: Exception) {}
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        if (cam.cameraInfo.hasFlashUnit()) {
            isTorchOn = !isTorchOn
            cam.cameraControl.enableTorch(isTorchOn)
        }
    }

    private fun returnResult(barcode: Barcode) {
        val rawValue = barcode.rawValue ?: ""
        val formatName = getFormatName(barcode.format)

        val resultJson = JSONObject().apply {
            put("hasContent", true)
            put("content", rawValue)
            put("format", formatName)
        }

        runOnUiThread {
            setResult(Activity.RESULT_OK, Intent().putExtra("result", resultJson.toString()))
            finish()
        }
    }

    private fun getFormatName(format: Int): String {
        return when (format) {
            Barcode.FORMAT_QR_CODE -> "QR_CODE"
            Barcode.FORMAT_CODE_128 -> "CODE_128"
            Barcode.FORMAT_CODE_39 -> "CODE_39"
            Barcode.FORMAT_CODE_93 -> "CODE_93"
            Barcode.FORMAT_EAN_13 -> "EAN_13"
            Barcode.FORMAT_EAN_8 -> "EAN_8"
            Barcode.FORMAT_UPC_A -> "UPC_A"
            Barcode.FORMAT_UPC_E -> "UPC_E"
            Barcode.FORMAT_DATA_MATRIX -> "DATA_MATRIX"
            else -> "OTHER"
        }
    }

    private fun finishWithError(message: String) {
        setResult(Activity.RESULT_CANCELED, Intent().putExtra("error", message))
        finish()
    }

    override fun onDestroy() {
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}

