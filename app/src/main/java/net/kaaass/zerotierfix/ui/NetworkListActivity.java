package net.kaaass.zerotierfix.ui;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

/**
 * 网络列表 fragment 的容器 activity
 */
public class NetworkListActivity extends SingleFragmentActivity {
    private static final int REQUEST_POST_NOTIFICATIONS = 1;
    private static final String PREFS_UI = "zerotierfix_ui";
    private static final String PREF_ASKED_POST_NOTIFICATIONS = "asked_post_notifications";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestNotificationPermissionOnce();
    }

    /**
     * Android 13 起通知属于运行时权限。没有它，前台服务的常驻通知（含「断开连接」按钮）不会显示。
     * 只问一次，拒绝后不再纠缠（用户可随时在系统设置里打开）。
     */
    private void requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        SharedPreferences prefs = getSharedPreferences(PREFS_UI, MODE_PRIVATE);
        if (prefs.getBoolean(PREF_ASKED_POST_NOTIFICATIONS, false)) {
            return;
        }
        prefs.edit().putBoolean(PREF_ASKED_POST_NOTIFICATIONS, true).apply();
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_POST_NOTIFICATIONS);
    }

    @Override
    public Fragment createFragment() {
        return new NetworkListFragment();
    }
}
