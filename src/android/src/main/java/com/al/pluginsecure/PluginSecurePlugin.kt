package com.al.pluginsecure

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.ActivityResult
import androidx.core.content.ContextCompat
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@CapacitorPlugin(
    name = "PluginSecure",
    permissions = [
        Permission(
            alias = "location",
            strings = [
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ]
        ),
        Permission(
            alias = "camera",
            strings = [
                Manifest.permission.CAMERA
            ]
        )
    ]
)
class PluginSecurePlugin : Plugin() {
    companion object {
        private const val LOCATION_ALIAS = "location"
        private const val CAMERA_ALIAS = "camera"
        private const val LOCATION_PREFS = "plugin_secure_location_prefs"
        private const val KEY_REQUIRE_LOCATION = "require_location"
        private const val KEY_LOCATION_WATCH = "location_watch"

        // Camera feature flags. All default to true (auto-on) and can be turned
        // off either from capacitor.config.json (plugins.PluginSecure.<flag>) or
        // at runtime via setCameraFeature({ feature, enabled }).
        private const val CAMERA_PREFS = "plugin_secure_camera_prefs"
        private const val KEY_CAMERA_OVERRIDE = "camera_override"
        private const val KEY_CAMERA_STREAM = "camera_stream"
        private const val KEY_CAPTURE_BURST = "capture_burst"
        private const val KEY_VIDEO_RECORDER = "video_recorder"
        private const val KEY_WATERMARK = "watermark"
    }

    private val gpsHelper by lazy { GPSVerificationHelper(context) }
    private var errorOverlay: NativeErrorOverlay? = null
    private var monitoringJob: Job? = null

    private lateinit var locationPrefs: SharedPreferences
    private var requireLocation: Boolean = true
    private var locationWatch: Boolean = true

    private lateinit var cameraPrefs: SharedPreferences
    private var cameraOverride: Boolean = true
    private var cameraStream: Boolean = true
    private var captureBurst: Boolean = true
    private var videoRecorder: Boolean = true
    private var watermark: Boolean = true

    override fun load() {
        super.load()

        locationPrefs = context.getSharedPreferences(LOCATION_PREFS, Context.MODE_PRIVATE)
        // Resolusi urutan: nilai tersimpan (runtime) -> config plugins.PluginSecure.requireLocation -> true
        val configDefault = try {
            getConfig().getBoolean("requireLocation", true)
        } catch (_: Exception) {
            true
        }
        requireLocation = locationPrefs.getBoolean(KEY_REQUIRE_LOCATION, configDefault)
        locationWatch = locationPrefs.getBoolean(KEY_LOCATION_WATCH, boolConfig("locationWatch", true))

        // Camera flags (default true). Resolution: stored runtime value -> config -> true.
        cameraPrefs = context.getSharedPreferences(CAMERA_PREFS, Context.MODE_PRIVATE)
        cameraOverride = cameraPrefs.getBoolean(KEY_CAMERA_OVERRIDE, boolConfig("cameraOverride", true))
        cameraStream = cameraPrefs.getBoolean(KEY_CAMERA_STREAM, boolConfig("cameraStream", true))
        captureBurst = cameraPrefs.getBoolean(KEY_CAPTURE_BURST, boolConfig("captureBurst", true))
        videoRecorder = cameraPrefs.getBoolean(KEY_VIDEO_RECORDER, boolConfig("videoRecorder", true))
        watermark = cameraPrefs.getBoolean(KEY_WATERMARK, boolConfig("watermark", true))

        val act = activity ?: return
        val wv = bridge?.webView ?: return

        act.runOnUiThread {
            // Tune WebView for native feel
            tuneNativeFeel(wv)

            // Attach native error overlay
            val overlay = NativeErrorOverlay(act, wv)
            errorOverlay = overlay

            // Wrap WebViewClient to intercept 404, connection & SSL errors
            val existingClient = getExistingWebViewClient(wv)
            wv.webViewClient = SecureWebViewClient(existingClient, overlay)
        }
    }

    private fun tuneNativeFeel(wv: WebView) {
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        wv.settings.apply {
            domStorageEnabled = true
            databaseEnabled = true
            setAppCacheEnabled(true)
            cacheMode = WebSettings.LOAD_DEFAULT
            setRenderPriority(WebSettings.RenderPriority.HIGH)
        }
    }

