package cd.fool.adbport;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * v2.8.1 + 诊断日志版。
 * 流水线（模块 A–G）：
 *   A 配对（仅带 pc）→ B 固定端口直连 → C mDNS 单播发现 → D 兜底扫描
 *   → E 连接与切换 → F 最终验证（等满 6s）→ G 收尾。
 * 核心原则：除 C 环节发往 wlan IP 的单播查询外，一切目标一律 127.0.0.1。
 */
public class AdbConfigService extends Service {
    private static final String TAG = "AdbPort";
    private static final String CHANNEL_ID = "AdbPortChannel";
    private static final int NOTIF_ID = 1;
    private static final String ACTION_OUT = "cd.fool.adbport.out";
    private static final String MD_PKG = "com.arlosoft.macrodroid";

    private static final int DEFAULT_PT = 1608;
    private static final int PROBE_TCP_TIMEOUT_MS = 1000;

    private static final int MDNS_PORT = 5353;
    private static final int MDNS_RECV_TIMEOUT_MS = 2000;
    private static final int MDNS_RETRY_GAP_MS = 2000;

    private static final int SCAN_MIN = 32768;
    private static final int SCAN_MAX = 60999;
    private static final int SCAN_THREADS = 64;
    private static final int SCAN_TCP_TIMEOUT_MS = 50;

    private static final int SWITCH_TOTAL_WAIT_MS = 6000;

    private static final String S_OK = "OK";
    private static final String S_PAIR = "PAIR";
    private static final String S_PFAIL = "PFAIL";
    private static final String S_FAIL = "FAIL";

    private static final String D_PORT = "端口发现";
    private static final String D_AUTH = "连接认证";
    private static final String D_SW = "tcpip切换";
    private static final String D_PDEAD = "配对未生效";

    private static final int PROBE_DOWN = 0, PROBE_REJECT = 1, PROBE_OK = 2;

    private Intent mIntent;
    private AdbHelper adb;
    private volatile boolean busy = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Logger.init(this);                       // 日志初始化放最前
        adb = new AdbHelper(this);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "ADB Port", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (busy) {
            Logger.w(TAG, "busy, duplicate intent ignored");
            return START_NOT_STICKY;
        }
        mIntent = intent;
        Result.clear();
        startFg("启动...");
        busy = true;
        new Thread(this::run, "adbport-run").start();
        return START_NOT_STICKY;
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
            Logger.i(TAG, "run start: pt=" + pt + " pp=" + pp + " pc=" + (pc == null ? "null" : "***")
                    + " pairingMode=" + pairingMode);

            // ===== 模块 A：配对 =====
            if (pairingMode) {
                int ppPort = parseInt(pp, -1);
                boolean ppAlive = ppPort > 0 && NetworkUtils.rawTcpOk("127.0.0.1", ppPort, PROBE_TCP_TIMEOUT_MS);
                Logger.i(TAG, "A1: pp=" + ppPort + " rawTcp(127.0.0.1)=" + ppAlive);
                if (!ppAlive) {
                    finish(S_PFAIL, AdbHelper.PAIR_ERR_PORT);
                    return;
                }
                notif("配对握手...");
                long t0 = System.currentTimeMillis();
                String pairErr = adb.pair("127.0.0.1", ppPort, pc.trim());
                Logger.i(TAG, "A2: pair returned " + (pairErr == null ? "null(成功)" : pairErr)
                        + " in " + (System.currentTimeMillis() - t0) + "ms");
                if (pairErr != null) {
                    finish(S_PFAIL, pairErr);
                    return;
                }
                Logger.i(TAG, "A3: pair ok, continue regular pipeline");
            }

            // ===== 模块 B：固定端口直连 =====
            notif("探测固定端口 " + pt + "...");
            int st = probe("127.0.0.1", pt);
            Logger.i(TAG, "B1: probe 127.0.0.1:" + pt + " -> " + probeName(st));
            if (st != PROBE_DOWN) {
                if (st == PROBE_OK) finish(S_OK, "");
                else onRejected(pairingMode, pt);
                return;
            }

