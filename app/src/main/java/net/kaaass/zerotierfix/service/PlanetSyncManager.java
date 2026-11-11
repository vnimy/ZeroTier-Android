package net.kaaass.zerotierfix.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import net.kaaass.zerotierfix.util.Constants;
import net.kaaass.zerotierfix.util.PlanetSyncer;

import java.io.File;

/**
 * 行星服务器（Planet 服务器）同步：把远端 planet 下载到内核读取的路径。
 *
 * <p>落盘路径与切换方式完全复用原有的自定义 Planet 机制：
 * core（ZT 1.12.2）读取 planet 状态对象时使用文件名 {@code planet}，
 * {@link DataStore} 在 {@code planet_use_custom} 打开时把它重定向到
 * {@code getFilesDir()/planet.custom}。</p>
 */
public final class PlanetSyncManager {

    private static final String TAG = "PlanetSyncManager";

    private PlanetSyncManager() {
    }

    /**
     * 内核读取 planet 时实际落盘的文件
     */
    public static File customPlanetFile(Context context) {
        return new File(context.getFilesDir(), Constants.FILE_CUSTOM_PLANET);
    }

    /**
     * 上一份可用 planet 的备份
     */
    public static File customPlanetBackupFile(Context context) {
        return new File(context.getFilesDir(), Constants.FILE_PLANET_BACKUP);
    }

    /**
     * 是否配置了行星服务器地址。未配置时整个同步链路不产生任何副作用。
     */
    public static boolean isEnabled(Context context) {
        return !PlanetSyncer.normalizeUrl(configuredUrl(context)).isEmpty();
    }

    public static String configuredUrl(Context context) {
        return preferences(context).getString(Constants.PREF_PLANET_SERVER_URL, "");
    }

    /**
     * 判断本次启动是否需要下载
     */
    public static boolean needsSync(Context context) {
        if (!isEnabled(context)) {
            return false;
        }
        if (preferences(context).getBoolean(Constants.PREF_PLANET_SERVER_AUTO_UPDATE, false)) {
            // 自动更新：每次启动都拉取
            return true;
        }
        // 非自动更新：只在本地还没有可用 planet 时拉取
        return PlanetSyncer.validatePlanetFile(customPlanetFile(context)) != null;
    }

    /**
     * 启动 core 之前调用。需要时下载 planet，并保证失败时回退到上一份或内置 planet。
     *
     * @return 本次同步结果；未配置地址时返回 null（不改变原有行为）
     */
    public static PlanetSyncer.Result syncBeforeStart(Context context) {
        if (!isEnabled(context)) {
            return null;
        }
        PlanetSyncer.Result result = null;
        if (needsSync(context)) {
            result = syncNow(context);
        }
        // 无论成功与否，只有在本地存在合法 planet 时才启用自定义 planet，
        // 否则关闭开关，让内核回退到内置 planet。
        boolean usable = PlanetSyncer.validatePlanetFile(customPlanetFile(context)) == null;
        boolean current = preferences(context).getBoolean(Constants.PREF_PLANET_USE_CUSTOM, false);
        if (current != usable) {
            preferences(context).edit().putBoolean(Constants.PREF_PLANET_USE_CUSTOM, usable).apply();
            Log.i(TAG, "planet_use_custom -> " + usable);
        }
        Log.i(TAG, "Planet before start: usable=" + usable
                + ", result=" + (result == null ? "not needed" : result.toString()));
        return result;
    }

    /**
     * 立即下载一次（设置页“保存并同步”也走这里）
     */
    public static PlanetSyncer.Result syncNow(Context context) {
        SharedPreferences preferences = preferences(context);
        String url = preferences.getString(Constants.PREF_PLANET_SERVER_URL, "");
        String token = preferences.getString(Constants.PREF_PLANET_SERVER_TOKEN, "");
        String sha256 = preferences.getString(Constants.PREF_PLANET_SERVER_SHA256, "");
        File filesDir = context.getFilesDir();
        PlanetSyncer.Result result = PlanetSyncer.downloadAndInstall(
                new File(filesDir, Constants.FILE_CUSTOM_PLANET),
                new File(filesDir, Constants.FILE_PLANET_TEMP),
                new File(filesDir, Constants.FILE_PLANET_BACKUP),
                url, token, sha256);
        if (result.success) {
            preferences.edit()
                    .putLong(Constants.PREF_PLANET_SERVER_LAST_SYNC, System.currentTimeMillis())
                    .apply();
            preferences.edit().putBoolean(Constants.PREF_PLANET_USE_CUSTOM, true).apply();
            Log.i(TAG, "Planet updated from " + url + ", sha256=" + result.sha256);
        } else {
            Log.w(TAG, "Planet sync failed: " + result.message);
        }
        return result;
    }

    /**
     * 回退说明，用于提示用户当前实际生效的 planet 来源
     */
    public static String fallbackDescription(Context context) {
        if (PlanetSyncer.validatePlanetFile(customPlanetFile(context)) == null) {
            return "上一份已校验的 planet";
        }
        return "ZeroTier 内置 planet";
    }

    private static SharedPreferences preferences(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }
}
