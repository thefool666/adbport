package cd.fool.adbport;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 双写日志：Logcat + 落盘 /sdcard/Android/data/cd.fool.adbport/files/adbport.log。
 * app 专属外部目录，零权限；追加写，单文件超 2MB 轮转为 .old。
 * 所有关键诊断点（配对堆栈 / mDNS 收发 / 扫描开放口 / 每步 probe 结果）均落盘。
 */
public final class Logger {
    private static final String TAG = "AdbPort";
    private static final long MAX_SIZE = 2L * 1024 * 1024;
    private static final Object LOCK = new Object();
    private static File file;
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private Logger() {}

    public static void init(Context c) {
        try {
            File dir = c.getExternalFilesDir(null);
            if (dir != null) file = new File(dir, "adbport.log");
        } catch (Exception ignored) {}
        w(TAG, "==== new session ====");
    }

    public static void i(String tag, String msg) { Log.i(tag, msg); write("I", msg, null); }
    public static void w(String tag, String msg) { Log.w(tag, msg); write("W", msg, null); }

    public static void e(String tag, String msg, Throwable t) {
        Log.e(tag, msg, t);
        write("E", msg, t);
    }

    private static void write(String level, String msg, Throwable t) {
        File f = file;
        if (f == null) return;
        synchronized (LOCK) {
            Writer out = null;
            try {
                if (f.exists() && f.length() > MAX_SIZE) {
                    File old = new File(f.getParentFile(), "adbport.log.old");
                    if (old.exists()) old.delete();
                    f.renameTo(old);
                }
                StringBuilder sb = new StringBuilder();
                sb.append(FMT.format(new Date())).append(' ').append(level).append(": ").append(msg);
                if (t != null) {
                    sb.append('\n').append(android.util.Log.getStackTraceString(t));
                }
                sb.append('\n');
                out = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8);
                out.write(sb.toString());
                out.flush();
            } catch (Exception ignored) {
            } finally {
                if (out != null) try { out.close(); } catch (Exception ignored) {}
            }
        }
    }
}
