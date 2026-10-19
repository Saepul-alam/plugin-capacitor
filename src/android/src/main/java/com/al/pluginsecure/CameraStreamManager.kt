package com.al.pluginsecure

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.getcapacitor.JSObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

// Headless camera preview stream. Frames are pushed to JS through the
// "cameraFrame" event so the web layer can render them into a canvas
// (getUserMedia polyfill). Runs without any Activity of its own.
object CameraStreamManager {
    private const val TAG = "CameraStreamManager"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private var lifecycleOwner: StreamLifecycleOwner? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var analysis: ImageAnalysis? = null
    private var lastFrameAt = 0L
    private var fps = 15
    private var jpegQuality = 70

    @Volatile
    private var rotationDegrees = 0

    @Volatile
    private var torchAvailable = false

    @Volatile
    private var running = false

    @Volatile
    var onFrame: ((JSObject) -> Unit)? = null

    fun isRunning(): Boolean = running

    fun currentRotation(): Int = rotationDegrees

    fun start(
        context: Context,
        facingMode: String,
        width: Int,
        height: Int,
        fps: Int,
        quality: Int,
        torch: Boolean,
        callback: (JSObject) -> Unit
    ): Int {
        this.fps = fps.coerceIn(1, 30)
        this.jpegQuality = quality.coerceIn(20, 100)
        this.onFrame = callback

        val initialRotation = surfaceRotationToDegrees(displaySurfaceRotation(context))
        rotationDegrees = initialRotation

        mainHandler.post {
            try {
                if (running) {
                    applyTorch(torch)
                    return@post
                }

                val owner = StreamLifecycleOwner()
                lifecycleOwner = owner
                owner.markResumed()

                val future = ProcessCameraProvider.getInstance(context)
                future.addListener({
                    try {
                        val provider = future.get()
                        cameraProvider = provider

                        val selector = CameraSelector.Builder()
                            .requireLensFacing(
                                if (facingMode == "user") CameraSelector.LENS_FACING_FRONT
                                else CameraSelector.LENS_FACING_BACK
                            )
                            .build()

                        val surfaceRotation = displaySurfaceRotation(context)
                        rotationDegrees = surfaceRotationToDegrees(surfaceRotation)

                        val imageAnalysis = ImageAnalysis.Builder()
                            .setTargetResolution(Size(width, height))
                            .setTargetRotation(surfaceRotation)
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        imageAnalysis.setAnalyzer(analysisExecutor) { proxy -> processFrame(proxy) }
                        analysis = imageAnalysis

                        camera = provider.bindToLifecycle(owner, selector, imageAnalysis)
                        torchAvailable = camera?.cameraInfo?.hasFlashUnit() == true
                        running = true
                        if (torch) applyTorch(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "start failed", e)
                        stopInternal()
                    }
                }, ContextCompat.getMainExecutor(context))
            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
                stopInternal()
            }
        }

        return initialRotation
    }

    fun stop() {
        mainHandler.post { stopInternal() }
    }

    fun setTorch(enabled: Boolean) {
        mainHandler.post { applyTorch(enabled) }
    }

    private fun stopInternal() {
        try {
            camera?.cameraControl?.enableTorch(false)
        } catch (_: Exception) {
        }
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
        analysis = null
        camera = null
        running = false
        torchAvailable = false
        try {
            lifecycleOwner?.markDestroyed()
        } catch (_: Exception) {
        }
        lifecycleOwner = null
    }

    private fun applyTorch(enabled: Boolean) {
        try {
            camera?.cameraControl?.enableTorch(enabled)
        } catch (e: Exception) {
            Log.e(TAG, "torch failed", e)
        }
    }

    private fun displaySurfaceRotation(context: Context): Int {
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            wm.defaultDisplay.rotation
        } catch (e: Exception) {
            0
        }
    }

    private fun surfaceRotationToDegrees(surfaceRotation: Int): Int {
        return when (surfaceRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    private fun processFrame(proxy: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            val minGap = 1000L / fps
            if (now - lastFrameAt < minGap) {
                return
            }
            lastFrameAt = now

            val rotation = proxy.imageInfo.rotationDegrees
            rotationDegrees = rotation

            val jpeg = yuvToJpeg(proxy)
            if (jpeg == null || jpeg.isEmpty()) {
                return
            }
            val data = JSObject().apply {
                put("base64", Base64.encodeToString(jpeg, Base64.NO_WRAP))
                put("timestamp", now)
                put("rotation", rotation)
                put("frameWidth", proxy.width)
                put("frameHeight", proxy.height)
                put("torch", torchAvailable)
            }
            onFrame?.invoke(data)
        } catch (e: Exception) {
            Log.e(TAG, "frame failed", e)
        } finally {
            proxy.close()
        }
    }

    private fun yuvToJpeg(proxy: ImageProxy): ByteArray? {
        val planes = proxy.planes
        if (planes.size < 3) {
            return null
        }
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val width = proxy.width
        val height = proxy.height

        val nv21 = ByteArray(width * height * 3 / 2)
        var pos = 0

        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        for (row in 0 until height) {
            yBuffer.position(row * yRowStride)
            yBuffer.get(nv21, pos, width)
            pos += width
        }

        val chromaHeight = height / 2
        val chromaWidth = width / 2
        val vBuffer = vPlane.buffer
        val uBuffer = uPlane.buffer
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                nv21[pos++] = vBuffer.get(row * vRowStride + col * vPixelStride)
                nv21[pos++] = uBuffer.get(row * uRowStride + col * uPixelStride)
            }
        }

        val out = ByteArrayOutputStream()
        val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        if (!yuv.compressToJpeg(Rect(0, 0, width, height), jpegQuality, out)) {
            return null
        }
        return out.toByteArray()
    }

    private class StreamLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)

        override fun getLifecycle(): Lifecycle = registry

        fun markResumed() {
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun markDestroyed() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }
}