            // ===== 模块 C：mDNS 单播发现 =====
            notif("mDNS 探测...");
            List<Integer> candidates = mdnsDiscover();
            Logger.i(TAG, "C: mdns candidates=" + candidates);

            // ===== 模块 D：兜底扫描 =====
            if (candidates.isEmpty()) {
                notif("端口扫描...");
                long t0 = System.currentTimeMillis();
                candidates = scanPorts();
                Logger.i(TAG, "D: scan done in " + (System.currentTimeMillis() - t0) + "ms, candidates=" + candidates);
            }
            if (candidates.isEmpty()) {
                finish(S_FAIL, D_PORT);
                return;
            }

            // ===== 模块 E：连接与切换 =====
            int target = -1;
            for (int p : candidates) {
                if (p == pt) {
                    Logger.i(TAG, "E1: skip " + p + " (== pt, B1 already dead)");
                    continue;
                }
                st = probe("127.0.0.1", p);
                Logger.i(TAG, "E1: probe 127.0.0.1:" + p + " -> " + probeName(st));
                if (st == PROBE_REJECT) {
                    onRejected(pairingMode, p);
                    return;
                }
                if (st == PROBE_OK) { target = p; break; }
            }
            if (target < 0) {
                Logger.w(TAG, "E: no candidate passed auth");
                finish(S_FAIL, D_PORT);
                return;
            }
            notif("切换 tcpip:" + pt + " ...");
            long switchStart = System.currentTimeMillis();
            boolean sw = adb.switchToPort("127.0.0.1", target, pt);
            Logger.i(TAG, "E2: switchToPort(127.0.0.1," + target + "," + pt + ")=" + sw);
            if (!sw) {
                finish(S_FAIL, D_SW);
                return;
            }

            // ===== 模块 F：最终验证 =====
            long remain = SWITCH_TOTAL_WAIT_MS - (System.currentTimeMillis() - switchStart);
            if (remain > 0) {
                try { Thread.sleep(remain); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            st = probe("127.0.0.1", pt);
            Logger.i(TAG, "F2: final probe 127.0.0.1:" + pt + " -> " + probeName(st));
            finish(st == PROBE_OK ? S_OK : S_FAIL, st == PROBE_OK ? "" : D_SW);
        } catch (Exception e) {
            Logger.e(TAG, "pipeline error", e);
            finish(S_FAIL, D_AUTH);
        }
    }

    private int probe(String host, int port) {
        if (!NetworkUtils.rawTcpOk(host, port, PROBE_TCP_TIMEOUT_MS)) return PROBE_DOWN;
        return adb.connect(host, port) ? PROBE_OK : PROBE_REJECT;
    }

    private static String probeName(int st) {
        return st == PROBE_DOWN ? "DOWN" : st == PROBE_REJECT ? "REJECT" : "OK";
    }

    private void onRejected(boolean pairingMode, int port) {
        if (pairingMode) finish(S_PFAIL, D_PDEAD);
        else finish(S_PAIR, "(端口 " + port + ")");
    }

