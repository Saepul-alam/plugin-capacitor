package com.al.pluginsecure

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import com.getcapacitor.JSObject
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class GPSVerificationHelper(private val context: Context) {
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val knownSpoofers = arrayOf(
        "com.lexa.fakegps", "com.incorporateapps.fakegps",
        "com.gsmartstudio.fakegps", "ru.gavrikov.fakegps",
        "com.androidoverload.fakegps", "com.fakegps.mocklocation",
        "com.techmini.fakegps", "org.abolfazl.fakegps",
        "com.fakegps.location", "fake.gps.location",
        "com.fakegps.free", "com.gps.fake",
        "org.abolfazl.fakegpsfree", "com.fakegps.mock",
        "com.fakegps.pro", "com.mock.gps",
        "com.theappninjas.fakegpsjoystick",
        "com.incorporateapps.fakegps_route",
        "com.marlove.mocklocation"
    )

    private val rootPaths = arrayOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su",
        "/system/su", "/system/bin/.ext/.su", "/system/usr/we-need-root/su",
        "/system/app/Superuser.apk", "/system/app/SuperSU.apk",
        "/sbin/.magisk", "/data/adb/magisk", "/data/adb/ksu",
        "/data/adb/ap", "/system/addon.d"
    )

    private var gnssSatellitesInFix = -1
    private var gnssTotalSatellites = -1
    private var gnssCallbackRegistered = false
    private var locationCallback: LocationCallback? = null

    private val gnssCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                val total = status.satelliteCount
                var inFix = 0
                for (i in 0 until total) {
                    if (status.usedInFix(i)) {
                        inFix++
                    }
                }
                gnssTotalSatellites = total
                gnssSatellitesInFix = inFix
            }
        }
    } else {
        null
    }

    data class GPSVerificationResult(
        val isMock: Boolean,
        val isJailbroken: Boolean = false,
        val details: JSObject = JSObject()
    )

    data class DeviceIntegrityResult(
        val isRooted: Boolean,
        val isJailbroken: Boolean = false,
        val details: MutableList<String> = mutableListOf()
    )

    fun registerGnssListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !gnssCallbackRegistered) {
            try {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                if (lm != null && gnssCallback != null) {
                    lm.registerGnssStatusCallback(gnssCallback as GnssStatus.Callback)
                    gnssCallbackRegistered = true
                }
            } catch (_: SecurityException) {
                // Permission not granted yet
            } catch (_: Exception) {}
        }
    }

    fun unregisterGnssListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && gnssCallbackRegistered) {
            try {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                if (lm != null && gnssCallback != null) {
                    lm.unregisterGnssStatusCallback(gnssCallback as GnssStatus.Callback)
                    gnssCallbackRegistered = false
                }
            } catch (_: Exception) {}
        }
    }

    suspend fun verifyGPS(): GPSVerificationResult {
        registerGnssListener()
        val mockSettingsEnabled = isLegacyMockLocationEnabled()
        val appOpsMockEnabled = isAppOpsMockEnabled()
        val suspiciousApps = detectSuspiciousApps()
        val location = getFreshLocation()
        val isLocationMock = isLocationMocked(location)
        val hookDetected = detectHooking()
        val rootPathsFound = checkRootPaths()
        val gnssAnomaly = checkGnssAnomaly(location)

        val isMock = mockSettingsEnabled
            || appOpsMockEnabled
            || isLocationMock
            || suspiciousApps.isNotEmpty()
            || gnssAnomaly

        val elapsedAgeMs = if (location != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
        } else {
            location?.let { System.currentTimeMillis() - it.time } ?: -1L
        }

        val details = JSObject().apply {
            put("mockLocationEnabled", mockSettingsEnabled || appOpsMockEnabled)
            put("isFromMockProvider", isLocationMock)
            put("appOpsMock", appOpsMockEnabled)
            put("suspiciousApps", suspiciousApps)
            put("locationProvider", location?.provider ?: "none")
            put("accuracy", location?.accuracy ?: -1f)
            put("timestamp", location?.time ?: System.currentTimeMillis())
            put("ageMs", elapsedAgeMs)
            put("isSimulator", isEmulator())
            put("satellitesInFix", gnssSatellitesInFix)
            put("totalSatellites", gnssTotalSatellites)
            put("rootPathsFound", rootPathsFound)
            put("hookingDetected", hookDetected)
            put("gnssAnomalyDetected", gnssAnomaly)
        }

        return GPSVerificationResult(isMock = isMock, details = details)
    }

    fun isLocationMocked(location: Location?): Boolean {
        if (location == null) return false

        // Android 12+ (API 31+) provides Location.isMock()
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                val method = Location::class.java.getMethod("isMock")
                val res = method.invoke(location) as? Boolean
                if (res == true) return true
            } catch (_: Exception) {}
        }

        // Android 4.3 - 11 (API 18 - 30)
        @Suppress("DEPRECATION")
        if (location.isFromMockProvider) {
            return true
        }

        // Check extras bundle for mockLocation flag
        try {
            val extras = location.extras
            if (extras != null && extras.getBoolean("mockLocation", false)) {
                return true
            }
        } catch (_: Exception) {}

        return false
    }

    private fun isAppOpsMockEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                if (appOps != null) {
                    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        appOps.unsafeCheckOpRaw(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName)
                    } else {
                        @Suppress("DEPRECATION")
                        appOps.checkOp(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName)
                    }
                    return mode == AppOpsManager.MODE_ALLOWED
                }
            } catch (_: Exception) {}
        }
        return false
    }

    private fun isLegacyMockLocationEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return try {
                val mockSetting = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ALLOW_MOCK_LOCATION
                )
                "1" == mockSetting
            } catch (_: Exception) {
                false
            }
        }
        return false
    }

    suspend fun getFreshLocation(): Location? {
        val freshLocation = withTimeoutOrNull(5000L) {
            try {
                val cts = CancellationTokenSource()
                fusedLocationClient.getCurrentLocation(
                    LocationRequest.PRIORITY_HIGH_ACCURACY,
                    cts.token
                ).await()
            } catch (_: Exception) {
                null
            }
        }

        return freshLocation ?: try {
            fusedLocationClient.lastLocation.await()
        } catch (_: Exception) {
            null
        }
    }

    /** Builds the JS payload emitted on every realtime location update. */
    fun buildLocationPayload(location: Location): JSObject {
        val ageMs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
        } else {
            System.currentTimeMillis() - location.time
        }
        return JSObject().apply {
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            put("altitude", location.altitude)
            put("accuracy", location.accuracy.toDouble())
            put("speed", location.speed.toDouble())
            put("heading", location.bearing.toDouble())
            put("timestamp", location.time)
            put("provider", location.provider ?: "unknown")
            put("ageMs", ageMs)
            put("isMock", isLocationMocked(location))
        }
    }

    /**
     * Starts continuous location updates. [onUpdate] receives a JS payload per
     * fix. Any previous registration is replaced.
     */
    @SuppressLint("MissingPermission")
    fun startLocationUpdates(
        intervalMs: Long,
        distanceFilter: Float,
        highAccuracy: Boolean,
        onUpdate: (JSObject) -> Unit
    ) {
        stopLocationUpdates()
        val interval = if (intervalMs > 0) intervalMs else 2000L
        val priority = if (highAccuracy) {
            LocationRequest.PRIORITY_HIGH_ACCURACY
        } else {
            LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY
        }
        val request = LocationRequest.create()
            .setInterval(interval)
            .setFastestInterval(interval)
            .setSmallestDisplacement(distanceFilter)
            .setPriority(priority)

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult?) {
                val location = result?.lastLocation ?: return
                onUpdate(buildLocationPayload(location))
            }
        }
        locationCallback = callback
        try {
            fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (_: Exception) {
            locationCallback = null
        }
    }

    fun stopLocationUpdates() {
        locationCallback?.let {
            try {
                fusedLocationClient.removeLocationUpdates(it)
            } catch (_: Exception) {
            }
        }
        locationCallback = null
    }

    private fun checkGnssAnomaly(location: Location?): Boolean {
        if (location == null) return false
        // Real GPS fix claiming high accuracy (< 15m) without any satellites used in fix is anomalous
        if (location.provider == LocationManager.GPS_PROVIDER && location.accuracy < 15f && gnssSatellitesInFix == 0) {
            return true
        }
        return false
    }

    private fun detectSuspiciousApps(): List<String> {
        val pm = context.packageManager
        return knownSpoofers.filter { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun isEmulator(): Boolean {
        return Build.FINGERPRINT.startsWith("generic")
            || Build.FINGERPRINT.startsWith("unknown")
            || Build.MODEL.contains("google_sdk")
            || Build.MODEL.contains("Emulator")
            || Build.MODEL.contains("Android SDK built for x86")
            || Build.MANUFACTURER.contains("Genymotion")
            || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
            || "google_sdk".equals(Build.PRODUCT)
    }

    private fun checkRootPaths(): List<String> {
        return rootPaths.filter {
            try {
                File(it).exists()
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun detectHooking(): Boolean {
        // Stack trace inspection for hooking frameworks
        try {
            throw Exception("DetectHook")
        } catch (e: Exception) {
            for (element in e.stackTrace) {
                val cls = element.className.lowercase()
                if (cls.contains("xposed") || cls.contains("lsposed") || cls.contains("epic") || cls.contains("substrate")) {
                    return true
                }
            }
        }

        // Memory maps check for loaded hooking libraries
        try {
            val mapsFile = File("/proc/self/maps")
            if (mapsFile.exists()) {
                mapsFile.useLines { lines ->
                    for (line in lines) {
                        val lower = line.lowercase()
                        if (lower.contains("xposed") || lower.contains("lsposed") || lower.contains("frida") || lower.contains("substrate")) {
                            return true
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return false
    }

    fun checkDeviceIntegrity(): DeviceIntegrityResult {
        val details = mutableListOf<String>()
        var isRooted = false

        val foundRootPaths = checkRootPaths()
        if (foundRootPaths.isNotEmpty()) {
            isRooted = true
            details.addAll(foundRootPaths)
        }

        if (Build.TAGS != null && Build.TAGS.contains("test-keys")) {
            isRooted = true
            details.add("Build tags: test-keys")
        }

        try {
            val mounts = File("/proc/self/mounts")
            if (mounts.exists()) {
                val content = mounts.readText().lowercase()
                if (content.contains("magisk") || content.contains("zygisk") || content.contains("core/mirror")) {
                    isRooted = true
                    details.add("Magisk/Zygisk detected in mounts")
                }
            }
        } catch (_: Exception) {}

        if (detectHooking()) {
            isRooted = true
            details.add("Hooking framework detected (Xposed/LSPosed/Frida)")
        }

        return DeviceIntegrityResult(isRooted = isRooted, details = details)
    }
}