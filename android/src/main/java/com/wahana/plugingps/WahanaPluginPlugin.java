package com.wahana.plugingps;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Looper;
import android.provider.Settings;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.NativePlugin;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@NativePlugin(
    name = "WahanaPlugin",
    requestCodes = { WahanaPluginPlugin.REQUEST_LOCATION_PERMISSION },
    permissions = { Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION }
)
public class WahanaPluginPlugin extends Plugin {

    public static final int REQUEST_LOCATION_PERMISSION = 1000;

    private final WahanaPlugin implementation = new WahanaPlugin();
    private FusedLocationProviderClient fusedLocationClient;
    private LocationProviderReceiver providerReceiver;
    private final ConcurrentHashMap<String, LocationCallback> watchCallbacks = new ConcurrentHashMap<>();
    private Location lastKnownLocation;
    private AlertDialog gatingDialog;

    @Override
    public void load() {
        super.load();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(getContext());

        providerReceiver = new LocationProviderReceiver(
            new LocationProviderReceiver.ProviderChangeListener() {
                @Override
                public void onProviderChanged() {
                    emitLocationStatusChanged();
                    Activity currentActivity = getActivity();
                    if (currentActivity != null) {
                        currentActivity.runOnUiThread(
                            new Runnable() {
                                @Override
                                public void run() {
                                    checkAndEnforce(null);
                                }
                            }
                        );
                    }
                }
            }
        );

        IntentFilter filter = new IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            getContext().registerReceiver(providerReceiver, filter, 2); // Context.RECEIVER_EXPORTED
        } else {
            getContext().registerReceiver(providerReceiver, filter);
        }
    }

    @Override
    protected void handleOnDestroy() {
        dismissNativeGatingDialog();
        if (providerReceiver != null) {
            try {
                getContext().unregisterReceiver(providerReceiver);
            } catch (Exception ignored) {}
            providerReceiver = null;
        }

        for (Map.Entry<String, LocationCallback> entry : watchCallbacks.entrySet()) {
            fusedLocationClient.removeLocationUpdates(entry.getValue());
        }
        watchCallbacks.clear();
        super.handleOnDestroy();
    }

    @Override
    protected void handleOnResume() {
        super.handleOnResume();
        Activity currentActivity = getActivity();
        if (currentActivity != null) {
            currentActivity.runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        if (
                            isLocationPermissionGranted() &&
                            implementation.isLocationEnabled(getContext()) &&
                            !implementation.isLocationMock(lastKnownLocation)
                        ) {
                            dismissNativeGatingDialog();
                            emitLocationStatusChanged();
                        } else if (gatingDialog != null && gatingDialog.isShowing()) {
                            checkAndEnforce(null);
                        }
                    }
                }
            );
        }
    }

    private boolean isLocationPermissionGranted() {
        boolean fine =
            ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        boolean coarse =
            ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED;

        return fine || coarse;
    }

    @SuppressLint("MissingPermission")
    @PluginMethod
    public void getCurrentPosition(final PluginCall call) {
        if (!isLocationPermissionGranted()) {
            call.reject("Location permission not granted");
            return;
        }

        if (!implementation.isLocationEnabled(getContext())) {
            call.reject("Location services (GPS) are disabled");
            return;
        }

        boolean enableHighAccuracy = call.getBoolean("enableHighAccuracy", true);
        int priority = enableHighAccuracy ? LocationRequest.PRIORITY_HIGH_ACCURACY : LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY;

        CancellationTokenSource cancellationTokenSource = new CancellationTokenSource();

        fusedLocationClient
            .getCurrentLocation(priority, cancellationTokenSource.getToken())
            .addOnSuccessListener(
                new OnSuccessListener<Location>() {
                    @Override
                    public void onSuccess(final Location location) {
                        if (location != null) {
                            lastKnownLocation = location;
                            if (implementation.isLocationMock(location)) {
                                emitLocationStatusChanged();
                            }
                            call.resolve(implementation.formatLocation(location));
                        } else {
                            fusedLocationClient
                                .getLastLocation()
                                .addOnSuccessListener(
                                    new OnSuccessListener<Location>() {
                                        @Override
                                        public void onSuccess(Location fallback) {
                                            if (fallback != null) {
                                                lastKnownLocation = fallback;
                                                if (implementation.isLocationMock(fallback)) {
                                                    emitLocationStatusChanged();
                                                }
                                                call.resolve(implementation.formatLocation(fallback));
                                            } else {
                                                call.reject("Unable to get current location");
                                            }
                                        }
                                    }
                                )
                                .addOnFailureListener(
                                    new OnFailureListener() {
                                        @Override
                                        public void onFailure(@NonNull Exception e) {
                                            call.reject("Unable to get current location: " + e.getMessage());
                                        }
                                    }
                                );
                        }
                    }
                }
            )
            .addOnFailureListener(
                new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        call.reject("Failed to obtain location: " + e.getMessage());
                    }
                }
            );
    }

    @SuppressLint("MissingPermission")
    @PluginMethod(returnType = PluginMethod.RETURN_CALLBACK)
    public void watchPosition(final PluginCall call) {
        if (!isLocationPermissionGranted()) {
            call.reject("Location permission not granted");
            return;
        }

        if (!implementation.isLocationEnabled(getContext())) {
            call.reject("Location services (GPS) are disabled");
            return;
        }

        long interval = call.getInt("interval", 3000);
        long fastestInterval = call.getInt("fastestInterval", 1000);
        float minimumDistance = call.getFloat("minimumDistance", 0f);
        boolean enableHighAccuracy = call.getBoolean("enableHighAccuracy", true);

        int priority = enableHighAccuracy ? LocationRequest.PRIORITY_HIGH_ACCURACY : LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY;

        LocationRequest locationRequest = LocationRequest.create();
        locationRequest.setPriority(priority);
        locationRequest.setInterval(interval);
        locationRequest.setFastestInterval(fastestInterval);
        locationRequest.setSmallestDisplacement(minimumDistance);

        final String callbackId = call.getCallbackId();

        LocationCallback locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                Location location = locationResult.getLastLocation();
                if (location == null) return;

                lastKnownLocation = location;
                boolean isMock = implementation.isLocationMock(location);
                JSObject posData = implementation.formatLocation(location);

                notifyListeners("location", posData);
                call.resolve(posData);

                if (isMock) {
                    emitLocationStatusChanged();
                }
            }
        };

        watchCallbacks.put(callbackId, locationCallback);
        call.setKeepAlive(true);

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
    }

    @PluginMethod
    public void stopWatch(PluginCall call) {
        String id = call.hasOption("id") ? call.getString("id") : call.getCallbackId();
        LocationCallback callback = watchCallbacks.remove(id);

        if (callback != null) {
            fusedLocationClient.removeLocationUpdates(callback);
            PluginCall savedCall = bridge.getSavedCall(id);
            if (savedCall != null) {
                savedCall.setKeepAlive(false);
                bridge.releaseCall(savedCall);
            }
        }

        call.resolve();
    }

    @PluginMethod
    public void isLocationEnabled(PluginCall call) {
        boolean enabled = implementation.isLocationEnabled(getContext());
        JSObject ret = new JSObject();
        ret.put("enabled", enabled);
        call.resolve(ret);
    }

    @PluginMethod
    public void checkLocationStatus(PluginCall call) {
        boolean enabled = implementation.isLocationEnabled(getContext());
        boolean granted = isLocationPermissionGranted();
        boolean isMock = implementation.isLocationMock(lastKnownLocation);
        call.resolve(implementation.calculateStatus(enabled, granted, isMock));
    }

    @PluginMethod
    @Override
    public void checkPermissions(PluginCall call) {
        boolean granted = isLocationPermissionGranted();
        String permString = granted ? "granted" : "prompt";
        JSObject ret = new JSObject();
        ret.put("location", permString);
        ret.put("coarseLocation", permString);
        call.resolve(ret);
    }

    @PluginMethod
    @Override
    public void requestPermissions(PluginCall call) {
        if (isLocationPermissionGranted()) {
            checkPermissions(call);
        } else {
            saveCall(call);
            pluginRequestPermissions(
                new String[] { Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION },
                REQUEST_LOCATION_PERMISSION
            );
        }
    }

    @Override
    protected void handleRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.handleRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCATION_PERMISSION) {
            emitLocationStatusChanged();
            boolean granted = isLocationPermissionGranted();
            PluginCall saved = getSavedCall();

            if (!granted) {
                // Izin DITOLAK! Langsung kunci layar dengan Native AlertDialog agar aplikasi tidak bisa dipakai
                showNativeGatingDialog(
                    "Izin Lokasi Diperlukan",
                    "Aplikasi Wahana SWB tidak dapat digunakan tanpa izin lokasi presisi. Silakan berikan izin di Pengaturan Aplikasi untuk melanjutkan.",
                    "Buka Pengaturan",
                    "Keluar",
                    new Runnable() {
                        @Override
                        public void run() {
                            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                            intent.setData(Uri.fromParts("package", getContext().getPackageName(), null));
                            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            getContext().startActivity(intent);
                        }
                    }
                );
                if (saved != null) {
                    JSObject status = implementation.calculateStatus(implementation.isLocationEnabled(getContext()), false, false);
                    saved.resolve(status);
                }
            } else {
                // Izin DIBERIKAN! Lanjut cek status GPS & Fake GPS
                checkAndEnforce(saved);
            }
            freeSavedCall();
        }
    }

    @PluginMethod
    public void enforceLocation(final PluginCall call) {
        Activity currentActivity = getActivity();
        if (currentActivity != null) {
            currentActivity.runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        checkAndEnforce(call);
                    }
                }
            );
        }
    }

    @PluginMethod
    public void dismissGatingDialog(PluginCall call) {
        dismissNativeGatingDialog();
        call.resolve();
    }

    private void checkAndEnforce(final PluginCall call) {
        final Activity currentActivity = getActivity();
        if (currentActivity == null || currentActivity.isFinishing()) return;

        final String title = call != null && call.hasOption("title") ? call.getString("title") : "Akses Lokasi Wajib Aktif";
        final String msgDenied =
            call != null && call.hasOption("messagePermissionDenied")
                ? call.getString("messagePermissionDenied")
                : "Aplikasi Wahana SWB membutuhkan izin lokasi presisi agar dapat digunakan. Silakan berikan izin untuk melanjutkan.";
        final String msgGps =
            call != null && call.hasOption("messageGpsDisabled")
                ? call.getString("messageGpsDisabled")
                : "Layanan GPS perangkat Anda sedang mati. Aktifkan GPS untuk dapat menggunakan aplikasi.";
        final String msgMock =
            call != null && call.hasOption("messageMockDetected")
                ? call.getString("messageMockDetected")
                : "Aplikasi pemalsu lokasi (Fake GPS) terdeteksi aktif. Matikan Fake GPS untuk melanjutkan transaksi.";
        final String actionLabel = call != null && call.hasOption("actionButtonLabel") ? call.getString("actionButtonLabel") : null;
        final String exitLabel = call != null && call.hasOption("exitButtonLabel") ? call.getString("exitButtonLabel") : "Keluar";

        // 1. Cek izin lokasi
        if (!isLocationPermissionGranted()) {
            if (call != null) {
                saveCall(call);
            }
            pluginRequestPermissions(
                new String[] { Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION },
                REQUEST_LOCATION_PERMISSION
            );
            return;
        }

        // 2. Cek apakah GPS perangkat mati
        if (!implementation.isLocationEnabled(getContext())) {
            showNativeGatingDialog(
                title,
                msgGps,
                actionLabel != null ? actionLabel : "Nyalakan GPS",
                exitLabel,
                new Runnable() {
                    @Override
                    public void run() {
                        Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        getContext().startActivity(intent);
                    }
                }
            );
            JSObject status = implementation.calculateStatus(false, true, false);
            notifyListeners("locationStatusChanged", status);
            if (call != null) {
                call.resolve(status);
            }
            return;
        }

        // 3. Cek manipulasi Fake GPS
        boolean isMock = implementation.isLocationMock(lastKnownLocation);
        if (isMock) {
            showNativeGatingDialog(
                title,
                msgMock,
                actionLabel != null ? actionLabel : "Coba Lagi",
                exitLabel,
                new Runnable() {
                    @Override
                    public void run() {
                        checkAndEnforce(call);
                    }
                }
            );
            JSObject status = implementation.calculateStatus(true, true, true);
            notifyListeners("locationStatusChanged", status);
            if (call != null) {
                call.resolve(status);
            }
            return;
        }

        // 4. Semua valid: tutup dialog native gating jika ada
        dismissNativeGatingDialog();
        JSObject status = implementation.calculateStatus(true, true, false);
        notifyListeners("locationStatusChanged", status);
        if (call != null) {
            call.resolve(status);
        }
    }

    private void showNativeGatingDialog(
        final String title,
        final String message,
        final String actionText,
        final String exitText,
        final Runnable onAction
    ) {
        final Activity currentActivity = getActivity();
        if (currentActivity == null || currentActivity.isFinishing()) return;

        currentActivity.runOnUiThread(
            new Runnable() {
                @Override
                public void run() {
                    if (gatingDialog != null && gatingDialog.isShowing()) {
                        gatingDialog.dismiss();
                    }

                    AlertDialog.Builder builder = new AlertDialog.Builder(currentActivity);
                    builder.setTitle(title);
                    builder.setMessage(message);
                    builder.setCancelable(false);

                    builder.setPositiveButton(
                        actionText,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (onAction != null) {
                                    onAction.run();
                                }
                            }
                        }
                    );

                    builder.setNegativeButton(
                        exitText,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                currentActivity.finishAffinity();
                            }
                        }
                    );

                    gatingDialog = builder.create();
                    gatingDialog.setCanceledOnTouchOutside(false);
                    gatingDialog.show();
                }
            }
        );
    }

    private void dismissNativeGatingDialog() {
        Activity currentActivity = getActivity();
        if (currentActivity != null) {
            currentActivity.runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        if (gatingDialog != null && gatingDialog.isShowing()) {
                            gatingDialog.dismiss();
                        }
                        gatingDialog = null;
                    }
                }
            );
        }
    }

    @PluginMethod
    public void openLocationSettings(PluginCall call) {
        Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(intent);
        call.resolve();
    }

    @PluginMethod
    public void openAppSettings(PluginCall call) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", getContext().getPackageName(), null));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(intent);
        call.resolve();
    }

    private void emitLocationStatusChanged() {
        boolean enabled = implementation.isLocationEnabled(getContext());
        boolean granted = isLocationPermissionGranted();
        boolean isMock = implementation.isLocationMock(lastKnownLocation);
        notifyListeners("locationStatusChanged", implementation.calculateStatus(enabled, granted, isMock));
    }
}
