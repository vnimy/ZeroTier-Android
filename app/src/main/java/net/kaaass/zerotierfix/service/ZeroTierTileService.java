package net.kaaass.zerotierfix.service;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.preference.PreferenceManager;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.RequiresApi;

import net.kaaass.zerotierfix.R;
import net.kaaass.zerotierfix.ZerotierFixApplication;
import net.kaaass.zerotierfix.model.NetworkDao;
import net.kaaass.zerotierfix.ui.NetworkListActivity;
import net.kaaass.zerotierfix.util.Constants;

/**
 * 快捷设置磁贴：在通知面板下拉后提供类似飞行模式的 ZeroTier 连接开关。
 * <p>
 * 点击行为：
 * - 已连接 / 正在连接：断开；
 * - 未连接：连接最近一次使用的网络。若尚未授予 VPN 权限，则打开 App 并留下一次性请求，
 * 由 {@link net.kaaass.zerotierfix.ui.NetworkListFragment} 在回到前台后自动继续连接。
 * <p>
 * 磁贴只会在 Android 7.0（API 24）及以上出现。
 */
@RequiresApi(Build.VERSION_CODES.N)
public class ZeroTierTileService extends TileService {
    private static final String TAG = "ZT1_Tile";

    /**
     * 一次性连接请求的有效期。超过这个时间的请求视为过期，避免很久以后打开 App 时意外连接。
     */
    private static final long CONNECT_REQUEST_TTL_MS = 15_000L;

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        updateTile();
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onClick() {
        int state = ZeroTierOneService.getConnectionState();
        if (state != ZeroTierOneService.STATE_IDLE) {
            // 已连接或正在连接：断开（与通知栏「断开」按钮相同的清理流程）
            Log.i(TAG, "Disconnect requested from quick settings tile");
            if (!tryStartService(new Intent(this, ZeroTierOneService.class)
                    .setAction(ZeroTierOneService.ACTION_DISCONNECT))) {
                // 后台启动受限时至少让用户能回到 App 操作
                openApp();
            }
            return;
        }

        long networkId = findLastActivatedNetworkId();
        if (networkId == 0) {
            Toast.makeText(this, R.string.quick_settings_tile_no_network, Toast.LENGTH_SHORT).show();
            openApp();
            return;
        }

        Intent startIntent = new Intent(this, ZeroTierOneService.class)
                .putExtra(ZeroTierOneService.ZT1_NETWORK_ID, networkId);
        if (VpnService.prepare(this) == null) {
            // 已授予 VPN 权限，直接连接，无需打开 App
            Log.i(TAG, "Connect requested from quick settings tile");
            if (!tryStartService(startIntent)) {
                saveConnectRequest(networkId);
                openApp();
            }
        } else {
            // 尚未授予 VPN 权限：记录请求并打开 App，由列表页发起系统授权流程
            Log.i(TAG, "VPN permission required, opening app");
            saveConnectRequest(networkId);
            openApp();
        }
    }

    /**
     * 刷新磁贴的亮灭状态与副标题。
     */
    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        int state = ZeroTierOneService.getConnectionState();
        tile.setState(state == ZeroTierOneService.STATE_IDLE
                ? Tile.STATE_INACTIVE
                : Tile.STATE_ACTIVE);
        tile.setLabel(getString(R.string.quick_settings_tile_label));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int stateRes;
            if (state == ZeroTierOneService.STATE_CONNECTED) {
                stateRes = R.string.quick_settings_tile_state_connected;
            } else if (state == ZeroTierOneService.STATE_CONNECTING) {
                stateRes = R.string.quick_settings_tile_state_connecting;
            } else {
                stateRes = R.string.quick_settings_tile_state_disconnected;
            }
            String stateText = getString(stateRes);
            tile.setSubtitle(stateText);
            tile.setContentDescription(getString(R.string.quick_settings_tile_label) + " " + stateText);
        }
        tile.updateTile();
    }

    /**
     * 读取最近一次连接的网络。没有记录时返回 0。
     */
    private long findLastActivatedNetworkId() {
        try {
            var application = (ZerotierFixApplication) getApplication();
            var daoSession = application.getDaoSession();
            var networks = daoSession.getNetworkDao().queryBuilder()
                    .where(NetworkDao.Properties.LastActivated.eq(true))
                    .list();
            if (networks != null && !networks.isEmpty()) {
                return networks.get(0).getNetworkId();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to query last activated network", e);
        }
        return 0;
    }

    /**
     * 记录一次性连接请求，供 App 回到前台后消费。
     */
    private void saveConnectRequest(long networkId) {
        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putLong(Constants.PREF_TILE_CONNECT_REQUESTED_AT, System.currentTimeMillis())
                .putLong(Constants.PREF_TILE_CONNECT_NETWORK_ID, networkId)
                .apply();
    }

    /**
     * 打开网络列表页面。若 App 已经打开，则复用现有任务栈。
     * 锁屏状态下先要求解锁，避免界面被锁屏遮挡。
     */
    private void openApp() {
        unlockAndRun(this::launchApp);
    }

    private void launchApp() {
        Intent intent = new Intent(this, NetworkListActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            startActivityAndCollapse(pendingIntent);
        } else {
            startActivityAndCollapse(intent);
        }
    }

    private boolean tryStartService(Intent intent) {
        try {
            startService(intent);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to start ZeroTier service from tile", e);
            return false;
        }
    }

    /**
     * 通知系统重新回调 {@link #onStartListening()}，让磁贴显示最新状态。
     */
    public static void requestTileRefresh(Context context) {
        try {
            TileService.requestListeningState(context,
                    new ComponentName(context, ZeroTierTileService.class));
        } catch (Exception e) {
            Log.w(TAG, "Failed to request tile listening state", e);
        }
    }
}
