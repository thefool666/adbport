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

import java.io.ByteArrayOutputStream;
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
 * 单发单收：只认收到的一份 intent，干完回执 stopSelf。
 * 流水线（v2.8.1，模块 A–G）：
 *   A 配对（仅带 pc）→ B 固定端口直连 → C mDNS 单播发现（唯一允许触碰 wlan IP 的环节）
 *   → D 兜底扫描 → E 连接与切换 → F 最终验证（等满 6s）→ G 收尾。
 * 核心原则：除 C 环节发往 wlan IP 的单播查询外，一切目标一律 127.0.0.1。
 * 终态四值：OK / PAIR（请求配对）/ PFAIL（配对环节失败）/ FAIL（其他）。
 * 终态双通道：槽/盘全源写入；MD 广播仅 MD 来源（无 src 或 src≠ui）发出。
 */
public class AdbConfigService extends Service {
    private static final String TAG = "AdbPort";
    private static final String CHANNEL_ID = "AdbPortChannel";
    private static final int NOTIF_ID = 1;
    private static final String ACTION_OUT = "cd.fool.adbport.out";
    private static final String MD_PKG = "com.arlosoft.macrodroid";

    private static final int DEFAULT_PT = 1608;
    private static final int PROBE_TCP_TIMEOUT_MS = 1000;   // E1 真连接超时写死 1 秒

    // mDNS 单播参数（与 adbd.sh / dig 语义对齐）
    private static final int MDNS_PORT = 5353;
    private static final int MDNS_RECV_TIMEOUT_MS = 2000;
    private static final int MDNS_RETRY_GAP_MS = 2000;      // C3：空 → 等 2 秒重发一次

    // 兜底扫描参数（宁慢勿漏：必须扫完全段才允许下「未找到」结论）
    private static final int SCAN_MIN = 32768;
    private static final int SCAN_MAX = 60999;
    private static final int SCAN_THREADS = 64;
    private static final int SCAN_TCP_TIMEOUT_MS = 50;

    private static final int SWITCH_TOTAL_WAIT_MS = 6000;   // F1：自 tcpip 发出起累计等满 6 秒

    // 回执 s 四值
    private static final String S_OK = "OK";
    private static final String S_PAIR = "PAIR";
    private static final String S_PFAIL = "PFAIL";
    private static final String S_FAIL = "FAIL";

    // 回执 d 值
    private static final String D_PORT = "端口发现";
    private static final String D_AUTH = "连接认证";          // 保留：仅意外异常 catch-all 兜底
    private static final String D_SW = "tcpip切换";
    private static final String D_PDEAD = "配对未生效";        // A3 后 B1 仍被拒（PFAIL 名下）

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
    public IBinder onBind(Intent intent) { return null; }

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

            // ===== 模块 A：配对（仅带 pc 时；一切失败一律 PFAIL）=====
            if (pairingMode) {
                int ppPort = parseInt(pp, -1);
                if (ppPort <= 0 || !NetworkUtils.rawTcpOk("127.0.0.1", ppPort, PROBE_TCP_TIMEOUT_MS)) {
                    finish(S_PFAIL, AdbHelper.PAIR_ERR_PORT);   // A1 端口不通（含 pp 非法值）
                    return;
                }
                notif("配对握手...");
                String pairErr = adb.pair("127.0.0.1", ppPort, pc.trim());   // A2 握手（核心原则：回环）
                if (pairErr != null) {
                    finish(S_PFAIL, pairErr);                   // 码错误 / 握手失败
                    return;
                }
                Log.i(TAG, "pair ok, continue regular pipeline"); // A3 密钥已落盘，完整走 B→F
            }

            // ===== 模块 B：固定端口直连（纯 127.0.0.1，无任何回退）=====
            notif("探测固定端口 " + pt + "...");
            int st = probe("127.0.0.1", pt);
            if (st != PROBE_DOWN) {
                if (st == PROBE_OK) finish(S_OK, "");
                else onRejected(pairingMode, pt);               // B1 被拒：常规→PAIR，配对模式→PFAIL(配对未生效)
                return;                                          // C/D/E/F 全跳过
            }

            // ===== 模块 C：mDNS 单播发现 =====
            notif("mDNS 探测...");
            List<Integer> candidates = mdnsDiscover();

            // ===== 模块 D：兜底扫描（C 落空才触发）=====
            if (candidates.isEmpty()) {
                notif("端口扫描...");
                candidates = scanPorts();
            }
            if (candidates.isEmpty()) {
                finish(S_FAIL, D_PORT);
                return;
            }
            Log.i(TAG, "candidates: " + candidates);

            // ===== 模块 E：连接与切换 =====
            int target = -1;
            for (int p : candidates) {
                if (p == pt) continue;                          // E1 附加：B1 已判死的口不重复试
                st = probe("127.0.0.1", p);                     // E1 真连，超时 1 秒
                if (st == PROBE_REJECT) {
                    onRejected(pairingMode, p);                 // 未授权即确诊，不再试其他端口
                    return;
                }
                if (st == PROBE_OK) { target = p; break; }
                // PROBE_DOWN → 试下一个
            }
            if (target < 0) {
                finish(S_FAIL, D_PORT);
                return;
            }
            notif("切换 tcpip:" + pt + " ...");
            long switchStart = System.currentTimeMillis();
            if (!adb.switchToPort("127.0.0.1", target, pt)) {   // E2（内部含 3s 等待）
                finish(S_FAIL, D_SW);
                return;
            }

