package cd.fool.adbport;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * 透明蹬板：MD 的 am start 拉起本 Activity → app 瞬间前台态 →
 * 起前台服务在 12+ 全部合法 → 立即 finish。不承载任何业务。
 */
public class TempEntryActivity extends Activity {

    private static final String TAG = "AdbPort";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            Intent in = getIntent();
            Intent out = new Intent(this, AdbConfigService.class);
            if (in != null && in.getExtras() != null) out.putExtras(in.getExtras());
            startForegroundService(out);
        } catch (Exception e) {
            Log.e(TAG, "TempEntryActivity forward failed", e);
        }
        finish();
    }
}
