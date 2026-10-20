package com.wahana.plugingps;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import androidx.core.location.LocationManagerCompat;
import com.getcapacitor.JSObject;
import java.lang.reflect.Method;

public class WahanaPlugin {

    /**
     * Detects if the location is simulated / from a mock location app (Fake GPS).
     * Supports Android 12+ (via isMock) and older Android versions (via isFromMockProvider)
     * using reflection to maintain compile-time compatibility with compileSdkVersion 30.
     */
    public boolean isLocationMock(Location location) {
        if (location == null) return false;

        if (Build.VERSION.SDK_INT >= 31) {
            // Android 12 (S)
            try {
                Method isMockMethod = Location.class.getMethod("isMock");
                Object result = isMockMethod.invoke(location);
                if (result instanceof Boolean) {
                    return (Boolean) result;
                }
            } catch (Exception ignored) {}
        }

        // Fallback for Android < 12 and as safety on newer
        try {
            return location.isFromMockProvider();
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Checks if location services (GPS) are enabled on the device.
     */
    public boolean isLocationEnabled(Context context) {
        if (context == null) return false;
        LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) return false;
        return LocationManagerCompat.isLocationEnabled(locationManager);
    }

    /**
     * Formats an Android Location object into standard Capacitor Position JSObject.
     */
    public JSObject formatLocation(Location location) {
        JSObject result = new JSObject();
        if (location == null) return result;

        JSObject coords = new JSObject();
        coords.put("latitude", location.getLatitude());
        coords.put("longitude", location.getLongitude());
        coords.put("accuracy", (double) location.getAccuracy());

        if (location.hasAltitude()) {
            coords.put("altitude", location.getAltitude());
        } else {
            coords.put("altitude", null);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasVerticalAccuracy()) {
            coords.put("altitudeAccuracy", (double) location.getVerticalAccuracyMeters());
        } else {
            coords.put("altitudeAccuracy", null);
        }

        if (location.hasBearing()) {
            coords.put("heading", (double) location.getBearing());
        } else {
            coords.put("heading", null);
        }

        if (location.hasSpeed()) {
            coords.put("speed", (double) location.getSpeed());
        } else {
            coords.put("speed", null);
        }

        coords.put("isMock", isLocationMock(location));

        result.put("timestamp", location.getTime());
        result.put("coords", coords);
        return result;
    }

    /**
     * Computes the gating LocationStatus JSObject.
     * Usable is true ONLY IF enabled == true, granted == true, and isMock == false.
     */
    public JSObject calculateStatus(boolean enabled, boolean granted, boolean isMock) {
        boolean usable = enabled && granted && !isMock;
        JSObject status = new JSObject();
        status.put("enabled", enabled);
        status.put("granted", granted);
        status.put("isMock", isMock);
        status.put("usable", usable);
        return status;
    }
}
