package com.wahana.plugingps

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.location.LocationManagerCompat
import com.getcapacitor.JSObject
import java.io.File

class WahanaPlugin {

    companion object {
        private val KNOWN_MOCK_PACKAGES = setOf(
            "com.lexa.fakegps",
            "com.incorporateapps.fakegps.fre",
            "com.incorporateapps.fakegps",
            "com.rosteam.gpsemulator",
            "com.location.mock",
            "com.fakegps.mock",
            "com.blogspot.newprocess.fakegps",
            "com.theappninjas.fakegpsjoystick",
            "com.theappninjas.gpsjoystick",
            "com.ninja.fakegps",
            "com.marlon.floating.fake.location",
            "com.gsmartstudio.fakegps",
            "com.fly.gps",
            "ru.gavrikov.mocklocations",
            "com.lkr.fakelocation",
            "com.app.fakelocation",
            "com.marlove.fackgps",
            "com.kfn.fakegps",
            "com.evezzon.fakegps",
            "com.nordeste.fakegps",
            "com.fakelocation.android",
            "com.cell411.fakegps",
            "com.change.location",
            "com.virtual.location",
            "com.ltp.pro.fakelocation",
            "com.fakegps.route",
            "com.pe.fakegps",
            "com.mock.gps",
            "org.hola.gps",
            "com.usefullapps.fakegpslocation",
            "cl.datacomputacion.fakelocation",
            "com.ilwt.fakegps",
            "com.blacksparrow.fakegps",
            "com.fakegps.location",
            "com.devpos.fakegps"
        )

        private val SUSPICIOUS_KEYWORDS = listOf(
            "fakegps",
            "fake.gps",
            "mocklocation",
            "mock.location",
            "gpsjoystick",
            "gps.joystick",
            "fakelocation",
            "fake.location",
            "location.spoofer",
            "gps.spoofer",
            "gpsemulator",
            "gps.emulator",
            "spoofgps",
            "spooflocation"
        )
    }

    /**
     * Checks if the device environment is compromised by root/hooking frameworks.
     */
    fun isSecurityCompromised(context: Context): Boolean {
        if (isHookingFrameworkDetected()) {
            return true
        }
        return false
    }

    /**
     * Comprehensive multi-layer detection for Fake GPS / Mock Location.
     * Evaluates Location object flags, real-time provider status,
     * cross-provider discrepancy, and root/hooking frameworks.
     * Blocks when Fake GPS is ACTIVELY SPOOFING / BEING USED.
     */
    fun isLocationMock(context: Context, location: Location?): Boolean {
        // 1. Direct Location Object Inspection (Kernel/OS level mock indicator)
        if (location != null && isLocationObjectMock(location)) {
            return true
        }

        // 2. Cross-Provider Discrepancy (GPS vs Network Cell Tower)
        if (location != null && isProviderDiscrepant(context, location)) {
            return true
        }

        // 3. Hooking / Root Framework Inspection (Smali Patcher, LSPosed, Magisk)
        if (isHookingFrameworkDetected()) {
            return true
        }

        return false
    }

    /**
     * Backward-compatible location mock check without Context.
     */
    fun isLocationMock(location: Location?): Boolean {
        if (location == null) return false
        return isLocationObjectMock(location)
    }

