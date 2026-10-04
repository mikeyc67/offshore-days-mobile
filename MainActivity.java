package io.github.mikeyc67.offshoredays;

import android.content.Intent;
import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(ShipFencePlugin.class);
        super.onCreate(savedInstanceState);
        takeTap(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        takeTap(intent);
    }

    /** Opened from an arrival alert: tell the page which ship. */
    private void takeTap(Intent intent) {
        if (intent == null) return;
        String mmsi = intent.getStringExtra("shipfence_mmsi");
        if (mmsi == null) return;
        intent.removeExtra("shipfence_mmsi");
        FenceStore.prefs(this).edit().putString("tapped", mmsi).apply();
        if (ShipFencePlugin.instance != null) ShipFencePlugin.instance.fireTapped(mmsi);
    }
}
