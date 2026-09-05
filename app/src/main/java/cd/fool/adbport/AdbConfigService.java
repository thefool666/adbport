package cd.fool.adbport;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单发单收：只认收到的一份 intent，干完回执 stopSelf。
 * 流水线：①a 真连快路径 → ①b mDNS → ①c 扫描 → ② tcpip 切换 → ③ 终裁决 → ④ 终态。
 * 终态双通道：槽/盘全源写入；MD 广播仅 MD 来源（无 src 或 src≠ui）发出。
 */
public class AdbConfigService extends Service {

    private static final String TAG = "AdbPort";
    private static final String CHANNEL_ID = "AdbPortChannel";
    private static final int NOTIF_ID = 1;
    private static final String ACTION_OUT = "cd.fool.adbport.out";
    private static final String MD_PKG = "com.arlosoft.macrodroid";
    private static final String SERVICE_TYPE = "_adb-tls-connect._tcp";
    private static final int DEFAULT_PT = 1608;
    private static final String S_OK = "OK", S_PAIR = "PAIR", S_FAIL = "FAIL";
    private static final String D_PORT = "端口发现";
    private static final String D_AUTH = "连接认证";
    private static final String D_SW = "tcpip切换";
    private static final String D_PAIR = "配对";
    private static final String D_PDEAD = "配对未生效";
    private static final int PROBE_DOWN = 0, PROBE_REJECT = 1, PROBE_OK = 2;

    private Intent mIntent;
    private AdbHelper adb;
    private volatile boolean busy = false;

    @Override
    public void onCreate() {
        super.onCreate();
        adb = new AdbHelper(this);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "ADB Port", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (busy) { // 单发单收：执行中忽略并发 intent，物理防重入
            Log.w(TAG, "busy, duplicate intent ignored");
            return START_NOT_STICKY;
        }
        mIntent = intent;
        Result.clear(); // 清场在解析 extras 之前
        startFg("启动...");
        busy = true;
        new Thread(this::run, "adbport-run").start();
        return START_NOT_STICKY; // 被杀不重建
    }

