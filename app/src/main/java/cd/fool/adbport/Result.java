package cd.fool.adbport;

/** 进程内静态结果槽：服务后台线程写 / UI 线程读，volatile 保证可见性。 */
public final class Result {
    public static volatile String s   = "";   // "" / "OK" / "PAIR" / "FAIL"
    public static volatile String d   = "";
    public static volatile long   ts  = 0L;
    public static volatile int    seq = 0;    // 每轮执行 +1，UI 判新结果

    private Result() {}

    public static void write(String s0, String d0) {
        s = s0; d = d0 == null ? "" : d0;
        ts = System.currentTimeMillis(); seq++;
    }

    public static void clear() { s = ""; d = ""; ts = 0; }
}
