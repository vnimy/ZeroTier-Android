package com.vnimy.zerotier.ui;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.vnimy.zerotier.R;

/**
 * 单片段 Activity
 */
public abstract class SingleFragmentActivity extends AppCompatActivity {
    private static final String TAG = "SingleFragmentActivity";

    public abstract Fragment createFragment();

    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        // Android 15（targetSdk 35）起系统强制 edge-to-edge：窗口会延伸到状态栏/导航栏下方。
        // AppCompat 1.6 不会自己处理，必须把系统栏 inset 换成 padding，否则页面顶部被标题栏盖住。
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_fragment);
        applyEdgeToEdgeInsets();
        FragmentManager supportFragmentManager = getSupportFragmentManager();
        Fragment findFragmentById = supportFragmentManager.findFragmentById(R.id.fragmentContainer);
        if (findFragmentById == null) {
            Fragment createFragment = createFragment();
            setArgs(createFragment);
            supportFragmentManager.beginTransaction().add(R.id.fragmentContainer, createFragment).commit();
            return;
        }
        setArgs(findFragmentById);
    }

    /**
     * 用标题栏 + 系统栏 inset 自己完成边到边布局。
     */
    private void applyEdgeToEdgeInsets() {
        final Toolbar toolbar = findViewById(R.id.toolbar);
        final View root = findViewById(R.id.root);
        final View content = findViewById(R.id.fragmentContainer);
        final View statusBarScrim = findViewById(R.id.statusBarScrim);
        setSupportActionBar(toolbar);

        // 状态栏区域与标题栏同为页面底色：深色主题用浅色图标，浅色主题用深色图标。
        boolean nightMode = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(nightMode);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            // 状态栏那一条与标题栏同为页面底色，标题栏保持 ?attr/actionBarSize 不被压扁，
            // 页面区域不染色（背景交给主题的 windowBackground）。
            ViewGroup.LayoutParams lp = statusBarScrim.getLayoutParams();
            if (lp.height != bars.top) {
                lp.height = bars.top;
                statusBarScrim.setLayoutParams(lp);
            }
            // 左右留给横屏的刘海/挖孔，底部留给导航栏与输入法。
            content.setPadding(bars.left, 0, bars.right, Math.max(bars.bottom, ime.bottom));
            return WindowInsetsCompat.CONSUMED;
        });
    }

    private void setArgs(Fragment fragment) {
        Bundle extras;
        Intent intent = getIntent();
        if (intent != null && (extras = intent.getExtras()) != null && !fragment.isAdded()) {
            try {
                fragment.setArguments(extras);
            } catch (IllegalArgumentException unused) {
                Log.e(TAG, "Exception setting arguments on fragment");
            }
        }
    }
}
