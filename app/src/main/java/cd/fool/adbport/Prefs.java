package cd.fool.adbport;

import android.content.Context;
import android.content.SharedPreferences;

/** 落盘：「上次执行」三 key + 端口扫描 last_port 快路径。跨轮保留，槽每轮 clear、盘不清。 */
public final class Prefs {
    private static final String NAME = "adbport";

    private Prefs() {}

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static void saveResult(Context c, String s, String d) {
        p(c).edit()
                .putString("result_s", s)
                .putString("result_d", d == null ? "" : d)
                .putLong("result_ts", System.currentTimeMillis())
                .apply();
    }

    public static String getS(Context c)    { return p(c).getString("result_s", ""); }
    public static String getD(Context c)    { return p(c).getString("result_d", ""); }
    public static long   getTs(Context c)   { return p(c).getLong("result_ts", 0L); }

    public static void saveLastPort(Context c, int port) { p(c).edit().putInt("last_port", port).apply(); }
    public static int  getLastPort(Context c)            { return p(c).getInt("last_port", -1); }
}
