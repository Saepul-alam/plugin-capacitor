package com.wahana.plugingps

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.provider.Settings
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.concurrent.ConcurrentHashMap

@CapacitorPlugin(
    name = "WahanaPlugin",
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
class WahanaPluginPlugin : Plugin() {

    private val implementation = WahanaPlugin()
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var providerReceiver: LocationProviderReceiver? = null
    private val watchCallbacks = ConcurrentHashMap<String, LocationCallback>()
    private var lastKnownLocation: Location? = null

    override fun load() {
        super.load()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

        providerReceiver = LocationProviderReceiver {
            emitLocationStatusChanged()
        }

        val filter = IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(providerReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(providerReceiver, filter)
        }
    }

    override fun handleOnDestroy() {
        providerReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) {
            }
            providerReceiver = null
        }

        for ((_, callback) in watchCallbacks) {
            fusedLocationClient.removeLocationUpdates(callback)
        }
        watchCallbacks.clear()
        super.handleOnDestroy()
    }

    @SuppressLint("MissingPermission")
    @PluginMethod
    fun getCurrentPosition(call: PluginCall) {
        if (getPermissionState("location") != PermissionState.GRANTED) {
            call.reject("Location permission not granted")
            return
        }

        if (!implementation.isLocationEnabled(context)) {
            call.reject("Location services (GPS) are disabled")
            return
        }

        val enableHighAccuracy = call.getBoolean("enableHighAccuracy", true) ?: true
        val priority = if (enableHighAccuracy) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }

        val cancellationTokenSource = CancellationTokenSource()

        fusedLocationClient.getCurrentLocation(priority, cancellationTokenSource.token)
            .addOnSuccessListener { location: Location? ->
                if (location != null) {
                    lastKnownLocation = location
                    if (implementation.isLocationMock(location)) {
                        emitLocationStatusChanged()
                    }
                    call.resolve(implementation.formatLocation(location))
                } else {
                    // Fallback to last location
                    fusedLocationClient.lastLocation.addOnSuccessListener { fallback: Location? ->
                        if (fallback != null) {
                            lastKnownLocation = fallback
                            if (implementation.isLocationMock(fallback)) {
                                emitLocationStatusChanged()
                            }
                            call.resolve(implementation.formatLocation(fallback))
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
        if (getPermissionState("location") != PermissionState.GRANTED) {
            call.reject("Location permission not granted")
            return
        }

        if (!implementation.isLocationEnabled(context)) {
            call.reject("Location services (GPS) are disabled")
            return
        }

        val interval = call.getInt("interval", 3000)?.toLong() ?: 3000L
        val fastestInterval = call.getInt("fastestInterval", 1000)?.toLong() ?: 1000L
        val minimumDistance = call.getFloat("minimumDistance", 0f) ?: 0f
        val enableHighAccuracy = call.getBoolean("enableHighAccuracy", true) ?: true

        val priority = if (enableHighAccuracy) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }

        val locationRequest = LocationRequest.Builder(priority, interval)
            .setMinUpdateIntervalMillis(fastestInterval)
            .setMinUpdateDistanceMeters(minimumDistance)
            .build()

        val callbackId = call.callbackId

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation ?: return
                lastKnownLocation = location
                val isMock = implementation.isLocationMock(location)
                val posData = implementation.formatLocation(location)

                notifyListeners("location", posData)
                call.resolve(posData)

                if (isMock) {
                    emitLocationStatusChanged()
                }
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
        val granted = getPermissionState("location") == PermissionState.GRANTED
        val isMock = implementation.isLocationMock(lastKnownLocation)
        call.resolve(implementation.calculateStatus(enabled, granted, isMock))
    }

    @PluginMethod
    override fun requestPermissions(call: PluginCall) {
        if (getPermissionState("location") == PermissionState.GRANTED) {
            checkPermissions(call)
        } else {
            requestPermissionForAlias("location", call, "locationPermissionsCallback")
        }
    }

    @PermissionCallback
    private fun locationPermissionsCallback(call: PluginCall) {
        emitLocationStatusChanged()
        checkPermissions(call)
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
        val granted = getPermissionState("location") == PermissionState.GRANTED
        val isMock = implementation.isLocationMock(lastKnownLocation)
        notifyListeners("locationStatusChanged", implementation.calculateStatus(enabled, granted, isMock))
    }
}
