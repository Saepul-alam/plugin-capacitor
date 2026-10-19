package com.wahana.plugingps

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.location.LocationManagerCompat
import com.getcapacitor.JSObject

class WahanaPlugin {

    /**
     * Detects if the location is simulated / from a mock location app (Fake GPS).
     */
    fun isLocationMock(location: Location?): Boolean {
        if (location == null) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            location.isMock
        } else {
            @Suppress("DEPRECATION")
            location.isFromMockProvider
        }
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