    // ===== 模块 G：终态 =====
    private void finish(String s, String d) {
        Logger.i(TAG, "finish: s=" + s + " d=" + d);
        Result.write(s, d);
        Prefs.saveResult(this, s, d);
        boolean fromUi = mIntent != null && "ui".equals(mIntent.getStringExtra("src"));
        if (!fromUi) {
            Intent out = new Intent(ACTION_OUT);
            out.setPackage(MD_PKG);
            out.putExtra("s", s);
            if (d != null && !d.isEmpty()) out.putExtra("d", d);
            sendBroadcast(out);
        }
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    // ===== 模块 C：mDNS 单播发现 =====
    private List<Integer> mdnsDiscover() {
        List<Integer> result = new ArrayList<>();
        String wlanIp = NetworkUtils.getWlanIPv4();
        Logger.i(TAG, "C1: wlanIp=" + wlanIp);
        if (wlanIp == null) {
            Logger.w(TAG, "C1: no wlan ipv4, skip mdns");
            return result;
        }
        byte[] query = buildMdnsQuery();
        for (int attempt = 0; attempt < 2; attempt++) {
            if (attempt > 0) {
                try { Thread.sleep(MDNS_RETRY_GAP_MS); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return result;
                }
            }
            try (DatagramSocket sock = new DatagramSocket()) {
                sock.setSoTimeout(MDNS_RECV_TIMEOUT_MS);
                sock.send(new DatagramPacket(query, query.length, InetAddress.getByName(wlanIp), MDNS_PORT));
                byte[] buf = new byte[2048];
                DatagramPacket resp = new DatagramPacket(buf, buf.length);
                sock.receive(resp);
                Logger.i(TAG, "C2: attempt " + attempt + " got " + resp.getLength() + " bytes from "
                        + resp.getAddress().getHostAddress());
                parseSrvPorts(resp.getData(), resp.getLength(), result);
            } catch (Exception e) {
                Logger.i(TAG, "C2: attempt " + attempt + " failed: " + e.getClass().getSimpleName()
                        + " / " + e.getMessage());
            }
            if (!result.isEmpty()) break;
        }
        Collections.sort(result, Collections.reverseOrder());
        return result;
    }

    private static byte[] buildMdnsQuery() {
        byte[] name = {
                16, '_', 'a', 'd', 'b', '-', 't', 'l', 's', '-', 'c', 'o', 'n', 'n', 'e', 'c', 't',
                4, '_', 't', 'c', 'p',
                5, 'l', 'o', 'c', 'a', 'l',
                0
        };
        byte[] pkt = new byte[12 + name.length + 4];
        int id = new Random().nextInt(65536);
        pkt[0] = (byte) (id >> 8);
        pkt[1] = (byte) id;
        pkt[5] = 1;
        System.arraycopy(name, 0, pkt, 12, name.length);
        int t = 12 + name.length;
        pkt[t] = 0;     pkt[t + 1] = 33;
        pkt[t + 2] = 0; pkt[t + 3] = 1;
        return pkt;
    }

    private static void parseSrvPorts(byte[] d, int len, List<Integer> out) {
        if (len < 12) return;
        int qd = u16(d, 4);
        int total = u16(d, 6) + u16(d, 8) + u16(d, 10);
        int p = 12;
        for (int i = 0; i < qd && p < len; i++) {
            p = skipName(d, len, p);
            p += 4;
        }
        for (int i = 0; i < total && p + 10 <= len; i++) {
            p = skipName(d, len, p);
            if (p + 10 > len) return;
            int type = u16(d, p);
            int rdlen = u16(d, p + 8);
            int rdp = p + 10;
            if (type == 33 && rdlen >= 8 && rdp + rdlen <= len) {
                int port = u16(d, rdp + 4);
                if (port >= 1024 && port <= 65535 && !out.contains(port)) out.add(port);
            }
            p = rdp + rdlen;
        }
    }

    private static int u16(byte[] d, int p) { return ((d[p] & 0xFF) << 8) | (d[p + 1] & 0xFF); }

    private static int skipName(byte[] d, int len, int p) {
        while (p < len) {
            int l = d[p] & 0xFF;
            if (l == 0) return p + 1;
            if ((l & 0xC0) == 0xC0) return p + 2;
            p += 1 + l;
        }
        return len;
    }

    // ===== 模块 D：兜底扫描（开放口与认证通过分开记，诊断关键）=====
    private List<Integer> scanPorts() {
        final List<Integer> rawOpen = Collections.synchronizedList(new ArrayList<Integer>());
        final List<Integer> authOk = Collections.synchronizedList(new ArrayList<Integer>());
        ExecutorService pool = Executors.newFixedThreadPool(SCAN_THREADS);
        for (int p = SCAN_MIN; p <= SCAN_MAX; p++) {
            final int port = p;
            pool.submit(() -> {
                if (!NetworkUtils.rawTcpOk("127.0.0.1", port, SCAN_TCP_TIMEOUT_MS)) return;
                rawOpen.add(port);
                if (adb.connect("127.0.0.1", port)) authOk.add(port);
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(10, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Logger.i(TAG, "D detail: rawOpen=" + rawOpen.size() + " " + rawOpen
                + " | authOk=" + authOk.size() + " " + authOk);
        Collections.sort(authOk, Collections.reverseOrder());
        return authOk;
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
}