    /**
     * Inspects attributes of the Location instance itself.
     */
    fun isLocationObjectMock(location: Location): Boolean {
        // Android 12+ (API 31+) isMock method
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                if (location.isMock) return true
            } catch (_: Exception) {
            }
        }

        // Android 4.3+ (API 18+) isFromMockProvider
        try {
            @Suppress("DEPRECATION")
            if (location.isFromMockProvider) return true
        } catch (_: Exception) {
        }

        // Check Bundle Extras for mock indicators
        try {
            val extras = location.extras
            if (extras != null && extras.getBoolean("mockLocation", false)) {
                return true
            }
        } catch (_: Exception) {
        }

        // Check provider name
        val provider = location.provider
        if (provider != null) {
            val lower = provider.lowercase()
            if (lower == "mock" || lower == "test") {
                return true
            }
        }

        return false
    }

    /**
     * Checks if a mock location provider is active in system settings or AppOps.
     */
    fun isSystemMockProviderActive(context: Context): Boolean {
        // A. AppOps OPSTR_MOCK_LOCATION check
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        if (appOps != null) {
            try {
                val pm = context.packageManager

                // 1. Check self
                val selfMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    appOps.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_MOCK_LOCATION,
                        Process.myUid(),
                        context.packageName
                    )
                } else {
                    appOps.checkOpNoThrow(
                        AppOpsManager.OPSTR_MOCK_LOCATION,
                        Process.myUid(),
                        context.packageName
                    )
                }
                if (selfMode == AppOpsManager.MODE_ALLOWED) {
                    return true
                }

                // 2. Direct fast check for known mock packages in AppOps
                for (knownPkg in KNOWN_MOCK_PACKAGES) {
                    try {
                        val appInfo = pm.getApplicationInfo(knownPkg, 0)
                        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            appOps.unsafeCheckOpNoThrow(
                                AppOpsManager.OPSTR_MOCK_LOCATION,
                                appInfo.uid,
                                knownPkg
                            )
                        } else {
                            appOps.checkOpNoThrow(
                                AppOpsManager.OPSTR_MOCK_LOCATION,
                                appInfo.uid,
                                knownPkg
                            )
                        }
                        if (mode == AppOpsManager.MODE_ALLOWED) {
                            return true
                        }
                    } catch (_: PackageManager.NameNotFoundException) {
                    } catch (_: Exception) {
                    }
                }

                // 3. Scan installed applications for OPSTR_MOCK_LOCATION
                val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                for (appInfo in installedApps) {
                    if (appInfo.packageName == context.packageName) continue
                    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        appOps.unsafeCheckOpNoThrow(
                            AppOpsManager.OPSTR_MOCK_LOCATION,
                            appInfo.uid,
                            appInfo.packageName
                        )
                    } else {
                        appOps.checkOpNoThrow(
                            AppOpsManager.OPSTR_MOCK_LOCATION,
                            appInfo.uid,
                            appInfo.packageName
                        )
                    }
                    if (mode == AppOpsManager.MODE_ALLOWED) {
                        return true
                    }
                }
            } catch (_: Exception) {
            }
        }

        // B. Legacy ALLOW_MOCK_LOCATION setting (Android < 6.0)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            try {
                @Suppress("DEPRECATION")
                if (Settings.Secure.getInt(context.contentResolver, Settings.Secure.ALLOW_MOCK_LOCATION, 0) != 0) {
                    return true
                }
            } catch (_: Exception) {
            }
        }

        return false
    }

    /**
     * Scans for installed Fake GPS applications.
     */
    fun hasMockLocationAppsInstalled(context: Context): Boolean {
        val pm = context.packageManager

        // 1. Direct fast lookup for known mock packages
        for (knownPkg in KNOWN_MOCK_PACKAGES) {
            try {
                pm.getApplicationInfo(knownPkg, 0)
                return true
            } catch (_: PackageManager.NameNotFoundException) {
            } catch (_: Exception) {
            }
        }

        // 2. Scan installed packages for suspicious names or ACCESS_MOCK_LOCATION
        try {
            val packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            for (pkg in packages) {
                if (pkg.packageName == context.packageName) continue

                // Check known package blacklist
                if (KNOWN_MOCK_PACKAGES.contains(pkg.packageName)) {
                    return true
                }

                // Check suspicious keywords in package name
                val lowerPkg = pkg.packageName.lowercase()
                for (keyword in SUSPICIOUS_KEYWORDS) {
                    if (lowerPkg.contains(keyword)) {
                        return true
                    }
                }

                // Check if package requested ACCESS_MOCK_LOCATION permission
                val requestedPermissions = pkg.requestedPermissions ?: continue
                for (perm in requestedPermissions) {
                    if (perm == "android.permission.ACCESS_MOCK_LOCATION") {
                        return true
                    }
                }
            }
        } catch (_: Exception) {
        }
        return false
    }

    /**
     * Detects root binaries or runtime hooking frameworks (Xposed, LSPosed, Frida).
     */
    fun isHookingFrameworkDetected(): Boolean {
        // 1. Check common SU binaries
        val suPaths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su",
            "/system/xbin/daemonsu"
        )
        for (path in suPaths) {
            try {
                if (File(path).exists()) return true
            } catch (_: Exception) {
            }
        }

        // 2. Check Xposed / LSPosed classes
        val hookClasses = arrayOf(
            "de.robv.android.xposed.XposedBridge",
            "de.robv.android.xposed.XposedHelpers",
            "org.lsposed.lspd.nativebridge.NativeBridge",
            "me.weishu.exposed.ExposedBridge"
        )
        for (className in hookClasses) {
            try {
                Class.forName(className)
                return true
            } catch (_: ClassNotFoundException) {
            } catch (_: Exception) {
            }
        }

        // 3. Test-keys build tags
        try {
            val buildTags = Build.TAGS
            if (buildTags != null && buildTags.contains("test-keys")) {
                return true
            }
        } catch (_: Exception) {
        }

        return false
    }

    /**
     * Compares GPS provider against Network (cell tower / WiFi) provider.
     * If distance between them > 25km, location is almost certainly spoofed.
     */
    fun isProviderDiscrepant(context: Context, gpsLocation: Location): Boolean {
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return false
            if (!lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) return false

            @Suppress("MissingPermission")
            val networkLocation = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) ?: return false

            // Only compare if network location is recent (less than 15 minutes old)
            val ageDiff = Math.abs(gpsLocation.time - networkLocation.time)
            if (ageDiff > 15 * 60 * 1000) return false

            val distance = gpsLocation.distanceTo(networkLocation)
            if (distance > 25000f) {
                return true
            }
        } catch (_: Exception) {
        }
        return false
    }

    /**
     * Checks if location services (GPS) are enabled on the device.
     */
    fun isLocationEnabled(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return LocationManagerCompat.isLocationEnabled(locationManager)
    }

    /**
     * Formats an Android Location object into standard Capacitor Position JSObject.
     */
    fun formatLocation(location: Location?): JSObject {
        val result = JSObject()
        if (location == null) return result

        val coords = JSObject()
        coords.put("latitude", location.latitude)
        coords.put("longitude", location.longitude)
        coords.put("accuracy", location.accuracy.toDouble())

        if (location.hasAltitude()) {
            coords.put("altitude", location.altitude)
        } else {
            coords.put("altitude", null)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasVerticalAccuracy()) {
            coords.put("altitudeAccuracy", location.verticalAccuracyMeters.toDouble())
        } else {
            coords.put("altitudeAccuracy", null)
        }

        if (location.hasBearing()) {
            coords.put("heading", location.bearing.toDouble())
        } else {
            coords.put("heading", null)
        }

        if (location.hasSpeed()) {
            coords.put("speed", location.speed.toDouble())
        } else {
            coords.put("speed", null)
        }

        coords.put("isMock", isLocationMock(location))

        result.put("timestamp", location.time)
        result.put("coords", coords)
        return result
    }

    /**
     * Formats an Android Location object with Context for deep mock detection.
     */
    fun formatLocation(context: Context, location: Location?): JSObject {
        val result = formatLocation(location)
        if (location != null) {
            val coords = result.getJSObject("coords") ?: JSObject()
            coords.put("isMock", isLocationMock(context, location))
            result.put("coords", coords)
        }
        return result
    }

    /**
     * Computes the gating LocationStatus JSObject.
     * Usable is true ONLY IF enabled == true, granted == true, and isMock == false.
     */
    fun calculateStatus(enabled: Boolean, granted: Boolean, isMock: Boolean): JSObject {
        val usable = enabled && granted && !isMock
        return JSObject().apply {
            put("enabled", enabled)
            put("granted", granted)
            put("isMock", isMock)
            put("usable", usable)
        }
    }
}
