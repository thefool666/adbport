package cd.fool.adbport;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Collections;

public final class NetworkUtils {
    private static final String TAG = "AdbPort";

    private NetworkUtils() {}

    /**
     * 取 wlan 网卡 IPv4（按接口名过滤，避开 EasyTier tun 口等干扰）。
     * 仅 mDNS 发现环节使用；取不到返回 null（调用方跳过 mDNS 落扫描兜底）。
     */
    public static String getWlanIPv4() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || !ni.getName().startsWith("wlan")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getWlanIPv4 failed", e);
        }
        return null;
    }

    /** 裸 TCP 连通性测试（不涉及 ADB 协议）。 */
    public static boolean rawTcpOk(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** WiFi / Ethernet / VPN 在线判定（排除裸蜂窝）。 */
    public static boolean isNetworkConnected(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            android.net.Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities cap = cm.getNetworkCapabilities(network);
            return cap != null
                    && (cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    || cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
                    && cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            Log.e(TAG, "isNetworkConnected failed", e);
            return false;
        }
    }
}
