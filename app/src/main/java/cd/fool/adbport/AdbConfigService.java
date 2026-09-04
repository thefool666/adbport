package cd.fool.adbport;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

public class AdbConfigService extends Service {
    private static final String TAG = "ADBPort";
    public static final String EXTRA_TARGET_PORT = "target_port";
    public static final String EXTRA_CURRENT_PORT = "current_port";

    public static final String MD_PACKAGE = "com.arlosoft.macrodroid";
    public static final String ACTION_RESULT = "cd.fool.adbport.ADB_RESULT";
    public static final String EXTRA_RESULT_SUCCESS = "success";
    public static final String EXTRA_RESULT_MESSAGE = "message";

    private static final String CHANNEL_ID = "adbport_channel";
    private static final int NOTIFICATION_ID = 1;

    private AdbHelper helper;

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundWithNotification();
        helper = new AdbHelper(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int targetPort = intent != null ? intent.getIntExtra(EXTRA_TARGET_PORT, -1) : -1;
        int currentPort = intent != null ? intent.getIntExtra(EXTRA_CURRENT_PORT, -1) : -1;
        final Intent fIntent = intent;
        new Thread(() -> {
            boolean ok = false;
            String msg;
            try {
                if (targetPort <= 0) {
                    msg = "target_port missing";
                } else if (currentPort <= 0) {
                    msg = "current_port missing";
                } else {
                    String ip = getLiveDeviceIP();
                    if (ip == null) {
                        msg = "No WiFi IPv4 found";
                    } else {
                        Log.i(TAG, "Using IP=" + ip + " current=" + currentPort + " target=" + targetPort);
                        ok = runFlow(ip, currentPort, targetPort);
                        msg = ok ? "OK" : "ADB flow failed, see logcat";
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Service error", e);
                msg = "Exception: " + e.getMessage();
            }
            sendResult(ok, msg + " (target=" + targetPort + ")");
            stopSelf();
        }).start();
        return START_NOT_STICKY;
    }

    private boolean runFlow(String ip, int currentPort, int targetPort) {
        // 1. Connect to the device's current ADB port
        if (!helper.connect(ip, currentPort)) {
            Log.e(TAG, "Connect failed on " + ip + ":" + currentPort);
            return false;
        }
        // 2. Grant WRITE_SECURE_SETTINGS to MacroDroid via ADB
        boolean granted = helper.selfGrantPermission(ip, currentPort, MD_PACKAGE,
                "android.permission.WRITE_SECURE_SETTINGS");
        Log.i(TAG, "selfGrantPermission -> " + granted);
        // 3. Switch ADB to the target port
        return helper.switchToPort(ip, currentPort, targetPort);
    }

    private void sendResult(boolean success, String message) {
        Intent i = new Intent(ACTION_RESULT);
        i.setPackage(MD_PACKAGE);
        i.putExtra(EXTRA_RESULT_SUCCESS, success);
        i.putExtra(EXTRA_RESULT_MESSAGE, message);
        sendBroadcast(i);
        Log.i(TAG, "Result broadcast sent: success=" + success + " msg=" + message);
    }

    private String getLiveDeviceIP() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getLiveDeviceIP failed", e);
        }
        return null;
    }

    private void startForegroundWithNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification n;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                    "ADB Port", NotificationManager.IMPORTANCE_LOW));
            n = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("adbport")
                    .setContentText("Configuring ADB…")
                    .setSmallIcon(getApplicationInfo().icon)
                    .build();
        } else {
            n = new Notification.Builder(this)
                    .setContentTitle("adbport")
                    .setContentText("Configuring ADB…")
                    .setSmallIcon(getApplicationInfo().icon)
                    .build();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
