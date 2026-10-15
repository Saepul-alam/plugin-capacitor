package com.al.pluginsecure

import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.getcapacitor.JSObject
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.concurrent.TimeUnit

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
        "com.fakegps.pro", "com.mock.gps"
    )

    private val rootPaths = arrayOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su",
        "/system/su", "/system/bin/.ext/.su", "/system/usr/we-need-root/su",
        "/system/app/Superuser.apk", "/system/app/SuperSU.apk",
        "/sbin/.magisk", "/data/adb/magisk", "/data/adb/ksu"
    )

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

    fun verifyGPS(): GPSVerificationResult {
        val mockEnabled = isMockLocationEnabled()
        val suspiciousApps = detectSuspiciousApps()
        val location = getLastKnownLocationSync()

        val isMock = mockEnabled
            || (location?.isFromMockProvider == true)
            || suspiciousApps.isNotEmpty()

        val details = JSObject().apply {
            put("mockLocationEnabled", mockEnabled)
            put("suspiciousApps", suspiciousApps)
            put("locationProvider", location?.provider ?: "none")
            put("accuracy", location?.accuracy ?: -1f)
            put("timestamp", location?.time ?: System.currentTimeMillis())
            put("isSimulator", isEmulator())
            put("rootPathsFound", checkRootPaths())
        }

        return GPSVerificationResult(isMock = isMock, details = details)
    }

    private fun isMockLocationEnabled(): Boolean {
        val mockSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ALLOW_MOCK_LOCATION
        )
        return "1" == mockSetting
    }

    private fun detectSuspiciousApps(): List<String> {
        val pm = context.packageManager
        return knownSpoofers.filter { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    private fun getLastKnownLocationSync(): Location? {
        return try {
            fusedLocationClient.lastLocation.await()
        } catch (e: Exception) {
            null
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
        return rootPaths.filter { File(it).exists() }
    }

    fun checkDeviceIntegrity(): DeviceIntegrityResult {
        val details = mutableListOf<String>()
        var isRooted = false

        val foundRootPaths = checkRootPaths()
        if (foundRootPaths.isNotEmpty()) {
            isRooted = true
            details.addAll(foundRootPaths)
        }

        if (Build.TAGS != null && Build.TAGS!!.contains("test-keys")) {
            isRooted = true
            details.add("Build tags: test-keys")
        }

        if (File("/proc/self/mounts").readText().contains("magisk") ||
            File("/proc/self/mounts").readText().contains("zygisk")) {
            isRooted = true
            details.add("Magisk/Zygisk detected in mounts")
        }

        return DeviceIntegrityResult(isRooted = isRooted, details = details)
    }
}