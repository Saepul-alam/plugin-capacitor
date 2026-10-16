package com.al.pluginsecure

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.ActivityResult
import androidx.fragment.app.FragmentActivity
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

@CapacitorPlugin(
    name = "PluginSecure",
    permissions = [
        Permission(
            alias = "location",
            strings = [
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ]
        )
    ]
)
class PluginSecurePlugin : Plugin() {
    companion object {
        private const val LOCATION_ALIAS = "location"
        private const val LOCATION_PREFS = "plugin_secure_location_prefs"
        private const val KEY_REQUIRE_LOCATION = "require_location"

        // Registered once per process; detects when the user explicitly closes the
        // app so the preserved state is cleared and the next launch starts fresh.
        @Volatile
        private var closeObserver: Application.ActivityLifecycleCallbacks? = null
    }

    private val gpsHelper by lazy { GPSVerificationHelper(context) }
    private val biometricHelper by lazy { BiometricHelper(context) }
    private val stateHelper by lazy { SecureStateHelper(context) }
    private var errorOverlay: NativeErrorOverlay? = null
    private var monitoringJob: Job? = null

    private lateinit var locationPrefs: SharedPreferences
    private var requireLocation: Boolean = true

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

        // State preservation (default true): restore the last route on cold start,
        // except when the user explicitly closed the app (then start fresh).
        val statePreservation = try {
            getConfig().getBoolean("statePreservation", true)
        } catch (_: Exception) {
            true
        }
        stateHelper.setDefaultEnabled(statePreservation)

        registerCloseObserver()

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
            wv.webViewClient = SecureWebViewClient(existingClient, overlay, stateHelper)

            // Auto-restore previous state if available
            stateHelper.restoreToWebView(wv)
        }
    }

    /**
     * Registers an application-level lifecycle observer once per process.
     *
     * When the user explicitly closes the app (swipe from Recents, or back-exit
     * which calls finish()), the host Activity is destroyed with
     * `isFinishing == true`; we then clear the preserved state so the next launch
     * starts fresh. When the OS kills the process in the background there is no
     * destroy callback, so the state survives and the app resumes where it was.
     */
    private fun registerCloseObserver() {
        if (closeObserver != null) return
        val app = context.applicationContext as? Application ?: return
        val observer = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                if (activity.isFinishing && !activity.isChangingConfigurations) {
                    SecureStateHelper(activity.applicationContext).clearState()
                }
            }
        }
        app.registerActivityLifecycleCallbacks(observer)
        closeObserver = observer
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
        val intent = Intent(activity, CameraVerifiedActivity::class.java).apply {
            putExtra("options", call.data.toString())
        }
        // Use skeleton wrapper
        launchWithSkeleton(intent, "cameraResult", call)
    }

    @PluginMethod
    fun checkBiometricAvailability(call: PluginCall) {
        val avail = biometricHelper.checkAvailability()
        call.resolve(JSObject().apply {
            put("isAvailable", avail.isAvailable)
            put("biometryType", avail.biometryType)
            put("reason", avail.reason)
        })
    }

    @PluginMethod
    fun authenticateBiometric(call: PluginCall) {
        val act = activity as? FragmentActivity
        if (act == null) {
            call.reject(nativeError("Activity does not support FragmentActivity"))
            return
        }

        act.runOnUiThread {
            biometricHelper.authenticate(act, call.data) { success, error ->
                if (success) {
                    call.resolve(JSObject().apply { put("success", true) })
                } else {
                    call.resolve(
                        JSObject().apply {
                            put("success", false)
                            put("error", error ?: "Authentication failed")
                        }
                    )
                }
            }
        }
    }

    @PluginMethod
    fun scanBarcode(call: PluginCall) {
        val intent = Intent(activity, BarcodeScanActivity::class.java).apply {
            putExtra("prompt", call.getString("prompt"))
        }
        // Use skeleton wrapper
        launchWithSkeleton(intent, "barcodeResult", call)
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

    @ActivityCallback
    private fun barcodeResult(call: PluginCall?, result: ActivityResult) {
        if (call == null) return
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val resultStr = data.getStringExtra("result") ?: "{}"
            try {
                call.resolve(JSObject(resultStr))
            } catch (e: Exception) {
                call.reject(nativeError("Failed to parse barcode result: ${e.message}"))
            }
        } else {
            // No content case – resolve with empty structure (unchanged)
            call.resolve(JSObject().apply {
                put("hasContent", false)
                put("content", "")
                put("format", "NONE")
            })
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

    override fun handleOnPause() {
        bridge?.webView?.url?.let { stateHelper.saveState(it) }
        super.handleOnPause()
    }

    override fun handleOnStop() {
        bridge?.webView?.url?.let { stateHelper.saveState(it) }
        super.handleOnStop()
    }

    override fun handleOnDestroy() {
        monitoringJob?.cancel()
        monitoringJob = null
        gpsHelper.unregisterGnssListener()
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

    @PluginMethod
    fun saveAppState(call: PluginCall) {
        val url = call.getString("url") ?: bridge?.webView?.url
        val route = call.getString("route")
        val extraData = call.getObject("data")?.toString()
        stateHelper.saveState(url, route, extraData)
        call.resolve()
    }

    @PluginMethod
    fun getLastAppState(call: PluginCall) {
        val ret = JSObject().apply {
            put("lastUrl", stateHelper.getLastUrl())
            put("lastRoute", stateHelper.getLastRoute())
            put("timestamp", stateHelper.getLastTimestamp())
            put("extraData", stateHelper.getExtraData())
            put("isExpired", stateHelper.isExpired())
        }
        call.resolve(ret)
    }

    @PluginMethod
    fun enableStatePreservation(call: PluginCall) {
        val enabled = call.getBoolean("enabled", true) ?: true
        stateHelper.setEnabled(enabled)
        call.resolve()
    }

    @PluginMethod
    fun restoreLastAppState(call: PluginCall) {
        activity?.runOnUiThread {
            val restored = bridge?.webView?.let { stateHelper.restoreToWebView(it) } ?: false
            call.resolve(JSObject().apply { put("restored", restored) })
        }
    }

    @PluginMethod
    fun enableNativeFeel(call: PluginCall) {
        activity?.runOnUiThread {
            bridge?.webView?.let { tuneNativeFeel(it) }
            call.resolve()
        }
    }
}