package com.wahana.plugingps;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.location.LocationManager;

public class LocationProviderReceiver extends BroadcastReceiver {

    public interface ProviderChangeListener {
        void onProviderChanged();
    }

    private final ProviderChangeListener listener;

    public LocationProviderReceiver(ProviderChangeListener listener) {
        this.listener = listener;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && LocationManager.PROVIDERS_CHANGED_ACTION.equals(intent.getAction())) {
            if (listener != null) {
                listener.onProviderChanged();
            }
        }
    }
}