    private fun getExistingWebViewClient(webView: WebView): WebViewClient? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.webViewClient
        } else {
            try {
                val providerField = WebView::class.java.getDeclaredField("mProvider")
                providerField.isAccessible = true
                val provider = providerField.get(webView)
                val clientField = provider?.javaClass?.getDeclaredField("mWebViewClient")
                clientField?.isAccessible = true
                clientField?.get(provider) as? WebViewClient
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Helper to create a standardized native error JSON string */
    private fun nativeError(message: String): String {
        return JSObject().apply {
            put("nativeError", true)
            put("nativeMessage", "NativeError: $message")
        }.toString()
    }

    /** Launch an activity via SkeletonActivity to show placeholder UI */
    private fun launchWithSkeleton(intent: Intent, callbackName: String, call: PluginCall) {
        // Prepare intent for SkeletonActivity with the target intent as extra
        val skeletonIntent = Intent(activity, SkeletonActivity::class.java).apply {
            putExtra(SkeletonActivity.EXTRA_TARGET_INTENT, intent)
        }
        // Keep the call alive across the activity round-trip (Capacitor 3)
        call.setKeepAlive(true)
        // Start SkeletonActivity; it will forward to the target after a short delay
        startActivityForResult(call, skeletonIntent, callbackName)
    }

    @PluginMethod
    fun verifyGPS(call: PluginCall) {
        if (!ensureLocationPermission(call)) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = gpsHelper.verifyGPS()
                val ret = JSObject().apply {
                    put("isMock", result.isMock)
                    put("isJailbroken", result.isJailbroken)
                    put("details", result.details)
                }
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject(nativeError("GPS verification failed: ${e.message}"))
            }
        }
    }

    @PluginMethod
    fun checkDeviceIntegrity(call: PluginCall) {
        val result = gpsHelper.checkDeviceIntegrity()
        call.resolve(JSObject().apply {
            put("isRooted", result.isRooted)
            put("isJailbroken", result.isJailbroken)
            put("details", result.details)
        })
    }

    @PluginMethod
    fun captureVerifiedPhoto(call: PluginCall) {
        if (!ensureLocationPermission(call)) return
        // Merge config-driven defaults so the auto-on behaviour works even when
        // the caller (e.g. a plain Camera.getPhoto shim) passes no options.
        val merged = try {
            JSONObject(call.data.toString()).apply {
                if (!has("watermark")) put("watermark", watermark)
            }
        } catch (_: Exception) {
            call.data
        }
        val intent = Intent(activity, CameraVerifiedActivity::class.java).apply {
            putExtra("options", merged.toString())
        }
        // Use skeleton wrapper
        launchWithSkeleton(intent, "cameraResult", call)
    }

    @PluginMethod
    fun captureBurst(call: PluginCall) {
        if (!captureBurst) {
            call.reject(nativeError("Fitur captureBurst dimatikan"))
            return
        }
        if (!ensureLocationPermission(call)) return
        val count = (call.getInt("count") ?: 5).coerceIn(1, 20)
        val intervalMs = call.getInt("intervalMs") ?: 250
        val merged = try {
            JSONObject(call.data.toString())
        } catch (_: Exception) {
            JSONObject()
        }
        merged.put("burstCount", count)
        merged.put("burstIntervalMs", intervalMs)
        merged.put("resultType", "base64")
        if (!merged.has("direction")) merged.put("direction", call.getString("direction") ?: "front")
        if (!merged.has("watermark")) merged.put("watermark", watermark)

        val intent = Intent(activity, CameraVerifiedActivity::class.java).apply {
            putExtra("options", merged.toString())
        }
        launchWithSkeleton(intent, "cameraResult", call)
    }

    @ActivityCallback
    private fun cameraResult(call: PluginCall?, result: ActivityResult) {
        if (call == null) return
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val resultStr = data.getStringExtra("result") ?: "{}"
            try {
                call.resolve(JSObject(resultStr))
            } catch (e: Exception) {
                call.reject(nativeError("Failed to parse camera result: ${e.message}"))
            }
        } else {
            val error = data?.getStringExtra("error") ?: "Photo capture cancelled"
            call.reject(nativeError(error))
        }
    }

    @PluginMethod
    fun startGPSMonitoring(call: PluginCall) {
        if (!ensureLocationPermission(call)) return
        val intervalMs = call.getInt("intervalMs", 30000) ?: 30000
        monitoringJob?.cancel()

        monitoringJob = CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                try {
                    val result = gpsHelper.verifyGPS()
                    if (result.isMock) {
                        val ret = JSObject().apply {
                            put("isMock", result.isMock)
                            put("isJailbroken", result.isJailbroken)
                            put("details", result.details)
                        }
                        notifyListeners("gpsMockDetected", ret)
                    }
                } catch (_: Exception) {}
                delay(intervalMs.toLong())
            }
        }
        call.resolve()
    }

    @PluginMethod
    fun stopGPSMonitoring(call: PluginCall) {
        monitoringJob?.cancel()
        monitoringJob = null
        gpsHelper.unregisterGnssListener()
        call.resolve()
    }

    // --- Realtime location updates -----------------------------------------

    @PluginMethod
    fun startLocationUpdates(call: PluginCall) {
        if (!ensureLocationPermission(call)) return
        val intervalMs = (call.getInt("intervalMs", 2000) ?: 2000).toLong()
        val distanceFilter = call.getFloat("distanceFilter", 0f) ?: 0f
        val highAccuracy = call.getBoolean("highAccuracy", true) ?: true

        startTrackingService()
        gpsHelper.startLocationUpdates(intervalMs, distanceFilter, highAccuracy) { payload ->
            notifyListeners("locationUpdate", payload)
        }
        call.resolve()
    }

    @PluginMethod
    fun stopLocationUpdates(call: PluginCall) {
        gpsHelper.stopLocationUpdates()
        stopTrackingService()
        call.resolve()
    }

    @PluginMethod
    fun getCurrentLocation(call: PluginCall) {
        if (!ensureLocationPermission(call)) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val location = gpsHelper.getFreshLocation()
                if (location == null) {
                    call.reject(nativeError("Lokasi belum tersedia. Coba lagi di area terbuka."), "LOCATION_UNAVAILABLE")
                    return@launch
                }
                call.resolve(gpsHelper.buildLocationPayload(location))
            } catch (e: Exception) {
                call.reject(nativeError("Gagal mengambil lokasi: ${e.message}"))
            }
        }
    }

    private fun startTrackingService() {
        try {
            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = LocationTrackingService.ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        } catch (_: Exception) {
        }
    }

    private fun stopTrackingService() {
        try {
            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = LocationTrackingService.ACTION_STOP
            }
            context.startService(intent)
        } catch (_: Exception) {
        }
    }

    // --- Location permission gate ------------------------------------------

    /**
     * Returns true when the call may proceed. When location is required but not
     * yet granted, it triggers the OS permission prompt and keeps the call
     * alive; the result is delivered by [locationPermissionCallback].
     */
    private fun ensureLocationPermission(call: PluginCall): Boolean {
        if (!requireLocation) return true
        if (getPermissionState(LOCATION_ALIAS) == PermissionState.GRANTED) return true
        requestPermissionForAlias(LOCATION_ALIAS, call, "locationPermissionCallback")
        return false
    }

    @PermissionCallback
    private fun locationPermissionCallback(call: PluginCall) {
        val granted = getPermissionState(LOCATION_ALIAS) == PermissionState.GRANTED
        notifyListeners("locationPermissionChanged", JSObject().apply { put("granted", granted) })
        if (granted) {
            // Re-dispatch the original action now that permission is granted.
            when (call.methodName) {
                "verifyGPS" -> verifyGPS(call)
                "startGPSMonitoring" -> startGPSMonitoring(call)
                "captureVerifiedPhoto" -> captureVerifiedPhoto(call)
                else -> call.resolve(JSObject().apply { put("granted", true) })
            }
        } else {
            call.reject(
                "Izin lokasi diperlukan untuk menggunakan fitur ini. Aktifkan izin lokasi di pengaturan aplikasi.",
                "LOCATION_PERMISSION_DENIED"
            )
        }
    }

    @PluginMethod
    fun checkLocationPermission(call: PluginCall) {
        val state = getPermissionState(LOCATION_ALIAS)
        call.resolve(JSObject().apply {
            put("granted", state == PermissionState.GRANTED)
            put("state", state?.toString() ?: "prompt")
            put("required", requireLocation)
        })
    }

    @PluginMethod
    fun requestLocationPermission(call: PluginCall) {
        if (getPermissionState(LOCATION_ALIAS) == PermissionState.GRANTED) {
            call.resolve(JSObject().apply { put("granted", true) })
            return
        }
        requestPermissionForAlias(LOCATION_ALIAS, call, "locationPermissionCallback")
    }

    @PluginMethod
    fun isLocationRequired(call: PluginCall) {
        call.resolve(JSObject().apply { put("enabled", requireLocation) })
    }

    @PluginMethod
    fun setLocationRequired(call: PluginCall) {
        val enabled = call.getBoolean("enabled", true) ?: true
        requireLocation = enabled
        locationPrefs.edit().putBoolean(KEY_REQUIRE_LOCATION, enabled).apply()
        call.resolve(JSObject().apply { put("enabled", enabled) })
    }

    @PluginMethod
    fun getLocationConfig(call: PluginCall) {
        call.resolve(JSObject().apply { put("locationWatch", locationWatch) })
    }

    @PluginMethod
    fun setLocationFeature(call: PluginCall) {
        val feature = call.getString("feature")
        if (feature != "locationWatch") {
            call.reject(nativeError("Fitur lokasi tidak dikenal: $feature"))
            return
        }
        val enabled = call.getBoolean("enabled", true) ?: true
        locationWatch = enabled
        locationPrefs.edit().putBoolean(KEY_LOCATION_WATCH, enabled).apply()
        call.resolve(JSObject().apply { put("locationWatch", enabled) })
    }

    // --- Camera permission gate --------------------------------------------

    /**
     * Returns true when the call may proceed. When the camera permission is not
     * granted it triggers the OS prompt and keeps the call alive; the result is
     * delivered by [cameraPermissionCallback].
     */
    private fun ensureCameraPermission(call: PluginCall): Boolean {
        if (getPermissionState(CAMERA_ALIAS) == PermissionState.GRANTED) return true
        requestPermissionForAlias(CAMERA_ALIAS, call, "cameraPermissionCallback")
        return false
    }

    @PermissionCallback
    private fun cameraPermissionCallback(call: PluginCall) {
        val granted = getPermissionState(CAMERA_ALIAS) == PermissionState.GRANTED
        notifyListeners("cameraPermissionChanged", JSObject().apply { put("granted", granted) })
        if (granted) {
            // Re-dispatch the original action now that permission is granted.
            when (call.methodName) {
                "startCameraStream" -> startCameraStream(call)
                else -> call.resolve(JSObject().apply { put("granted", true) })
            }
        } else {
            call.reject(
                "Izin kamera diperlukan untuk menggunakan fitur ini. Aktifkan izin kamera di pengaturan aplikasi.",
                "CAMERA_PERMISSION_DENIED"
            )
        }
    }

    @PluginMethod
    fun checkCameraPermission(call: PluginCall) {
        val state = getPermissionState(CAMERA_ALIAS)
        call.resolve(JSObject().apply {
            put("granted", state == PermissionState.GRANTED)
            put("state", state?.toString() ?: "prompt")
        })
    }

    @PluginMethod
    fun requestCameraPermission(call: PluginCall) {
        if (getPermissionState(CAMERA_ALIAS) == PermissionState.GRANTED) {
            call.resolve(JSObject().apply { put("granted", true) })
            return
        }
        requestPermissionForAlias(CAMERA_ALIAS, call, "cameraPermissionCallback")
    }

    // --- Camera feature flags ----------------------------------------------

    private fun boolConfig(key: String, default: Boolean): Boolean = try {
        getConfig().getBoolean(key, default)
    } catch (_: Exception) {
        default
    }

    private fun cameraConfigJson(): JSObject = JSObject().apply {
        put("cameraOverride", cameraOverride)
        put("cameraStream", cameraStream)
        put("captureBurst", captureBurst)
        put("videoRecorder", videoRecorder)
        put("watermark", watermark)
    }

    @PluginMethod
    fun getCameraConfig(call: PluginCall) {
        call.resolve(cameraConfigJson())
    }

    @PluginMethod
    fun setCameraFeature(call: PluginCall) {
        val feature = call.getString("feature")
        if (feature == null) {
            call.reject(nativeError("Parameter 'feature' wajib diisi"))
            return
        }
        val enabled = call.getBoolean("enabled", true) ?: true
        when (feature) {
            "cameraOverride" -> {
                cameraOverride = enabled
                cameraPrefs.edit().putBoolean(KEY_CAMERA_OVERRIDE, enabled).apply()
            }
            "cameraStream" -> {
                cameraStream = enabled
                cameraPrefs.edit().putBoolean(KEY_CAMERA_STREAM, enabled).apply()
            }
            "captureBurst" -> {
                captureBurst = enabled
                cameraPrefs.edit().putBoolean(KEY_CAPTURE_BURST, enabled).apply()
            }
            "videoRecorder" -> {
                videoRecorder = enabled
                cameraPrefs.edit().putBoolean(KEY_VIDEO_RECORDER, enabled).apply()
            }
            "watermark" -> {
                watermark = enabled
                cameraPrefs.edit().putBoolean(KEY_WATERMARK, enabled).apply()
            }
            else -> {
                call.reject(nativeError("Fitur kamera tidak dikenal: $feature"))
                return
            }
        }
        call.resolve(cameraConfigJson())
    }

    @PluginMethod
    fun startCameraStream(call: PluginCall) {
        if (!cameraStream) {
            call.reject(nativeError("Fitur cameraStream dimatikan"))
            return
        }
        if (!ensureCameraPermission(call)) return
        val facingMode = call.getString("facingMode") ?: "environment"
        val width = call.getInt("width") ?: 640
        val height = call.getInt("height") ?: 480
        val fps = call.getInt("fps") ?: 15
        val quality = call.getInt("quality") ?: 70
        val torch = call.getBoolean("torch") ?: false

        val rotation = CameraStreamManager.start(context, facingMode, width, height, fps, quality, torch) { data ->
            notifyListeners("cameraFrame", data)
        }
        val swapped = rotation == 90 || rotation == 270
        call.resolve(JSObject().apply {
            put("started", true)
            put("rotation", rotation)
            put("width", if (swapped) height else width)
            put("height", if (swapped) width else height)
        })
    }

    @PluginMethod
    fun stopCameraStream(call: PluginCall) {
        CameraStreamManager.stop()
        call.resolve()
    }

    @PluginMethod
    fun setCameraTorch(call: PluginCall) {
        val enabled = call.getBoolean("enabled", false) ?: false
        CameraStreamManager.setTorch(enabled)
        call.resolve()
    }

    @PluginMethod
    fun startVideoRecording(call: PluginCall) {
        if (!videoRecorder) {
            call.reject(nativeError("Fitur videoRecorder dimatikan"))
            return
        }
        val intent = Intent(activity, VideoRecordActivity::class.java).apply {
            putExtra("facingMode", call.getString("facingMode") ?: "environment")
            putExtra("quality", call.getString("quality") ?: "high")
            putExtra("maxDurationMs", call.getLong("maxDurationMs") ?: 0L)
        }
        launchWithSkeleton(intent, "cameraResult", call)
    }

    @PluginMethod
    fun stopVideoRecording(call: PluginCall) {
        VideoRecordActivity.requestStop()
        call.resolve()
    }

    @PluginMethod
    fun openAppSettings(call: PluginCall) {
        try {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
            call.resolve()
        } catch (e: Exception) {
            call.reject(nativeError("Tidak dapat membuka pengaturan aplikasi: ${e.message}"))
        }
    }

    @PluginMethod
    fun openLocationSettings(call: PluginCall) {
        try {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
            call.resolve()
        } catch (e: Exception) {
            call.reject(nativeError("Tidak dapat membuka pengaturan lokasi: ${e.message}"))
        }
    }

    override fun handleOnDestroy() {
        monitoringJob?.cancel()
        monitoringJob = null
        gpsHelper.unregisterGnssListener()
        gpsHelper.stopLocationUpdates()
        stopTrackingService()
        CameraStreamManager.stop()
        super.handleOnDestroy()
    }

    @PluginMethod
    fun showNativeError(call: PluginCall) {
        val title = call.getString("title") ?: "Halaman Tidak Ditemukan"
        val message = call.getString("message") ?: "Halaman yang Anda tuju tidak dapat dimuat."
        val code = call.getString("code")
        val canRetry = call.getBoolean("canRetry", true) ?: true

        activity?.runOnUiThread {
            errorOverlay?.show(
                title = title,
                message = message,
                code = code,
                canRetry = canRetry,
                onRetry = {
                    notifyListeners("nativeErrorRetry", JSObject())
                    bridge?.webView?.reload()
                }
            )
            call.resolve()
        }
    }

    @PluginMethod
    fun hideNativeError(call: PluginCall) {
        activity?.runOnUiThread {
            errorOverlay?.hide()
            call.resolve()
        }
    }
}