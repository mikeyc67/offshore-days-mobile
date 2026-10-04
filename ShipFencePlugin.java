package io.github.mikeyc67.offshoredays;

import android.Manifest;
import android.annotation.SuppressLint;
import android.os.Build;

import androidx.core.app.NotificationManagerCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

/** The page's way in to FenceStore: window.Capacitor.registerPlugin('ShipFence'). Same calls as on iPhone. */
@CapacitorPlugin(
    name = "ShipFence",
    permissions = {
        @Permission(alias = "location", strings = { Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION }),
        @Permission(alias = "notifications", strings = { "android.permission.POST_NOTIFICATIONS" })
    }
)
public class ShipFencePlugin extends Plugin {
    static ShipFencePlugin instance;

    @Override
    public void load() {
        instance = this;
    }

    @Override
    protected void handleOnResume() {
        super.handleOnResume();
        // "Allow all the time" is granted in Settings, so pick it up when you come back to the app
        FenceStore.apply(getContext());
    }

    /** A tapped arrival alert; kept until the page is listening, in case the tap launched the app. */
    void fireTapped(String mmsi) {
        JSObject o = new JSObject();
        o.put("mmsi", mmsi);
        notifyListeners("tapped", o, true);
    }

    private JSObject status() {
        JSObject o = new JSObject();
        boolean fine = FenceStore.hasFine(getContext());
        String loc = fine && FenceStore.hasBackground(getContext()) ? "always"
            : fine ? "whenInUse"
            : getPermissionState("location") == PermissionState.DENIED ? "denied" : "notDetermined";
        o.put("location", loc);
        o.put("notifications", NotificationManagerCompat.from(getContext()).areNotificationsEnabled());
        o.put("watching", FenceStore.watching);
        return o;
    }

    @PluginMethod
    public void status(PluginCall call) {
        call.resolve(status());
    }

    /** Asks for notifications and location, then "Allow all the time" (Android opens Settings for that). */
    @PluginMethod
    public void enable(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 33) requestPermissionForAliases(new String[] { "location", "notifications" }, call, "enableDone");
        else requestPermissionForAlias("location", call, "enableDone");
    }

    @PermissionCallback
    private void enableDone(PluginCall call) {
        if (FenceStore.hasFine(getContext()) && !FenceStore.hasBackground(getContext()) && Build.VERSION.SDK_INT >= 29) {
            getActivity().requestPermissions(new String[] { "android.permission.ACCESS_BACKGROUND_LOCATION" }, 7301);
        }
        FenceStore.apply(getContext());
        call.resolve(status());
    }

    @PluginMethod
    public void setShips(PluginCall call) {
        JSArray ships = call.getArray("ships", new JSArray());
        FenceStore.saveShips(getContext(), ships.toString());
        FenceStore.apply(getContext());
        JSObject o = new JSObject();
        o.put("watching", ships.length());
        call.resolve(o);
    }

    @PluginMethod
    public void position(PluginCall call) {
        if (!FenceStore.hasFine(getContext())) {
            requestPermissionForAlias("location", call, "positionAllowed");
            return;
        }
        locate(call);
    }

    @PermissionCallback
    private void positionAllowed(PluginCall call) {
        if (FenceStore.hasFine(getContext())) locate(call);
        else call.reject("Location wasn’t allowed.");
    }

    @SuppressLint("MissingPermission")
    private void locate(PluginCall call) {
        LocationServices.getFusedLocationProviderClient(getContext())
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener(loc -> {
                if (loc == null) { call.reject("No position yet."); return; }
                JSObject o = new JSObject();
                o.put("lat", loc.getLatitude());
                o.put("lon", loc.getLongitude());
                o.put("accuracy", loc.getAccuracy());
                call.resolve(o);
            })
            .addOnFailureListener(e -> call.reject(e.getMessage()));
    }

    @PluginMethod
    public void takeTapped(PluginCall call) {
        String id = FenceStore.prefs(getContext()).getString("tapped", null);
        FenceStore.prefs(getContext()).edit().remove("tapped").apply();
        JSObject o = new JSObject();
        if (id != null) o.put("mmsi", id);
        call.resolve(o);
    }
}
