package io.github.mikeyc67.offshoredays;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingClient;
import com.google.android.gms.location.GeofencingRequest;
import com.google.android.gms.location.LocationServices;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Watches the spots where your saved ships are alongside (sent over from the app) and posts a
 * notification when you arrive at one. Android keeps watching even when the app is closed; the
 * spots are set up again after the phone restarts (BootReceiver).
 */
final class FenceStore {
    private FenceStore() {}

    static final String CHANNEL = "arrivals";
    static int watching = 0;

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("shipfence", Context.MODE_PRIVATE);
    }

    static void saveShips(Context c, String json) {
        prefs(c).edit().putString("ships", json).apply();
    }

    static JSONArray ships(Context c) {
        try { return new JSONArray(prefs(c).getString("ships", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    static boolean hasFine(Context c) {
        return ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean hasBackground(Context c) {
        return Build.VERSION.SDK_INT < 29
            || ContextCompat.checkSelfPermission(c, "android.permission.ACCESS_BACKGROUND_LOCATION") == PackageManager.PERMISSION_GRANTED;
    }

    private static PendingIntent fenceIntent(Context c) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;   // the system adds the arrival details
        return PendingIntent.getBroadcast(c, 0, new Intent(c, FenceReceiver.class), flags);
    }

    /** Replace the watched spots with the saved list (at most 20, still in date). */
    @SuppressLint("MissingPermission")
    static void apply(Context c) {
        GeofencingClient client = LocationServices.getGeofencingClient(c);
        client.removeGeofences(fenceIntent(c));
        watching = 0;
        if (!hasFine(c) || !hasBackground(c)) return;
        JSONArray list = ships(c);
        long now = System.currentTimeMillis() / 1000;
        List<Geofence> fences = new ArrayList<>();
        for (int i = 0; i < list.length() && fences.size() < 20; i++) {
            JSONObject s = list.optJSONObject(i);
            if (s == null) continue;
            String id = s.optString("mmsi", "");
            if (id.isEmpty() || !s.has("lat") || !s.has("lon")) continue;
            double until = s.optDouble("until", 0);
            if (until > 0 && until < now) continue;
            float radius = (float) Math.max(100, s.optDouble("radius", 500));
            long expires = until > 0 ? (long) ((until - now) * 1000) : Geofence.NEVER_EXPIRE;
            fences.add(new Geofence.Builder()
                .setRequestId(id)
                .setCircularRegion(s.optDouble("lat"), s.optDouble("lon"), radius)
                .setExpirationDuration(expires)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                .build());
        }
        if (fences.isEmpty()) return;
        GeofencingRequest request = new GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)   // already standing there: still ask
            .addGeofences(fences)
            .build();
        try {
            client.addGeofences(request, fenceIntent(c));
            watching = fences.size();
        } catch (SecurityException ignored) {}
    }

    /** You've arrived at ship `id`: ask, at most once a day per ship. */
    static void arrived(Context c, String id) {
        JSONObject ship = null;
        JSONArray list = ships(c);
        for (int i = 0; i < list.length(); i++) {
            JSONObject s = list.optJSONObject(i);
            if (s != null && id.equals(s.optString("mmsi"))) { ship = s; break; }
        }
        if (ship == null) return;
        double until = ship.optDouble("until", 0);
        if (until > 0 && until < System.currentTimeMillis() / 1000.0) return;

        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.UK);
        f.setTimeZone(TimeZone.getTimeZone("Europe/London"));
        String today = f.format(new Date());
        if (today.equals(prefs(c).getString("told_" + id, ""))) return;
        prefs(c).edit().putString("told_" + id, today).apply();

        String name = ship.optString("name", "your ship");
        String port = ship.optString("port", "");
        String body = port.isEmpty()
            ? "You’re by " + name + ". Starting a voyage? Tap to log it."
            : "You’re by " + name + " in " + port + ". Starting a voyage? Tap to log it.";

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Arriving at your ship", NotificationManager.IMPORTANCE_HIGH));
        }
        Intent open = new Intent(c, MainActivity.class)
            .putExtra("shipfence_mmsi", id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent tap = PendingIntent.getActivity(c, id.hashCode(), open, flags);
        NotificationCompat.Builder n = new NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("At " + name + "?")
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tap);
        try {
            NotificationManagerCompat.from(c).notify(id.hashCode(), n.build());
        } catch (SecurityException ignored) {}   // notifications switched off
    }
}
