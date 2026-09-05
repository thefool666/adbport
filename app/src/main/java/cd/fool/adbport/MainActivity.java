package cd.fool.adbport;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Method;

public class MainActivity extends Activity {
    private EditText ppEdit, pcEdit, ptEdit;
    private Button runBtn;
    private TextView portView, resultView, switchView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int lastSeenSeq = 0;
    private boolean waiting = false;

    /** 500ms tick：seq 变化即渲染（等待结束 / MD 轮透视共用一条路径）；等待不设超时。 */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (Result.seq != lastSeenSeq) {
                lastSeenSeq = Result.seq;
                if (waiting) {
                    waiting = false;
                    runBtn.setEnabled(true);
                }
                renderResult();
            }
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 64, 48, 48);

        ppEdit = field(root, "配对端口 pp（系统配对页获取）");
        pcEdit = field(root, "配对码 pc");
        ptEdit = field(root, "目标端口 pt（留空 = 1608）");
        runBtn = new Button(this);
        runBtn.setText("配对并切换");
        runBtn.setOnClickListener(v -> onRun());
        root.addView(runBtn);
        addLine(root);

        portView = new TextView(this);
        resultView = new TextView(this);
        switchView = new TextView(this);
        TextView[] info = { portView, resultView, switchView };
        for (TextView t : info) {
            t.setTextSize(15);
            t.setPadding(0, 16, 0, 0);
            root.addView(t);
        }
        setContentView(root);
    }

    private EditText field(LinearLayout root, String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        root.addView(e);
        return e;
    }

    private void addLine(LinearLayout root) {
        TextView line = new TextView(this);
        line.setText("──────────────────────");
        root.addView(line);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatic();
        lastSeenSeq = Result.seq;
        renderResult();
        handler.postDelayed(tick, 500);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    /** 手动触发：与 MD 完全同构的 intent + src=ui。前台态内 startForegroundService，12+ 合法。 */
    private void onRun() {
        String pp = ppEdit.getText().toString().trim();
        String pc = pcEdit.getText().toString().trim();
        String pt = ptEdit.getText().toString().trim();
        if (pp.isEmpty() || !isNum(pp)) { toast("配对端口 pp 必须为数字"); return; }
        if (pc.isEmpty() || !isNum(pc)) { toast("配对码 pc 必须为数字"); return; }
        if (!pt.isEmpty() && !isNum(pt)) { toast("目标端口 pt 必须为数字"); return; }

        android.content.Intent i = new android.content.Intent(this, AdbConfigService.class);
        i.putExtra("pp", pp);
        i.putExtra("pc", pc);
        if (!pt.isEmpty()) i.putExtra("pt", pt);
        i.putExtra("src", "ui");
        try {
            startForegroundService(i);
            waiting = true;
            runBtn.setEnabled(false);
            resultView.setText("⏳ 执行中...");
        } catch (Exception e) {
            toast("启动服务失败：" + e.getMessage());
        }
    }

    private static boolean isNum(String s) {
        for (char c : s.toCharArray()) if (c < '0' || c > '9') return false;
        return true;
    }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }

    /** 静态三显示：sysprop 端口 / 上次执行（槽优先、盘兜底）/ 无线调试开关态。 */
    private void refreshStatic() {
        String tcpPort = sysprop("service.adb.tcp.port");
        portView.setText("adbd TCP 端口: " + (tcpPort == null || tcpPort.isEmpty() ? "未配置" : tcpPort));
        int wifi = Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", 0);
        switchView.setText("无线调试开关: " + (wifi == 1 ? "开" : "关"));
    }

    private void renderResult() {
        String s, d;
        long ts;
        if (Result.seq > 0 && !Result.s.isEmpty()) {
            s = Result.s; d = Result.d; ts = Result.ts;
        } else {
            s = Prefs.getS(this); d = Prefs.getD(this); ts = Prefs.getTs(this);
        }
        if (s.isEmpty()) {
            resultView.setText("上次执行: 无记录");
            return;
        }
        String time = android.text.format.DateFormat
                .getMediumDateFormat(this).format(new java.util.Date(ts));
        if ("OK".equals(s)) {
            resultView.setTextColor(0xFF2E7D32);
            resultView.setText("上次执行: ✓ 配置完成 · " + time);
        } else if ("PAIR".equals(s)) {
            resultView.setTextColor(0xFFF9A825);
            resultView.setText("上次执行: 需配对 → 请输入 pp/pc 后点按钮 · " + time);
        } else if ("PFAIL".equals(s)) {
            resultView.setTextColor(0xFFF9A825);
            resultView.setText("上次执行: 配对失败 → 请重新获取端口和配对码 · " + time);
        } else {
            resultView.setTextColor(0xFFC62828);
            resultView.setText("上次执行: ✗ " + mapD(d) + " · " + time);
        }
    }

    private static String mapD(String d) {
        switch (d == null ? "" : d) {
            case "端口发现":        return "未找到无线调试端口（检查开关是否已开）";
            case "连接认证":        return "连接被拒（可能密钥失效，请配对）";
            case "tcpip切换":       return "切换 tcpip 端口失败";
            case "配对：端口不通":  return "配对端口连不上（pp 可能已过期）";
            case "配对：握手失败":  return "配对握手失败（检查端口/配对码）";
            case "配对：码错误":    return "配对码错误或已过期，请重新获取";
            case "配对未生效":      return "配对码可能已过期，请重新获取";
            default:                return "失败";
        }
    }

    /** 反射读 sysprop：属性区全进程可读，零权限零风险。 */
    private static String sysprop(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class);
            return (String) get.invoke(null, key);
        } catch (Exception e) {
            return null;
        }
    }
}