    private void startFg(String text) {
        Notification n = notifImpl(text);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void run() {
        try {
            String ptRaw = mIntent == null ? null : mIntent.getStringExtra("pt");
            int pt = parseInt(ptRaw, DEFAULT_PT);
            String pp = mIntent == null ? null : mIntent.getStringExtra("pp");
            String pc = mIntent == null ? null : mIntent.getStringExtra("pc");
            boolean pairingMode = pc != null && !pc.trim().isEmpty();

            // ===== 配对模式：握手成功后转入常规流水线 =====
            if (pairingMode) {
                int ppPort = parseInt(pp, -1);
                if (ppPort <= 0) { finish(S_FAIL, D_PAIR); return; }
                notif("配对握手...");
                if (!adb.pair(host(), ppPort, pc.trim())) { finish(S_FAIL, D_PAIR); return; }
                Log.i(TAG, "pair ok, continue regular pipeline");
            }

            // ===== ①a 真连快路径（loopback 优先，退自取 IP）=====
            int st = probe("127.0.0.1", pt);
            if (st == PROBE_DOWN) {
                String ip = NetworkUtils.getLiveDeviceIP(this);
                if (!"127.0.0.1".equals(ip)) st = probe(ip, pt);
            }
            if (st != PROBE_DOWN) { // 快路径命中：跳过 tcpip/③/①b/①c
                if (st == PROBE_OK) finish(S_OK, "");
                else onRejected(pairingMode);
                return;
            }

            // ===== ①b mDNS 单播（5s 窗口，命中即断，host==自取 IP 过滤）=====
            notif("mDNS 探测...");
            int port = mdnsDiscover();

            // ===== ①c loopback 扫描兜底 =====
            if (port <= 0) {
                notif("端口扫描...");
                port = scanPorts();
            }
            if (port <= 0) { finish(S_FAIL, D_PORT); return; }
            Log.i(TAG, "adbd found on port " + port);

            // ===== ② 真连接 + 认证 + 发 tcpip:pt =====
            String h = "127.0.0.1";
            st = probe(h, port);
            if (st == PROBE_DOWN) {
                String ip = NetworkUtils.getLiveDeviceIP(this);
                if (!"127.0.0.1".equals(ip)) { h = ip; st = probe(h, port); }
            }
            if (st == PROBE_REJECT) { onRejected(pairingMode); return; }
            if (st == PROBE_DOWN) { finish(S_FAIL, D_PORT); return; }

            notif("切换 tcpip:" + pt + " ...");
            if (!adb.switchToPort(h, port, pt)) { finish(S_FAIL, D_SW); return; }
            Prefs.saveLastPort(this, port);

            // ===== ③ 终裁决：真连 pt + 认证 =====
            st = probe("127.0.0.1", pt);
            if (st == PROBE_DOWN) {
                String ip = NetworkUtils.getLiveDeviceIP(this);
                if (!"127.0.0.1".equals(ip)) st = probe(ip, pt);
            }
            if (st == PROBE_OK) finish(S_OK, "");
            else finish(S_FAIL, D_SW);

        } catch (Exception e) {
            Log.e(TAG, "pipeline error", e);
            finish(S_FAIL, D_AUTH);
        }
    }

    /** 裸 TCP 判活 + ADB 认证实查。DOWN=端口不活；REJECT=端口活但钥匙不对；OK=全通。 */
    private int probe(String host, int port) {
        if (!NetworkUtils.rawTcpOk(host, port, 1500)) return PROBE_DOWN;
        return adb.connect(host, port) ? PROBE_OK : PROBE_REJECT;
    }

    /** 防呆：配对模式内认证被拒一律 FAIL(配对未生效)，不报 PAIR，避免死循环弹框。 */
    private void onRejected(boolean pairingMode) {
        if (pairingMode) finish(S_FAIL, D_PDEAD);
        else finish(S_PAIR, "");
    }

    // ===== ④ 终态：槽/盘全源 → 广播仅 MD 来源 → 退出。顺序铁律不可调换 =====
    private void finish(String s, String d) {
        Result.write(s, d);            // 1. 槽（MD/UI 都写）
        Prefs.saveResult(this, s, d);  // 2. 盘（同上）
        boolean fromUi = mIntent != null && "ui".equals(mIntent.getStringExtra("src"));
        if (!fromUi) {                 // 3. MD 广播：仅 MD 来源
            Intent out = new Intent(ACTION_OUT);
            out.setPackage(MD_PKG);
            out.putExtra("s", s);
            if (d != null && !d.isEmpty()) out.putExtra("d", d);
            sendBroadcast(out);
        }
        stopForeground(Service.STOP_FOREGROUND_REMOVE); // 4. 最后退
        stopSelf();
    }

    // ===== ①b mDNS（5s 窗口，命中即断）=====
    private int mdnsDiscover() {
        final int[] found = {-1};
        final CountDownLatch latch = new CountDownLatch(1);
        String deviceIP = NetworkUtils.getLiveDeviceIP(this);
        NsdManager nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
        if (nsd == null) return -1;
        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String type) { }
            @Override public void onStartDiscoveryFailed(String type, int code) { latch.countDown(); }
            @Override public void onStopDiscoveryFailed(String type, int code) { }
            @Override public void onDiscoveryStopped(String type) { }
            @Override public void onServiceLost(NsdServiceInfo info) { }
            @Override public void onServiceFound(NsdServiceInfo info) {
                nsd.resolveService(info, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo i, int c) { }
                    @Override public void onServiceResolved(NsdServiceInfo i) {
                        if (found[0] > 0) return;
                        if (i.getHost() == null) return;
                        String host = i.getHost().getHostAddress();
                        if (deviceIP.equals(host)) {
                            found[0] = i.getPort();
                            latch.countDown(); // 命中即断，不等窗口走完
                        }
                    }
                });
            }
        };
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener);
            latch.await(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.e(TAG, "mdns error", e);
        } finally {
            try { nsd.stopServiceDiscovery(listener); } catch (Exception ignored) {}
        }
        return found[0];
    }

    // ===== ①c 扫描（32768–60999 / 64 线程 / 50ms / 15s 封顶 / last_port 快路径）=====
    private int scanPorts() {
        int lastPort = Prefs.getLastPort(this);
        if (lastPort > 0 && adb.connect("127.0.0.1", lastPort)) {
            Log.i(TAG, "hit last_port " + lastPort);
            return lastPort;
        }
        final int MIN = 32768, MAX = 60999, THREADS = 64, TIMEOUT = 50;
        final AtomicInteger found = new AtomicInteger(-1);
        AdbHelper helper = adb;
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        for (int p = MIN; p <= MAX; p++) {
            final int port = p;
            pool.submit(() -> {
                if (found.get() != -1) return;
                if (!NetworkUtils.rawTcpOk("127.0.0.1", port, TIMEOUT)) return;
                if (helper.connect("127.0.0.1", port)) {
                    found.compareAndSet(-1, port);
                }
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return found.get();
    }

    // ===== 通知 =====
    private void notif(String text) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIF_ID, notifImpl(text));
        } catch (Exception ignored) {}
    }

    private Notification notifImpl(String text) {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("ADB Port").setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_preferences).build();
    }

    private static int parseInt(String v, int def) {
        if (v == null) return def;
        try {
            int n = Integer.parseInt(v.trim());
            return n > 0 && n <= 65535 ? n : def;
        } catch (Exception e) {
            return def;
        }
    }

    private String host() {
        return NetworkUtils.getLiveDeviceIP(this);
    }
}
