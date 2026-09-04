package cd.fool.adbport;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class PairingActivity extends Activity {
    private EditText etIp, etCurrentPort, etTargetPort;
    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        etIp = new EditText(this);
        etIp.setHint("设备 IP（如 192.168.1.100）");
        root.addView(etIp);

        etCurrentPort = new EditText(this);
        etCurrentPort.setHint("当前 ADB 端口（配对时的端口）");
        root.addView(etCurrentPort);

        etTargetPort = new EditText(this);
        etTargetPort.setHint("目标端口（如 5555）");
        root.addView(etTargetPort);

        Button btn = new Button(this);
        btn.setText("开始配置（连接→授权MD→切端口）");
        btn.setOnClickListener(v -> {
            String ip = etIp.getText().toString().trim();
            String cp = etCurrentPort.getText().toString().trim();
            String tp = etTargetPort.getText().toString().trim();
            if (ip.isEmpty() || cp.isEmpty() || tp.isEmpty()) {
                Toast.makeText(this, "三项都要填", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent i = new Intent(this, AdbConfigService.class);
            i.putExtra(AdbConfigService.EXTRA_CURRENT_PORT, Integer.parseInt(cp));
            i.putExtra(AdbConfigService.EXTRA_TARGET_PORT, Integer.parseInt(tp));
            startForegroundService(i);
            tvStatus.setText("已触发，结果看 MacroDroid 广播或 logcat");
        });
        root.addView(btn);

        tvStatus = new TextView(this);
        tvStatus.setTextColor(Color.GRAY);
        root.addView(tvStatus);

        setContentView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }
}
