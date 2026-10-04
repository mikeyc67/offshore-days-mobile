package io.github.mikeyc67.offshoredays;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Android forgets watched spots when the phone restarts or the app is updated; set them up again. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        FenceStore.apply(context);
    }
}
