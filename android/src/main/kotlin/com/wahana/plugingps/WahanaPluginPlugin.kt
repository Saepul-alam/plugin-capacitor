package com.wahana.plugingps

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.getcapacitor.JSObject
import com.getcapacitor.NativePlugin
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.concurrent.ConcurrentHashMap

@NativePlugin(
    name = "WahanaPlugin",
    requestCodes = [WahanaPluginPlugin.REQUEST_LOCATION_PERMISSION],
    permissions = [
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ]
)
class WahanaPluginPlugin : Plugin() {

    companion object {
        const val REQUEST_LOCATION_PERMISSION = 1000
    }

    private val implementation = WahanaPlugin()
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var providerReceiver: LocationProviderReceiver? = null
    private val watchCallbacks = ConcurrentHashMap<String, LocationCallback>()
    private var lastKnownLocation: Location? = null
    private var gatingDialog: android.app.AlertDialog? = null
    private var isPermissionRequestPending = false
    private var hasRequestedPermissionsOnce = false

    private val securityLocationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            lastKnownLocation = location
            val isMock = implementation.isLocationMock(context, location)
            if (isMock) {
                emitLocationStatusChanged()
                activity?.runOnUiThread {
                    showMockGatingDialog()
                }
            } else {
                if (gatingDialog?.isShowing == true) {
                    dismissNativeGatingDialog()
                    emitLocationStatusChanged()
                }
            }
        }
    }

    override fun load() {
        super.load()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

        providerReceiver = LocationProviderReceiver {
            emitLocationStatusChanged()
            activity?.runOnUiThread {
                checkAndEnforce(null)
            }
        }

        val filter = IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(providerReceiver, filter, 2)
        } else {
            context.registerReceiver(providerReceiver, filter)
        }

        // Start real-time security monitor
        startSecurityLocationWatch()

        // Check GPS, permission, and Fake GPS immediately
        activity?.runOnUiThread {
            checkAndEnforce(null)
        }

        // Inject safety hook into webview
        injectGeolocationSafetyHook()
    }

    override fun handleOnDestroy() {
        dismissNativeGatingDialog()
        providerReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) {
            }
            providerReceiver = null
        }

        try {
            fusedLocationClient.removeLocationUpdates(securityLocationCallback)
        } catch (_: Exception) {
        }

        for ((_, callback) in watchCallbacks) {
            try {
                fusedLocationClient.removeLocationUpdates(callback)
            } catch (_: Exception) {
            }
        }
        watchCallbacks.clear()
        super.handleOnDestroy()
    }

    override fun handleOnResume() {
        super.handleOnResume()
        if (isPermissionRequestPending) {
            return
        }
        activity?.runOnUiThread {
            checkAndEnforce(null)
        }
        startSecurityLocationWatch()
        injectGeolocationSafetyHook()
    }

    @SuppressLint("MissingPermission")
    private fun startSecurityLocationWatch() {
        if (!isLocationPermissionGranted()) return
        try {
            val request = LocationRequest.create().apply {
                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
                interval = 2000L
                fastestInterval = 1000L
                smallestDisplacement = 0f
            }
            fusedLocationClient.requestLocationUpdates(
                request,
                securityLocationCallback,
                Looper.getMainLooper()
            )
        } catch (_: Exception) {
        }
    }

    private fun injectGeolocationSafetyHook() {
        try {
            val webView = bridge?.webView ?: return
            val jsHook = """
                (function() {
                    if (window.__wahanaGeoHooked) return;
                    window.__wahanaGeoHooked = true;
                    if (!navigator.geolocation) return;
                    var _origGetCurrent = navigator.geolocation.getCurrentPosition.bind(navigator.geolocation);
                    navigator.geolocation.getCurrentPosition = function(success, error, options) {
                        if (window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.WahanaPlugin) {
                            window.Capacitor.Plugins.WahanaPlugin.checkLocationStatus().then(function(res) {
                                if (res && (!res.usable || res.isMock)) {
                                    if (error) {
                                        error({
                                            code: 1,
                                            message: 'Fake GPS terdeteksi aktif. Matikan Fake GPS untuk melanjutkan.'
                                        });
                                    }
                                } else {
                                    _origGetCurrent(success, error, options);
                                }
                            }).catch(function() {
                                _origGetCurrent(success, error, options);
                            });
                        } else {
                            _origGetCurrent(success, error, options);
                        }
                    };
                })();
            """.trimIndent()

            webView.post {
                webView.evaluateJavascript(jsHook, null)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Strictly requires ACCESS_FINE_LOCATION (Full / Precise Location).
     * Approximated location (COARSE only) is rejected to enforce full precision.
     */
    private fun isLocationPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isCoarseLocationGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    @PluginMethod
    fun getCurrentPosition(call: PluginCall) {
        if (!isLocationPermissionGranted()) {
            call.reject("Location permission not granted")
            return
        }

        if (!implementation.isLocationEnabled(context)) {
            call.reject("Location services (GPS) are disabled")
            return
        }

        if (implementation.isHookingFrameworkDetected()) {
            activity?.runOnUiThread {
                showMockGatingDialog()
            }
            emitLocationStatusChanged()
            call.reject("Aplikasi Fake GPS / Hooking terdeteksi aktif")
            return
        }

        val enableHighAccuracy = call.getBoolean("enableHighAccuracy", true) ?: true
        val priority = if (enableHighAccuracy) {
            LocationRequest.PRIORITY_HIGH_ACCURACY
        } else {
            LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY
        }

        val cancellationTokenSource = CancellationTokenSource()

        fusedLocationClient.getCurrentLocation(priority, cancellationTokenSource.token)
            .addOnSuccessListener { location: Location? ->
                if (location != null) {
                    lastKnownLocation = location
                    if (implementation.isLocationMock(context, location)) {
                        activity?.runOnUiThread {
                            showMockGatingDialog()
                        }
                        emitLocationStatusChanged()
                        call.reject("Aplikasi Fake GPS terdeteksi aktif")
                        return@addOnSuccessListener
                    }
                    dismissNativeGatingDialog()
                    call.resolve(implementation.formatLocation(context, location))
                } else {
                    fusedLocationClient.lastLocation.addOnSuccessListener { fallback: Location? ->
                        if (fallback != null) {
                            lastKnownLocation = fallback
                            if (implementation.isLocationMock(context, fallback)) {
                                activity?.runOnUiThread {
                                    showMockGatingDialog()
                                }
                                emitLocationStatusChanged()
                                call.reject("Aplikasi Fake GPS terdeteksi aktif")
                                return@addOnSuccessListener
                            }
                            dismissNativeGatingDialog()
                            call.resolve(implementation.formatLocation(context, fallback))
                        } else {
                            call.reject("Unable to get current location")
                        }
                    }.addOnFailureListener { e ->
                        call.reject("Unable to get current location: ${e.message}")
                    }
                }
            }
            .addOnFailureListener { e ->
                call.reject("Failed to obtain location: ${e.message}")
            }
    }

    @SuppressLint("MissingPermission")
    @PluginMethod(returnType = PluginMethod.RETURN_CALLBACK)
    fun watchPosition(call: PluginCall) {
        if (!isLocationPermissionGranted()) {
            call.reject("Location permission not granted")
            return
        }

        if (!implementation.isLocationEnabled(context)) {
            call.reject("Location services (GPS) are disabled")
            return
        }

        if (implementation.isHookingFrameworkDetected()) {
            activity?.runOnUiThread {
                showMockGatingDialog()
            }
            emitLocationStatusChanged()
            call.reject("Aplikasi Fake GPS / Hooking terdeteksi aktif")
            return
        }

        val interval = call.getInt("interval", 3000)?.toLong() ?: 3000L
        val fastestInterval = call.getInt("fastestInterval", 1000)?.toLong() ?: 1000L
        val minimumDistance = call.getFloat("minimumDistance", 0f) ?: 0f
        val enableHighAccuracy = call.getBoolean("enableHighAccuracy", true) ?: true

        val priority = if (enableHighAccuracy) {
            LocationRequest.PRIORITY_HIGH_ACCURACY
        } else {
            LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY
        }

        val locationRequest = LocationRequest.create().apply {
            this.priority = priority
            this.interval = interval
            this.fastestInterval = fastestInterval
            this.smallestDisplacement = minimumDistance
        }

        val callbackId = call.callbackId

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation ?: return
                lastKnownLocation = location
                val isMock = implementation.isLocationMock(context, location)
                if (isMock) {
                    activity?.runOnUiThread {
                        showMockGatingDialog()
                    }
                    emitLocationStatusChanged()
                    return
                }

                dismissNativeGatingDialog()
                val posData = implementation.formatLocation(context, location)
                notifyListeners("location", posData)
                call.resolve(posData)
            }
        }

        watchCallbacks[callbackId] = locationCallback
        call.setKeepAlive(true)

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    @PluginMethod
    fun stopWatch(call: PluginCall) {
        val id = call.getString("id") ?: call.callbackId
        val callback = watchCallbacks.remove(id)

        if (callback != null) {
            fusedLocationClient.removeLocationUpdates(callback)
            val savedCall = bridge.getSavedCall(id)
            if (savedCall != null) {
                savedCall.setKeepAlive(false)
                bridge.releaseCall(savedCall)
            }
        }

        call.resolve()
    }

    @PluginMethod
    fun isLocationEnabled(call: PluginCall) {
        val enabled = implementation.isLocationEnabled(context)
        val ret = JSObject().apply {
            put("enabled", enabled)
        }
        call.resolve(ret)
    }

    @PluginMethod
    fun checkLocationStatus(call: PluginCall) {
        val enabled = implementation.isLocationEnabled(context)
        val granted = isLocationPermissionGranted()
        val isMock = implementation.isLocationMock(context, lastKnownLocation)
        call.resolve(implementation.calculateStatus(enabled, granted, isMock))
    }

    @PluginMethod
    override fun checkPermissions(call: PluginCall) {
        val granted = isLocationPermissionGranted()
        val permString = if (granted) "granted" else "prompt"
        val ret = JSObject().apply {
            put("location", permString)
            put("coarseLocation", permString)
        }
        call.resolve(ret)
    }

    @PluginMethod
    override fun requestPermissions(call: PluginCall) {
        if (isLocationPermissionGranted()) {
            checkPermissions(call)
        } else {
            saveCall(call)
            pluginRequestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQUEST_LOCATION_PERMISSION
            )
        }
    }

    override fun handleRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>?,
        grantResults: IntArray?
    ) {
        super.handleRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION_PERMISSION) {
            isPermissionRequestPending = false
            hasRequestedPermissionsOnce = true
            emitLocationStatusChanged()
            val fineGranted = isLocationPermissionGranted()
            val saved = try { savedCall } catch (_: Exception) { null }

            if (!fineGranted) {
                val isCoarseOnly = isCoarseLocationGranted()
                val messageText = if (isCoarseOnly) {
                    "Aplikasi Wahana SWB membutuhkan akses izin lokasi penuh (presisi/tepat). Anda saat ini hanya memberikan izin perkiraan (approximate). Silakan ubah menjadi 'Lokasi Tepat' di Pengaturan Aplikasi untuk melanjutkan."
                } else {
                    "Aplikasi Wahana SWB tidak dapat digunakan tanpa izin lokasi penuh (presisi/tepat). Silakan aktifkan izin lokasi di Pengaturan Aplikasi untuk melanjutkan."
                }

                showNativeGatingDialog(
                    title = "Izin Lokasi Penuh Diperlukan",
                    message = messageText,
                    actionText = "Buka Pengaturan",
                    exitText = "Keluar",
                    onAction = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                    }
                )
                val status = implementation.calculateStatus(
                    enabled = implementation.isLocationEnabled(context),
                    granted = false,
                    isMock = false
                )
                saved?.resolve(status)
            } else {
                startSecurityLocationWatch()
                checkAndEnforce(saved)
            }
            if (saved != null) {
                try {
                    freeSavedCall()
                } catch (_: Exception) {
                }
            }
        }
    }

    @PluginMethod
    fun enforceLocation(call: PluginCall) {
        activity?.runOnUiThread {
            checkAndEnforce(call)
        }
    }

    @PluginMethod
    fun dismissGatingDialog(call: PluginCall) {
        dismissNativeGatingDialog()
        call.resolve()
    }

    private fun checkAndEnforce(call: PluginCall?) {
        val currentActivity = activity
        if (currentActivity == null || currentActivity.isFinishing) return

        val title = call?.getString("title") ?: "Akses Lokasi Wajib Aktif"
        val msgDenied = call?.getString("messagePermissionDenied")
            ?: "Aplikasi Wahana SWB membutuhkan izin lokasi presisi (akses penuh) agar dapat digunakan. Silakan berikan izin untuk melanjutkan."
        val msgGps = call?.getString("messageGpsDisabled")
            ?: "Layanan GPS perangkat Anda sedang mati. Aktifkan GPS untuk dapat menggunakan aplikasi."
        val msgMock = call?.getString("messageMockDetected")
            ?: "Aplikasi pemalsu lokasi (Fake GPS) terdeteksi aktif. Matikan Fake GPS untuk menggunakan aplikasi ini."
        val actionLabel = call?.getString("actionButtonLabel")
        val exitLabel = call?.getString("exitButtonLabel") ?: "Keluar"

        // 1. Cek izin lokasi penuh (ACCESS_FINE_LOCATION)
        if (!isLocationPermissionGranted()) {
            if (isPermissionRequestPending) {
                return
            }
            if (!hasRequestedPermissionsOnce && !isCoarseLocationGranted()) {
                isPermissionRequestPending = true
                if (call != null) {
                    saveCall(call)
                }
                pluginRequestPermissions(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ),
                    REQUEST_LOCATION_PERMISSION
                )
                return
            }

            val isCoarseOnly = isCoarseLocationGranted()
            val messageText = if (isCoarseOnly) {
                "Aplikasi Wahana SWB membutuhkan akses izin lokasi penuh (presisi/tepat). Anda saat ini hanya memberikan izin perkiraan (approximate). Silakan ubah menjadi 'Lokasi Tepat' di Pengaturan Aplikasi untuk melanjutkan."
            } else {
                msgDenied
            }

            showNativeGatingDialog(
                title = "Izin Lokasi Penuh Diperlukan",
                message = messageText,
                actionText = "Buka Pengaturan",
                exitText = exitLabel,
                onAction = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                }
            )
            val status = implementation.calculateStatus(
                enabled = implementation.isLocationEnabled(context),
                granted = false,
                isMock = false
            )
            notifyListeners("locationStatusChanged", status)
            call?.resolve(status)
            return
        }

        // 2. Cek apakah GPS perangkat mati
        if (!implementation.isLocationEnabled(context)) {
            showNativeGatingDialog(
                title = title,
                message = msgGps,
                actionText = actionLabel ?: "Nyalakan GPS",
                exitText = exitLabel,
                onAction = {
                    val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                }
            )
            val status = implementation.calculateStatus(enabled = false, granted = true, isMock = false)
            notifyListeners("locationStatusChanged", status)
            call?.resolve(status)
            return
        }

        // 3. Cek manipulasi Fake GPS yang aktif pada lastKnownLocation
        val isMock = lastKnownLocation != null && implementation.isLocationMock(context, lastKnownLocation)
        if (isMock) {
            showMockGatingDialog()
            val status = implementation.calculateStatus(enabled = true, granted = true, isMock = true)
            notifyListeners("locationStatusChanged", status)
            call?.resolve(status)
            return
        }

        // 4. Jika lastKnownLocation masih null, coba ambil lastLocation secara asynchronous untuk verifikasi
        if (lastKnownLocation == null) {
            try {
                fusedLocationClient.lastLocation.addOnSuccessListener { loc: Location? ->
                    if (loc != null) {
                        lastKnownLocation = loc
                        if (implementation.isLocationMock(context, loc)) {
                            activity?.runOnUiThread {
                                showMockGatingDialog()
                            }
                            emitLocationStatusChanged()
                        } else {
                            dismissNativeGatingDialog()
                            emitLocationStatusChanged()
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 5. Cek apakah ada hooking/root
        if (implementation.isHookingFrameworkDetected()) {
            showMockGatingDialog()
            return
        }

        // 6. Semua valid dan Fake GPS tidak sedang aktif: tutup dialog native gating jika ada
        dismissNativeGatingDialog()
        val status = implementation.calculateStatus(enabled = true, granted = true, isMock = false)
        notifyListeners("locationStatusChanged", status)
        call?.resolve(status)
    }

    private fun showMockGatingDialog() {
        showNativeGatingDialog(
            title = "Fake GPS Terdeteksi",
            message = "Aplikasi pemalsu lokasi (Fake GPS) terdeteksi aktif. Matikan Fake GPS untuk melanjutkan transaksi.",
            actionText = "Coba Lagi",
            exitText = "Keluar",
            onAction = {
                try {
                    fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                        if (loc != null) {
                            lastKnownLocation = loc
                        }
                        activity?.runOnUiThread {
                            checkAndEnforce(null)
                        }
                    }.addOnFailureListener {
                        activity?.runOnUiThread {
                            checkAndEnforce(null)
                        }
                    }
                } catch (_: Exception) {
                    activity?.runOnUiThread {
                        checkAndEnforce(null)
                    }
                }
            }
        )
    }

    private fun showNativeGatingDialog(
        title: String,
        message: String,
        actionText: String,
        exitText: String,
        onAction: () -> Unit
    ) {
        val currentActivity = activity ?: return
        if (currentActivity.isFinishing) return

        currentActivity.runOnUiThread {
            if (gatingDialog?.isShowing == true) {
                gatingDialog?.dismiss()
            }
            gatingDialog = android.app.AlertDialog.Builder(currentActivity)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton(actionText) { _, _ ->
                    onAction()
                }
                .setNegativeButton(exitText) { _, _ ->
                    currentActivity.finishAffinity()
                }
                .create()

            gatingDialog?.setCanceledOnTouchOutside(false)
            gatingDialog?.show()
        }
    }

    private fun dismissNativeGatingDialog() {
        activity?.runOnUiThread {
            if (gatingDialog?.isShowing == true) {
                gatingDialog?.dismiss()
            }
            gatingDialog = null
        }
    }

    @PluginMethod
    fun openLocationSettings(call: PluginCall) {
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        call.resolve()
    }

    @PluginMethod
    fun openAppSettings(call: PluginCall) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        call.resolve()
    }

    private fun emitLocationStatusChanged() {
        val enabled = implementation.isLocationEnabled(context)
        val granted = isLocationPermissionGranted()
        val isMock = implementation.isLocationMock(context, lastKnownLocation)
        notifyListeners("locationStatusChanged", implementation.calculateStatus(enabled, granted, isMock))
    }
}
