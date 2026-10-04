package io.github.mikeyc67.offshoredays;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingEvent;

/** Android calls this when you walk into one of the watched spots. */
public class FenceReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        GeofencingEvent event = GeofencingEvent.fromIntent(intent);
        if (event == null || event.hasError() || event.getTriggeringGeofences() == null) return;
        int t = event.getGeofenceTransition();
        if (t != Geofence.GEOFENCE_TRANSITION_ENTER && t != Geofence.GEOFENCE_TRANSITION_DWELL) return;
        for (Geofence g : event.getTriggeringGeofences()) FenceStore.arrived(context, g.getRequestId());
    }
}