            // ===== 模块 F：最终验证（F1 等满 6 秒防假 FAIL）=====
            long remain = SWITCH_TOTAL_WAIT_MS - (System.currentTimeMillis() - switchStart);
            if (remain > 0) {
                try { Thread.sleep(remain); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            st = probe("127.0.0.1", pt);                        // F2 纯回环，无回退
            finish(st == PROBE_OK ? S_OK : S_FAIL, st == PROBE_OK ? "" : D_SW);
        } catch (Exception e) {
            Log.e(TAG, "pipeline error", e);
            finish(S_FAIL, D_AUTH);                             // 意外异常兜底，与正常判失败区分
        }
    }

    /** 裸 TCP 判活（1 秒写死）+ ADB 认证实查。DOWN=端口不活；REJECT=端口活但钥匙不对；OK=全通。 */
    private int probe(String host, int port) {
        if (!NetworkUtils.rawTcpOk(host, port, PROBE_TCP_TIMEOUT_MS)) return PROBE_DOWN;
        return adb.connect(host, port) ? PROBE_OK : PROBE_REJECT;
    }

    /** 防呆：PAIR 仅常规流程报（带端口）；配对模式内一切被拒一律 PFAIL(配对未生效)，防死循环弹框。 */
    private void onRejected(boolean pairingMode, int port) {
        if (pairingMode) finish(S_PFAIL, D_PDEAD);
        else finish(S_PAIR, "(端口 " + port + ")");
    }

    // ===== 模块 G：终态。槽/盘全源 → 广播仅 MD 来源 → 退出。顺序铁律不可调换 =====
    private void finish(String s, String d) {
        Result.write(s, d);                    // G1 槽（MD/UI 都写）
        Prefs.saveResult(this, s, d);          // G2 盘（同上）
        boolean fromUi = mIntent != null && "ui".equals(mIntent.getStringExtra("src"));
        if (!fromUi) {                         // G3 MD 广播：仅 MD 来源
            Intent out = new Intent(ACTION_OUT);
            out.setPackage(MD_PKG);
            out.putExtra("s", s);
            if (d != null && !d.isEmpty()) out.putExtra("d", d);
            sendBroadcast(out);
        }
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
        stopSelf();                            // G4 最后退
    }

    // ===== 模块 C：mDNS 单播发现（dig 单播语义，NsdManager 已废除）=====
    // C1 取 wlan IPv4（取不到 → 空列表落扫描）；C2 单发，有应答即收；C3 空 → 等 2 秒重发一次。
    private List<Integer> mdnsDiscover() {
        List<Integer> result = new ArrayList<>();
        String wlanIp = NetworkUtils.getWlanIPv4();
        if (wlanIp == null) {
            Log.w(TAG, "no wlan ipv4, skip mdns");
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
                sock.receive(resp); // 有应答即收
                parseSrvPorts(resp.getData(), resp.getLength(), result);
            } catch (Exception e) {
                Log.i(TAG, "mdns attempt " + attempt + ": " + e.getClass().getSimpleName());
            }
            if (!result.isEmpty()) break;
        }
        Collections.sort(result, Collections.reverseOrder());
        return result;
    }

    /** 查询包 = 12 字节头（随机事务 ID、标志 0、QDCount=1）+ _adb-tls-connect._tcp.local + QTYPE=33 + QCLASS=1。 */
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
        // pkt[2..3]=0 标志位；pkt[4..5]=1 QDCount；其余 0
        pkt[5] = 1;
        System.arraycopy(name, 0, pkt, 12, name.length);
        int t = 12 + name.length;
        pkt[t] = 0;     pkt[t + 1] = 33;  // QTYPE = SRV
        pkt[t + 2] = 0; pkt[t + 3] = 1;   // QCLASS = IN
        return pkt;
    }

    /** 解析应答：跳过 Question 区，扫 Answer/Authority/Additional 全部 RR，收 SRV 记录 Port 字段。 */
    private static void parseSrvPorts(byte[] d, int len, List<Integer> out) {
        if (len < 12) return;
        int qd = u16(d, 4);
        int total = u16(d, 6) + u16(d, 8) + u16(d, 10); // AN + NS + AR
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
            if (type == 33 && rdlen >= 8 && rdp + rdlen <= len) {   // SRV: prio(2)+weight(2)+port(2)+target
                int port = u16(d, rdp + 4);
                if (port >= 1024 && port <= 65535 && !out.contains(port)) out.add(port);
            }
            p = rdp + rdlen;
        }
    }

    private static int u16(byte[] d, int p) { return ((d[p] & 0xFF) << 8) | (d[p + 1] & 0xFF); }

    /** 跳过域名（兼容压缩指针 0xC0）。 */
    private static int skipName(byte[] d, int len, int p) {
        while (p < len) {
            int l = d[p] & 0xFF;
            if (l == 0) return p + 1;
            if ((l & 0xC0) == 0xC0) return p + 2;
            p += 1 + l;
        }
        return len;
    }

    // ===== 模块 D：兜底扫描（32768–60999 / 64 线程 / 50ms / 无 15s 封顶 / 无 last_port）=====
    private List<Integer> scanPorts() {
        final List<Integer> found = Collections.synchronizedList(new ArrayList<Integer>());
        ExecutorService pool = Executors.newFixedThreadPool(SCAN_THREADS);
        for (int p = SCAN_MIN; p <= SCAN_MAX; p++) {
            final int port = p;
            pool.submit(() -> {
                if (!NetworkUtils.rawTcpOk("127.0.0.1", port, SCAN_TCP_TIMEOUT_MS)) return;
                if (adb.connect("127.0.0.1", port)) found.add(port);
            });
        }
        pool.shutdown();
        try {
            // 不设 15s 封顶：扫完全段才允许下「未找到」结论；大额上限仅防线程池泄漏
            pool.awaitTermination(10, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Collections.sort(found, Collections.reverseOrder());
        return found;
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
