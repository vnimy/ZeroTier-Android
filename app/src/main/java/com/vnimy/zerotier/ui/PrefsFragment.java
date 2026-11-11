package com.vnimy.zerotier.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.content.pm.PackageInfoCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreference;

import com.google.android.material.snackbar.BaseTransientBottomBar;
import com.google.android.material.snackbar.Snackbar;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanIntentResult;
import com.journeyapps.barcodescanner.ScanOptions;

import com.vnimy.zerotier.R;
import com.vnimy.zerotier.service.PlanetSyncManager;
import com.vnimy.zerotier.service.ZeroTierOneService;
import com.vnimy.zerotier.util.Constants;
import com.vnimy.zerotier.util.FileUtil;
import com.vnimy.zerotier.util.PlanetSyncer;

import org.apache.commons.io.FileUtils;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * 设置页面 fragment
 */
public class PrefsFragment extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener {

    public static final int PLANET_DOWNLOAD_CONN_TIMEOUT = 5 * 1000;
    public static final int PLANET_DOWNLOAD_TIMEOUT = 10 * 1000;
    /**
     * 检查更新请求的超时
     */
    private static final int UPDATE_MANIFEST_TIMEOUT = 6 * 1000;
    private static final String TAG = "PreferencesFragment";
    /**
     * Plant 文件固定头
     */
    private static final byte[] PLANET_FILE_HEADER = new byte[]{
            0x01,  // World type, 0x01 = planet
    };
    private SwitchPreference prefPlanetUseCustom;
    private Preference prefSetPlanetFile;
    private Preference prefPlanetServer;
    private Dialog planetDialog = null;
    private Dialog loadingDialog = null;
    private ActivityResultLauncher<Intent> planetFileSelectLauncher = null;
    private ActivityResultLauncher<ScanOptions> qrScanLauncher = null;
    private ActivityResultLauncher<String> cameraPermissionLauncher = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        // 初始化 Planet 文件选择结果回调
        this.planetFileSelectLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), (activityResult) -> {
            var result = activityResult.getResultCode();
            var data = activityResult.getData();
            if (result == -1 && data != null) {
                // Planet 自定义文件设置
                Uri uriData = data.getData();
                if (uriData == null) {
                    Log.e(TAG, "Invalid planet URI");
                    return;
                }
                // 复制文件
                try (InputStream in = requireContext().getContentResolver().openInputStream(uriData)) {
                    FileUtils.copyInputStreamToFile(in, FileUtil.tempFile(requireContext()));
                    boolean success = dealTempPlanetFile();
                    if (!success) {
                        // 校验失败
                        Toast.makeText(getContext(), R.string.planet_wrong_format, Toast.LENGTH_LONG).show();
                        closePlanetDialog();
                        return;
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Cannot copy planet file", e);
                    // 设置失败
                    Toast.makeText(getContext(), R.string.cannot_open_planet, Toast.LENGTH_LONG).show();
                    closePlanetDialog();
                    return;
                } finally {
                    FileUtil.clearTempFile(requireContext());
                }
                Log.i(TAG, "Copy planet file successfully");
                // 关闭对话框
                Snackbar.make(requireView(), R.string.set_planet_succ, BaseTransientBottomBar.LENGTH_LONG).show();
                closePlanetDialog();
            } else {
                Toast.makeText(getContext(), R.string.cannot_open_planet, Toast.LENGTH_LONG).show();
            }
        });

        // 初始化二维码扫描回调和相机权限回调
        this.qrScanLauncher = registerForActivityResult(new ScanContract(), this::onQrScanResult);
        this.cameraPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) {
                        startQrScan();
                    } else {
                        Toast.makeText(getContext(), R.string.planet_qr_need_camera, Toast.LENGTH_LONG).show();
                    }
                });
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.preferences);

        this.prefPlanetUseCustom = findPreference(Constants.PREF_PLANET_USE_CUSTOM);
        this.prefSetPlanetFile = findPreference(Constants.PREF_SET_PLANET_FILE);
        this.prefPlanetServer = findPreference(Constants.PREF_PLANET_SERVER_URL);

        // 行星服务器（自动下载 planet）配置
        Objects.requireNonNull(this.prefPlanetServer).setOnPreferenceClickListener(preference -> {
            showPlanetServerDialog();
            return true;
        });

        // Plane 配置开关
        Objects.requireNonNull(this.prefPlanetUseCustom).setOnPreferenceClickListener(preference -> {
            // 判断状态
            updatePlanetSetting();
            var pref = preference.getSharedPreferences();
            if (pref == null || !pref.getBoolean(Constants.PREF_PLANET_USE_CUSTOM, false)) {
                // 设置为假时直接退出
                return true;
            }
            // 设置为真时，如果还没有设置文件则提示设置文件
            if (customPlanetFileNotExit()) {
                showPlanetDialog();
            }
            return true;
        });

        // 扫描二维码导入行星服务器地址
        Preference prefScanQr = findPreference(Constants.PREF_PLANET_SCAN_QR);
        if (prefScanQr != null) {
            prefScanQr.setOnPreferenceClickListener(preference -> {
                if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED) {
                    startQrScan();
                } else {
                    this.cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
                }
                return true;
            });
        }

        // 打开 Plant 文件设置
        Objects.requireNonNull(this.prefSetPlanetFile).setOnPreferenceClickListener(preference -> {
            showPlanetDialog();
            return true;
        });
        // 检查更新
        Preference prefCheckUpdate = findPreference(Constants.PREF_CHECK_UPDATE);
        if (prefCheckUpdate != null) {
            prefCheckUpdate.setOnPreferenceClickListener(preference -> {
                checkForUpdates();
                return true;
            });
        }
        updatePlanetSetting();
        updatePlanetServerSummary();
    }

    /**
     * 启动二维码扫描
     */
    private void startQrScan() {
        ScanOptions options = new ScanOptions();
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        options.setPrompt(getString(R.string.planet_qr_prompt));
        options.setBeepEnabled(false);
        options.setOrientationLocked(false);
        this.qrScanLauncher.launch(options);
    }

    /**
     * 处理二维码结果：二维码内容形如 https://host/planet?token=xxx
     */
    private void onQrScanResult(ScanIntentResult result) {
        String contents = result.getContents();
        if (contents == null) {
            return;
        }

        String raw = contents.trim();
        if (!raw.contains("://")) {
            raw = "http://" + raw;
        }

        Uri uri;
        try {
            uri = Uri.parse(raw);
        } catch (Exception e) {
            Log.e(TAG, "Invalid QR content", e);
            Toast.makeText(getContext(), R.string.planet_qr_invalid, Toast.LENGTH_LONG).show();
            return;
        }

        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            Toast.makeText(getContext(), R.string.planet_qr_invalid, Toast.LENGTH_LONG).show();
            return;
        }

        // token 是可选查询参数，去掉后剩下的就是 planet 下载地址
        String token = uri.getQueryParameter("token");
        String url = uri.buildUpon().clearQuery().fragment(null).build().toString();

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(requireContext());
        SharedPreferences.Editor editor = preferences.edit()
                .putString(Constants.PREF_PLANET_SERVER_URL, url);
        if (token != null && !token.trim().isEmpty()) {
            editor.putString(Constants.PREF_PLANET_SERVER_TOKEN, token.trim());
        }
        editor.apply();

        updatePlanetServerSummary();
        Toast.makeText(getContext(), R.string.planet_qr_imported, Toast.LENGTH_SHORT).show();
        syncPlanetFromServer();
    }

    /**
     * 更新行星服务器设置项的摘要
     */
    private void updatePlanetServerSummary() {
        if (this.prefPlanetServer == null) {
            return;
        }
        String url = PlanetSyncer.normalizeUrl(PreferenceManager
                .getDefaultSharedPreferences(requireContext())
                .getString(Constants.PREF_PLANET_SERVER_URL, ""));
        if (url.isEmpty()) {
            this.prefPlanetServer.setSummary(R.string.planet_server_summary);
        } else {
            this.prefPlanetServer.setSummary(url);
        }
    }

    /**
     * 显示行星服务器配置对话框：地址 + token + sha256 + 自动更新
     */
    private void showPlanetServerDialog() {
        View view = LayoutInflater.from(getContext()).inflate(R.layout.dialog_planet_server, null);
        final EditText urlInput = view.findViewById(R.id.planet_server_url_input);
        final EditText tokenInput = view.findViewById(R.id.planet_server_token_input);
        final EditText sha256Input = view.findViewById(R.id.planet_server_sha256_input);
        final CheckBox autoUpdate = view.findViewById(R.id.planet_server_auto_update);

        final SharedPreferences preferences = PreferenceManager
                .getDefaultSharedPreferences(requireContext());
        urlInput.setText(preferences.getString(Constants.PREF_PLANET_SERVER_URL, ""));
        tokenInput.setText(preferences.getString(Constants.PREF_PLANET_SERVER_TOKEN, ""));
        sha256Input.setText(preferences.getString(Constants.PREF_PLANET_SERVER_SHA256, ""));
        autoUpdate.setChecked(preferences.getBoolean(Constants.PREF_PLANET_SERVER_AUTO_UPDATE, false));

        new AlertDialog.Builder(getContext())
                .setView(view)
                .setTitle(R.string.planet_server)
                .setPositiveButton(R.string.planet_server_save_and_sync, (dialog, which) -> {
                    String url = PlanetSyncer.normalizeUrl(urlInput.getText().toString());
                    preferences.edit()
                            .putString(Constants.PREF_PLANET_SERVER_URL, url)
                            .putString(Constants.PREF_PLANET_SERVER_TOKEN,
                                    tokenInput.getText().toString().trim())
                            .putString(Constants.PREF_PLANET_SERVER_SHA256,
                                    sha256Input.getText().toString().trim())
                            .putBoolean(Constants.PREF_PLANET_SERVER_AUTO_UPDATE, autoUpdate.isChecked())
                            .apply();
                    updatePlanetServerSummary();
                    if (url.isEmpty()) {
                        // 清空地址即恢复原有行为，不做任何下载
                        Toast.makeText(getContext(), R.string.planet_server_cleared,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    syncPlanetFromServer();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .show();
    }

    /**
     * 立即从行星服务器同步一次 planet 文件
     */
    private void syncPlanetFromServer() {
        showLoadingDialog(R.string.downloading);
        new Thread(() -> {
            final PlanetSyncer.Result result = PlanetSyncManager.syncNow(requireContext());
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                closeLoadingDialog();
                if (result.success) {
                    Snackbar.make(requireView(), R.string.planet_sync_success,
                            BaseTransientBottomBar.LENGTH_LONG).show();
                } else {
                    Toast.makeText(getContext(),
                            getString(R.string.planet_sync_failure, result.message),
                            Toast.LENGTH_LONG).show();
                }
                // 同步成功会打开自定义 planet 开关，这里刷新界面
                this.prefPlanetUseCustom.setChecked(PreferenceManager
                        .getDefaultSharedPreferences(requireContext())
                        .getBoolean(Constants.PREF_PLANET_USE_CUSTOM, false));
                updatePlanetSetting();
                updatePlanetServerSummary();
            });
        }).start();
    }

    /**
     * 显示选择 Planet 文件来源对话框
     */
    private void showPlanetDialog() {
        View view = LayoutInflater.from(getContext()).inflate(R.layout.dialog_custom_planet_select, null);

        // 选择文件
        View viewFile = view.findViewById(R.id.from_file);
        viewFile.setOnClickListener(v -> this.showPlanetFromFileDialog());

        // 选择 URL
        View viewUrl = view.findViewById(R.id.from_url);
        viewUrl.setOnClickListener(v -> this.showPlanetFromUrlDialog());

        // 窗口
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext())
                .setView(view)
                .setTitle(R.string.load_planet_file)
                .setOnCancelListener(dialog -> closePlanetDialog());

        this.planetDialog = builder.create();
        this.planetDialog.show();
    }

    /**
     * 显示选择 Planet 文件的文件选择器
     */
    private void showPlanetFromFileDialog() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        this.planetFileSelectLauncher.launch(intent);
    }

    /**
     * 显示输入 Planet 文件 URL 对话框
     */
    private void showPlanetFromUrlDialog() {
        View view = LayoutInflater.from(getContext()).inflate(R.layout.dialog_planet_url, null);

        final EditText editText = view.findViewById(R.id.planet_url);

        AlertDialog.Builder builder = new AlertDialog.Builder(getContext())
                .setView(view)
                .setTitle(R.string.import_via_url)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String url = editText.getText().toString();
                    URL fileUrl;
                    try {
                        fileUrl = new URL(url);
                    } catch (MalformedURLException e) {
                        Toast.makeText(getContext(), R.string.wrong_url_format, Toast.LENGTH_LONG).show();
                        // 不必关闭对话框
                        return;
                    }

                    // 关闭对话框
                    if (this.planetDialog != null) {
                        this.planetDialog.dismiss();
                        this.planetDialog = null;
                    }
                    // 显示加载动画
                    showLoadingDialog(R.string.downloading);
                    // 下载 Planet 文件
                    new Thread(() -> {
                        try {
                            FileUtils.copyURLToFile(fileUrl,
                                    FileUtil.tempFile(requireContext()),
                                    PLANET_DOWNLOAD_CONN_TIMEOUT, PLANET_DOWNLOAD_TIMEOUT);
                            boolean success = dealTempPlanetFile();
                            if (!success) {
                                // 校验失败
                                requireActivity().runOnUiThread(() -> {
                                    Toast.makeText(getContext(), R.string.planet_wrong_format, Toast.LENGTH_LONG).show();
                                    closePlanetDialog();
                                    closeLoadingDialog();
                                });
                                return;
                            }
                        } catch (IOException e) {
                            Log.e(TAG, "Cannot download planet file", e);
                            // 设置失败
                            requireActivity().runOnUiThread(() -> {
                                int message = R.string.cannot_download_planet_file;
                                if (e instanceof SocketTimeoutException) {
                                    message = R.string.planet_download_timeout;
                                }
                                Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
                                closePlanetDialog();
                                closeLoadingDialog();
                            });
                            return;
                        } finally {
                            FileUtil.clearTempFile(requireContext());
                        }

                        // 关闭对话框
                        requireActivity().runOnUiThread(() -> {
                            Snackbar.make(requireView(), R.string.set_planet_succ, BaseTransientBottomBar.LENGTH_LONG).show();
                            closePlanetDialog();
                            closeLoadingDialog();
                        });
                    }).start();
                });

        builder.create().show();
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key.equals(Constants.PREF_NETWORK_USE_CELLULAR_DATA)) {
            // 移动网络数据配置
            if (sharedPreferences.getBoolean(Constants.PREF_NETWORK_USE_CELLULAR_DATA, false)) {
                requireActivity().startService(new Intent(getActivity(), ZeroTierOneService.class));
            }
        }
    }

    /**
     * 检查是否存在 Planet 文件
     */
    private boolean customPlanetFileNotExit() {
        File file = new File(requireActivity().getFilesDir(), Constants.FILE_CUSTOM_PLANET);
        return !file.exists();
    }

    /**
     * 关闭 Planet 对话框
     */
    private void closePlanetDialog() {
        // 关闭对话框
        if (this.planetDialog != null) {
            this.planetDialog.dismiss();
            this.planetDialog = null;
        }
        // 如果没有设置文件，就取消选项
        if (customPlanetFileNotExit()) {
            this.prefPlanetUseCustom.setChecked(false);
        }
    }

    /**
     * 更新 Planet 设置选项
     */
    private void updatePlanetSetting() {
        boolean useCustom = PreferenceManager.getDefaultSharedPreferences(getContext())
                .getBoolean(Constants.PREF_PLANET_USE_CUSTOM, false);
        this.prefSetPlanetFile.setEnabled(useCustom);
    }

    /**
     * 将临时文件设置为 Planet 文件
     */
    private boolean dealTempPlanetFile() {
        // Plant 文件校验
        File temp = FileUtil.tempFile(requireContext());
        byte[] buf = new byte[PLANET_FILE_HEADER.length];
        try (FileInputStream in = new FileInputStream(temp)) {
            // 读入文件头
            if (in.read(buf) != PLANET_FILE_HEADER.length) {
                return false;
            }
            // 校验
            if (!Arrays.equals(buf, PLANET_FILE_HEADER)) {
                Log.i(TAG, "Planet file has a wrong header");
                return false;
            }
        } catch (IOException e) {
            return false;
        }
        // 移动临时文件
        File dest = new File(requireActivity().getFilesDir(), Constants.FILE_CUSTOM_PLANET);
        return temp.renameTo(dest);
    }

    /**
     * 显示加载框
     */
    private void showLoadingDialog(int prompt) {
        closeLoadingDialog();
        // 创建
        View view = LayoutInflater.from(getContext()).inflate(R.layout.dialog_loading, null);

        TextView textPrompt = view.findViewById(R.id.prompt);
        textPrompt.setText(prompt);

        this.loadingDialog = new AlertDialog.Builder(getContext())
                .setView(view)
                .setCancelable(false)
                .create();
        this.loadingDialog.show();
    }

    /**
     * 关闭加载框
     */
    private void closeLoadingDialog() {
        if (this.loadingDialog != null) {
            this.loadingDialog.dismiss();
        }
    }

    /**
     * 检查更新：从行星服务器根路径读取 version.json，与本机 versionCode 比较
     */
    private void checkForUpdates() {
        if (!isAdded()) {
            return;
        }
        Context context = requireContext();
        String serverUrl = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(Constants.PREF_PLANET_SERVER_URL, "");
        if (PlanetSyncer.normalizeUrl(serverUrl).isEmpty()) {
            Toast.makeText(context, R.string.check_update_no_server, Toast.LENGTH_LONG).show();
            return;
        }
        final String manifestUrl = updateManifestUrl(serverUrl);
        Log.i(TAG, "Checking updates at " + manifestUrl);
        Toast.makeText(context, R.string.check_update_checking, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                JSONObject manifest = fetchUpdateManifest(manifestUrl);
                final int latestCode = manifest.optInt("versionCode", 0);
                final String latestName = manifest.optString("versionName", String.valueOf(latestCode));
                final String notes = manifest.optString("notes", "");
                final String downloadUrl = manifest.optString("url", "");
                final int currentCode = currentVersionCode();
                Log.i(TAG, "Update check: current=" + currentCode + ", latest=" + latestCode);
                if (latestCode <= currentCode) {
                    postOnUi(() -> Toast.makeText(requireContext(), R.string.check_update_latest,
                            Toast.LENGTH_LONG).show());
                    return;
                }
                postOnUi(() -> showUpdateDialog(latestName, notes, downloadUrl));
            } catch (Exception e) {
                Log.w(TAG, "Update check failed", e);
                postOnUi(() -> Toast.makeText(requireContext(), R.string.check_update_failed,
                        Toast.LENGTH_LONG).show());
            }
        }, "Update Check").start();
    }

    /**
     * 行星服务器地址（可能带 /planet 路径）→ 同主机的 /version.json
     */
    private static String updateManifestUrl(String serverUrl) {
        Uri uri = Uri.parse(PlanetSyncer.normalizeUrl(serverUrl));
        String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
        String authority = uri.getAuthority() != null ? uri.getAuthority() : uri.getHost();
        return scheme + "://" + authority + "/version.json";
    }

    private static JSONObject fetchUpdateManifest(String manifestUrl) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(manifestUrl).openConnection();
            connection.setConnectTimeout(UPDATE_MANIFEST_TIMEOUT);
            connection.setReadTimeout(UPDATE_MANIFEST_TIMEOUT);
            connection.setRequestProperty("Accept", "application/json");
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code);
            }
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buffer = new char[512];
                int read;
                while ((read = reader.read(buffer)) > 0 && body.length() < 64 * 1024) {
                    body.append(buffer, 0, read);
                }
            }
            return new JSONObject(body.toString());
        } catch (org.json.JSONException e) {
            throw new IOException("invalid version.json", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private int currentVersionCode() {
        try {
            PackageInfo info = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0);
            return (int) PackageInfoCompat.getLongVersionCode(info);
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    private void showUpdateDialog(String versionName, String notes, String downloadUrl) {
        if (!isAdded()) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.check_update_available, versionName))
                .setMessage(notes.trim().isEmpty()
                        ? getString(R.string.check_update_notes_empty) : notes.trim())
                .setNegativeButton(android.R.string.cancel, null);
        if (!downloadUrl.trim().isEmpty()) {
            builder.setPositiveButton(R.string.check_update_open, (dialog, which) -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl.trim())));
                } catch (Exception e) {
                    Log.w(TAG, "Cannot open " + downloadUrl, e);
                }
            });
        }
        builder.show();
    }

    /**
     * 后台线程回到 UI 线程，fragment 已分离时静默丢弃
     */
    private void postOnUi(Runnable action) {
        if (!isAdded()) {
            return;
        }
        requireActivity().runOnUiThread(() -> {
            if (isAdded()) {
                action.run();
            }
        });
    }
}
