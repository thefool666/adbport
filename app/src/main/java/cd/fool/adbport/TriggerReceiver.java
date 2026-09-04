package cd.fool.adbport;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class TriggerReceiver extends BroadcastReceiver {
    private static final String TAG = "ADBPort";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "Trigger received: " + intent);
        Intent svc = new Intent(context, AdbConfigService.class);
        if (intent.getExtras() != null) {
            svc.putExtras(intent.getExtras());
        }
        context.startForegroundService(svc);
    }

}
