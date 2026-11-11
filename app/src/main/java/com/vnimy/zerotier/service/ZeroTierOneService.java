package com.vnimy.zerotier.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.VpnService;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.zerotier.sdk.Event;
import com.zerotier.sdk.EventListener;
import com.zerotier.sdk.Node;
import com.zerotier.sdk.ResultCode;
import com.zerotier.sdk.VirtualNetworkConfig;
import com.zerotier.sdk.VirtualNetworkConfigListener;
import com.zerotier.sdk.VirtualNetworkConfigOperation;
import com.zerotier.sdk.VirtualNetworkStatus;

import com.vnimy.zerotier.R;
import com.vnimy.zerotier.ZeroTierApplication;
import com.vnimy.zerotier.events.AfterJoinNetworkEvent;
import com.vnimy.zerotier.events.ErrorEvent;
import com.vnimy.zerotier.events.IsServiceRunningReplyEvent;
import com.vnimy.zerotier.events.IsServiceRunningRequestEvent;
import com.vnimy.zerotier.events.ManualDisconnectEvent;
import com.vnimy.zerotier.events.NetworkConfigChangedByUserEvent;
import com.vnimy.zerotier.events.NetworkListReplyEvent;
import com.vnimy.zerotier.events.NetworkListRequestEvent;
import com.vnimy.zerotier.events.NetworkReconfigureEvent;
import com.vnimy.zerotier.events.NodeDestroyedEvent;
import com.vnimy.zerotier.events.NodeIDEvent;
import com.vnimy.zerotier.events.NodeStatusEvent;
import com.vnimy.zerotier.events.NodeStatusRequestEvent;
import com.vnimy.zerotier.events.OrbitMoonEvent;
import com.vnimy.zerotier.events.PeerInfoReplyEvent;
import com.vnimy.zerotier.events.PeerInfoRequestEvent;
import com.vnimy.zerotier.events.StopEvent;
import com.vnimy.zerotier.events.VPNErrorEvent;
import com.vnimy.zerotier.events.VirtualNetworkConfigChangedEvent;
import com.vnimy.zerotier.events.VirtualNetworkConfigReplyEvent;
import com.vnimy.zerotier.events.VirtualNetworkConfigRequestEvent;
import com.vnimy.zerotier.model.AppNode;
import com.vnimy.zerotier.model.MoonOrbit;
import com.vnimy.zerotier.model.Network;
import com.vnimy.zerotier.model.NetworkDao;
import com.vnimy.zerotier.model.type.DNSMode;
import com.vnimy.zerotier.ui.NetworkListActivity;
import com.vnimy.zerotier.util.Constants;
import com.vnimy.zerotier.util.DatabaseUtils;
import com.vnimy.zerotier.util.InetAddressUtils;
import com.vnimy.zerotier.util.PlanetSyncer;
import com.vnimy.zerotier.util.NetworkInfoUtils;
import com.vnimy.zerotier.util.StringUtils;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

// TODO: clear up
public class ZeroTierOneService extends VpnService implements Runnable, EventListener, VirtualNetworkConfigListener {
    public static final int MSG_JOIN_NETWORK = 1;
    public static final int MSG_LEAVE_NETWORK = 2;
    public static final String ZT1_NETWORK_ID = "com.zerotier.one.network_id";
    public static final String ZT1_USE_DEFAULT_ROUTE = "com.zerotier.one.use_default_route";
    private static final String[] DISALLOWED_APPS = {"com.android.vending"};
    private static final String TAG = "ZT1_Service";
    private static final int ZT_NOTIFICATION_TAG = 5919812;

    /**
     * 通知栏「断开」按钮发来的动作
     */
    public static final String ACTION_DISCONNECT = "com.vnimy.zerotier.action.DISCONNECT";

    /**
     * 快捷设置磁贴使用的连接状态
     */
    public static final int STATE_IDLE = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_CONNECTED = 2;

    private static volatile int connectionState = STATE_IDLE;

    public static int getConnectionState() {
        return connectionState;
    }

    private final IBinder mBinder = new ZeroTierBinder();
    private final DataStore dataStore = new DataStore(this);
    private final EventBus eventBus = EventBus.getDefault();
    private final Map<Long, VirtualNetworkConfig> virtualNetworkConfigMap = new HashMap();
    FileInputStream in;
    FileOutputStream out;
    DatagramSocket svrSocket;
    ParcelFileDescriptor vpnSocket;
    private int bindCount = 0;
    private boolean disableIPv6 = false;
    private int mStartID = -1;
    private long networkId = 0;
    private long nextBackgroundTaskDeadline = 0;
    private Node node;
    private NotificationManager notificationManager;
    private TunTapAdapter tunTapAdapter;
    private UdpCom udpCom;
    private Thread udpThread;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final Set<android.net.Network> physicalNetworks = new HashSet<>();
    private Thread v4MulticastScanner = new Thread() {
        /* class com.zerotier.one.service.ZeroTierOneService.AnonymousClass1 */
        List<String> subscriptions = new ArrayList<>();

        @Override
        public void run() {
            Log.d(ZeroTierOneService.TAG, "IPv4 Multicast Scanner Thread Started.");
            while (!isInterrupted()) {
                try {
                    List<String> groups = NetworkInfoUtils.listMulticastGroupOnInterface("tun0", false);

                    ArrayList<String> arrayList2 = new ArrayList<>(this.subscriptions);
                    ArrayList<String> arrayList3 = new ArrayList<>(groups);
                    arrayList3.removeAll(arrayList2);
                    for (String str : arrayList3) {
                        try {
                            byte[] hexStringToByteArray = StringUtils.hexStringToBytes(str);
                            for (int i = 0; i < hexStringToByteArray.length / 2; i++) {
                                byte b = hexStringToByteArray[i];
                                hexStringToByteArray[i] = hexStringToByteArray[(hexStringToByteArray.length - i) - 1];
                                hexStringToByteArray[(hexStringToByteArray.length - i) - 1] = b;
                            }
                            ResultCode multicastSubscribe = ZeroTierOneService.this.node.multicastSubscribe(ZeroTierOneService.this.networkId, TunTapAdapter.multicastAddressToMAC(InetAddress.getByAddress(hexStringToByteArray)));
                            if (multicastSubscribe != ResultCode.RESULT_OK) {
                                Log.e(ZeroTierOneService.TAG, "Error when calling multicastSubscribe: " + multicastSubscribe);
                            }
                        } catch (Exception e) {
                            Log.e(ZeroTierOneService.TAG, e.toString(), e);
                        }
                    }
                    arrayList2.removeAll(new ArrayList<>(groups));
                    for (String str2 : arrayList2) {
                        try {
                            byte[] hexStringToByteArray2 = StringUtils.hexStringToBytes(str2);
                            for (int i2 = 0; i2 < hexStringToByteArray2.length / 2; i2++) {
                                byte b2 = hexStringToByteArray2[i2];
                                hexStringToByteArray2[i2] = hexStringToByteArray2[(hexStringToByteArray2.length - i2) - 1];
                                hexStringToByteArray2[(hexStringToByteArray2.length - i2) - 1] = b2;
                            }
                            ResultCode multicastUnsubscribe = ZeroTierOneService.this.node.multicastUnsubscribe(ZeroTierOneService.this.networkId, TunTapAdapter.multicastAddressToMAC(InetAddress.getByAddress(hexStringToByteArray2)));
                            if (multicastUnsubscribe != ResultCode.RESULT_OK) {
                                Log.e(ZeroTierOneService.TAG, "Error when calling multicastUnsubscribe: " + multicastUnsubscribe);
                            }
                        } catch (Exception e) {
                            Log.e(ZeroTierOneService.TAG, e.toString(), e);
                        }
                    }
                    this.subscriptions = groups;
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Log.d(ZeroTierOneService.TAG, "V4 Multicast Scanner Thread Interrupted", e);
                    break;
                }
            }
            Log.d(ZeroTierOneService.TAG, "IPv4 Multicast Scanner Thread Ended.");
        }
    };
    private Thread v6MulticastScanner = new Thread() {
        /* class com.zerotier.one.service.ZeroTierOneService.AnonymousClass2 */
        List<String> subscriptions = new ArrayList<>();

        @Override
        public void run() {
            Log.d(ZeroTierOneService.TAG, "IPv6 Multicast Scanner Thread Started.");
            while (!isInterrupted()) {
                try {
                    List<String> groups = NetworkInfoUtils.listMulticastGroupOnInterface("tun0", true);

                    ArrayList<String> arrayList2 = new ArrayList<>(this.subscriptions);
                    ArrayList<String> arrayList3 = new ArrayList<>(groups);
                    arrayList3.removeAll(arrayList2);
                    for (String str : arrayList3) {
                        try {
                            ResultCode multicastSubscribe = ZeroTierOneService.this.node.multicastSubscribe(ZeroTierOneService.this.networkId, TunTapAdapter.multicastAddressToMAC(InetAddress.getByAddress(StringUtils.hexStringToBytes(str))));
                            if (multicastSubscribe != ResultCode.RESULT_OK) {
                                Log.e(ZeroTierOneService.TAG, "Error when calling multicastSubscribe: " + multicastSubscribe);
                            }
                        } catch (Exception e) {
                            Log.e(ZeroTierOneService.TAG, e.toString(), e);
                        }
                    }
                    arrayList2.removeAll(new ArrayList<>(groups));
                    for (String str2 : arrayList2) {
                        try {
                            ResultCode multicastUnsubscribe = ZeroTierOneService.this.node.multicastUnsubscribe(ZeroTierOneService.this.networkId, TunTapAdapter.multicastAddressToMAC(InetAddress.getByAddress(StringUtils.hexStringToBytes(str2))));
                            if (multicastUnsubscribe != ResultCode.RESULT_OK) {
                                Log.e(ZeroTierOneService.TAG, "Error when calling multicastUnsubscribe: " + multicastUnsubscribe);
                            }
                        } catch (Exception e) {
                            Log.e(ZeroTierOneService.TAG, e.toString(), e);
                        }
                    }
                    this.subscriptions = groups;
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Log.d(ZeroTierOneService.TAG, "V6 Multicast Scanner Thread Interrupted", e);
                    break;
                }
            }
            Log.d(ZeroTierOneService.TAG, "IPv6 Multicast Scanner Thread Ended.");
        }
    };
    private Thread vpnThread;

    public VirtualNetworkConfig getVirtualNetworkConfig(long j) {
        VirtualNetworkConfig virtualNetworkConfig;
        synchronized (this.virtualNetworkConfigMap) {
            virtualNetworkConfig = this.virtualNetworkConfigMap.get(Long.valueOf(j));
        }
        return virtualNetworkConfig;
    }

    public VirtualNetworkConfig setVirtualNetworkConfig(long j, VirtualNetworkConfig virtualNetworkConfig) {
        VirtualNetworkConfig put;
        synchronized (this.virtualNetworkConfigMap) {
            put = this.virtualNetworkConfigMap.put(Long.valueOf(j), virtualNetworkConfig);
        }
        return put;
    }

    public VirtualNetworkConfig clearVirtualNetworkConfig(long j) {
        VirtualNetworkConfig remove;
        synchronized (this.virtualNetworkConfigMap) {
            remove = this.virtualNetworkConfigMap.remove(Long.valueOf(j));
        }
        return remove;
    }

    private void logBindCount() {
        Log.i(TAG, "Bind Count: " + this.bindCount);
    }

    public IBinder onBind(Intent intent) {
        Log.d(TAG, "Bound by: " + getPackageManager().getNameForUid(Binder.getCallingUid()));
        this.bindCount++;
        logBindCount();
        return this.mBinder;
    }

    public boolean onUnbind(Intent intent) {
        Log.d(TAG, "Unbound by: " + getPackageManager().getNameForUid(Binder.getCallingUid()));
        this.bindCount--;
        logBindCount();
        return false;
    }

    /* access modifiers changed from: protected */
    public void setNextBackgroundTaskDeadline(long j) {
        synchronized (this) {
            this.nextBackgroundTaskDeadline = j;
        }
    }

    /**
     * 启动 ZT 服务，连接至给定网络或最近连接的网络
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        long networkId;
        Log.d(TAG, "onStartCommand");
        if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
            // 通知栏「断开」按钮：走与系统撤销 VPN 权限相同的清理流程
            Log.i(TAG, "Disconnect requested from notification");
            stopZeroTier();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (startId == 3) {
            Log.i(TAG, "Authorizing VPN");
            return START_NOT_STICKY;
        } else if (intent == null) {
            Log.e(TAG, "NULL intent.  Cannot start");
            return START_NOT_STICKY;
        }
        this.mStartID = startId;

        // 监听物理网络变化，切换 Wi-Fi / 蜂窝网络后重新保护套接字并恢复 UDP 线程
        registerNetworkMonitor();

        // 注册事件总线监听器
        if (!this.eventBus.isRegistered(this)) {
            this.eventBus.register(this);
        }

        // 确定待启动的网络 ID
        if (intent.hasExtra(ZT1_NETWORK_ID)) {
            // Intent 中指定了目标网络，直接使用此 ID
            networkId = intent.getLongExtra(ZT1_NETWORK_ID, 0);
        } else {
            // 默认启用最近一次启动的网络
            DatabaseUtils.readLock.lock();
            try {
                var daoSession = ((ZeroTierApplication) getApplication()).getDaoSession();
                daoSession.clear();
                var lastActivatedNetworks = daoSession.getNetworkDao().queryBuilder()
                        .where(NetworkDao.Properties.LastActivated.eq(true))
                        .list();
                if (lastActivatedNetworks == null || lastActivatedNetworks.isEmpty()) {
                    Log.e(TAG, "Couldn't find last activated connection");
                    return START_NOT_STICKY;
                } else if (lastActivatedNetworks.size() > 1) {
                    Log.e(TAG, "Multiple networks marked as last connected: " + lastActivatedNetworks.size());
                    for (Network network : lastActivatedNetworks) {
                        Log.e(TAG, "ID: " + Long.toHexString(network.getNetworkId()));
                    }
                    throw new IllegalStateException("Database is inconsistent");
                } else {
                    networkId = lastActivatedNetworks.get(0).getNetworkId();
                    Log.i(TAG, "Got Always On request for ZeroTier");
                }
            } finally {
                DatabaseUtils.readLock.unlock();
            }
        }
        if (networkId == 0) {
            Log.e(TAG, "Network ID not provided to service");
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        this.networkId = networkId;

        // 检查当前的网络环境
        var preferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean useCellularData = preferences.getBoolean(Constants.PREF_NETWORK_USE_CELLULAR_DATA, false);
        this.disableIPv6 = preferences.getBoolean(Constants.PREF_NETWORK_DISABLE_IPV6, false);
        var currentNetworkInfo = NetworkInfoUtils.getNetworkInfoCurrentConnection(this);

        if (currentNetworkInfo == NetworkInfoUtils.CurrentConnection.CONNECTION_NONE) {
            // 未连接网络
            Toast.makeText(this, R.string.toast_no_network, Toast.LENGTH_SHORT).show();
            stopSelf(this.mStartID);
            return START_NOT_STICKY;
        } else if (currentNetworkInfo == NetworkInfoUtils.CurrentConnection.CONNECTION_MOBILE &&
                !useCellularData) {
            // 使用移动网络，但未在设置中允许移动网络访问
            Toast.makeText(this, R.string.toast_mobile_data, Toast.LENGTH_LONG).show();
            stopSelf(this.mStartID);
            return START_NOT_STICKY;
        }

        // 启动 ZT 服务
        setConnectionState(STATE_CONNECTING);
        synchronized (this) {
            try {
                // 创建本地 ZT 服务 Socket，监听本地端口
                if (this.svrSocket == null) {
                    this.svrSocket = new DatagramSocket(null);
                    this.svrSocket.setReuseAddress(true);
                    this.svrSocket.setSoTimeout(1000);
                    this.svrSocket.bind(new InetSocketAddress(9994));
                }
                if (!protect(this.svrSocket)) {
                    Log.e(TAG, "Error protecting UDP socket from feedback loop.");
                }

                // 创建本地节点
                if (this.node == null) {
                    // 启动 core 之前同步行星服务器上的 planet。
                    // 未配置行星服务器地址时这里直接返回 null，不触碰任何文件与开关。
                    notifyPlanetSyncResult(PlanetSyncManager.syncBeforeStart(this));

                    this.udpCom = new UdpCom(this, this.svrSocket);
                    this.tunTapAdapter = new TunTapAdapter(this, networkId);

                    // 创建节点对象并初始化
                    var dataStore = this.dataStore;
                    this.node = new Node(System.currentTimeMillis());
                    var result = this.node.init(dataStore, dataStore, this.udpCom, this, this.tunTapAdapter, this, null);

                    if (result == ResultCode.RESULT_OK) {
                        Log.d(TAG, "ZeroTierOne Node Initialized");
                    } else {
                        Log.e(TAG, "Error starting ZT1 Node: " + result);
                        setConnectionState(STATE_IDLE);
                        return START_NOT_STICKY;
                    }
                    this.onNodeStatusRequest(null);

                    // 持久化当前节点信息
                    long address = this.node.address();
                    DatabaseUtils.writeLock.lock();
                    try {
                        var appNodeDao = ((ZeroTierApplication) getApplication())
                                .getDaoSession().getAppNodeDao();
                        var nodesList = appNodeDao.queryBuilder().build()
                                .forCurrentThread().list();
                        if (nodesList.isEmpty()) {
                            var appNode = new AppNode();
                            appNode.setNodeId(address);
                            appNode.setNodeIdStr(String.format("%10x", address));
                            appNodeDao.insert(appNode);
                        } else {
                            var appNode = nodesList.get(0);
                            appNode.setNodeId(address);
                            appNode.setNodeIdStr(String.format("%10x", address));
                            appNodeDao.save(appNode);
                        }
                    } finally {
                        DatabaseUtils.writeLock.unlock();
                    }

                    this.eventBus.post(new NodeIDEvent(address));
                    this.udpCom.setNode(this.node);
                    this.tunTapAdapter.setNode(this.node);

                    // 启动 UDP 消息处理线程
                    var thread = new Thread(this.udpCom, "UDP Communication Thread");
                    this.udpThread = thread;
                    thread.start();
                }

                // 创建并启动 VPN 服务线程
                if (this.vpnThread == null) {
                    var thread = new Thread(this, "ZeroTier Service Thread");
                    this.vpnThread = thread;
                    thread.start();
                }

                // 启动 UDP 消息处理线程
                if (!this.udpThread.isAlive()) {
                    this.udpThread.start();
                }
            } catch (Exception e) {
                Log.e(TAG, e.toString(), e);
                setConnectionState(STATE_IDLE);
                return START_NOT_STICKY;
            }
        }
        joinNetwork(networkId);
        return START_STICKY;
    }

    /**
     * 行星服务器同步失败时提示用户，并说明当前回退到哪一份 planet
     */
    private void notifyPlanetSyncResult(PlanetSyncer.Result result) {
        if (result == null || result.success) {
            return;
        }
        Log.w(TAG, "Planet sync failed: " + result.message);
        String message = getString(R.string.planet_sync_fallback,
                PlanetSyncManager.fallbackDescription(this));
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    /**
     * 监听物理网络变化。用 NOT_VPN 约束，避免把本应用自己的 tun 接口当成物理网络。
     */
    private void registerNetworkMonitor() {
        if (this.networkCallback != null) {
            return;
        }
        var manager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            return;
        }
        this.connectivityManager = manager;
        var request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build();
        var callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(android.net.Network network) {
                synchronized (physicalNetworks) {
                    physicalNetworks.add(network);
                }
                onPhysicalNetworksChanged();
            }

            @Override
            public void onLost(android.net.Network network) {
                synchronized (physicalNetworks) {
                    physicalNetworks.remove(network);
                }
                onPhysicalNetworksChanged();
            }
        };
        try {
            manager.registerNetworkCallback(request, callback);
            this.networkCallback = callback;
            Log.i(TAG, "Network monitor registered");
        } catch (Exception e) {
            Log.w(TAG, "Unable to register network callback", e);
        }
    }

    private void unregisterNetworkMonitor() {
        if (this.connectivityManager != null && this.networkCallback != null) {
            try {
                this.connectivityManager.unregisterNetworkCallback(this.networkCallback);
            } catch (Exception e) {
                Log.w(TAG, "Unable to unregister network callback", e);
            }
        }
        this.networkCallback = null;
        synchronized (this.physicalNetworks) {
            this.physicalNetworks.clear();
        }
    }

    /**
     * 物理网络集合变化后：校验移动网络策略，重新保护 UDP 套接字，并在必要时拉起收包线程。
     */
    private void onPhysicalNetworksChanged() {
        if (this.node == null || this.connectivityManager == null) {
            // 尚未连接：启动时已经检查过网络环境
            return;
        }
        boolean hasCellular = false;
        boolean hasOther = false;
        synchronized (this.physicalNetworks) {
            for (android.net.Network network : this.physicalNetworks) {
                var capabilities = this.connectivityManager.getNetworkCapabilities(network);
                if (capabilities == null) {
                    continue;
                }
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    hasCellular = true;
                } else {
                    // Wi-Fi / 以太网 / USB / 蓝牙等非蜂窝链路都算可用
                    hasOther = true;
                }
            }
        }
        if (!hasCellular && !hasOther) {
            Log.i(TAG, "Physical network lost, waiting for a new connection");
            return;
        }
        boolean useCellularData = PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean(Constants.PREF_NETWORK_USE_CELLULAR_DATA, false);
        if (hasCellular && !hasOther && !useCellularData) {
            // 与启动时的策略保持一致：只剩移动网络且未允许时断开
            Log.i(TAG, "Only cellular network left and mobile data is disabled, stopping");
            Toast.makeText(this, R.string.toast_mobile_data, Toast.LENGTH_LONG).show();
            stopZeroTier();
            stopSelf();
            return;
        }
        Log.i(TAG, "Physical network changed: cellular=" + hasCellular + ", other=" + hasOther);
        if (this.svrSocket != null && !protect(this.svrSocket)) {
            Log.w(TAG, "Error re-protecting UDP socket after network change");
        }
        ensureUdpThreadAlive();
    }

    /**
     * 网络切换会让 UDP 收包线程因 SocketException 退出，这里重新拉起，避免节点从此收不到包。
     */
    private void ensureUdpThreadAlive() {
        if (this.node == null || this.udpCom == null) {
            return;
        }
        if (this.svrSocket == null || this.svrSocket.isClosed()) {
            return;
        }
        if (this.udpThread != null && this.udpThread.isAlive()) {
            return;
        }
        Log.i(TAG, "UDP communication thread is not running, restarting");
        var thread = new Thread(this.udpCom, "UDP Communication Thread");
        this.udpThread = thread;
        thread.start();
    }

    /**
     * 以前台服务方式运行，避免息屏或切到后台后被系统回收。
     * Android 12+ 在后台启动前台服务可能被拒，此时退回普通通知。
     */
    private void startForegroundCompat(Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(ZT_NOTIFICATION_TAG, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(ZT_NOTIFICATION_TAG, notification);
            }
        } catch (Exception e) {
            Log.w(TAG, "startForeground failed, fallback to normal notification", e);
            if (this.notificationManager != null) {
                this.notificationManager.notify(ZT_NOTIFICATION_TAG, notification);
            }
        }
    }

    /**
     * 更新连接状态并通知快捷设置磁贴刷新。
     */
    private void setConnectionState(int state) {
        if (connectionState == state) {
            return;
        }
        connectionState = state;
        Log.d(TAG, "Connection state changed to " + state);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ZeroTierTileService.requestTileRefresh(getApplicationContext());
        }
    }

    public void stopZeroTier() {
        setConnectionState(STATE_IDLE);
        if (this.svrSocket != null) {
            this.svrSocket.close();
            this.svrSocket = null;
        }
        if (this.udpThread != null && this.udpThread.isAlive()) {
            this.udpThread.interrupt();
            try {
                this.udpThread.join();
            } catch (InterruptedException ignored) {
            }
            this.udpThread = null;
        }
        if (this.tunTapAdapter != null && this.tunTapAdapter.isRunning()) {
            this.tunTapAdapter.interrupt();
            try {
                this.tunTapAdapter.join();
            } catch (InterruptedException ignored) {
            }
            this.tunTapAdapter = null;
        }
        if (this.vpnThread != null && this.vpnThread.isAlive()) {
            this.vpnThread.interrupt();
            try {
                this.vpnThread.join();
            } catch (InterruptedException ignored) {
            }
            this.vpnThread = null;
        }
        if (this.v4MulticastScanner != null) {
            this.v4MulticastScanner.interrupt();
            try {
                this.v4MulticastScanner.join();
            } catch (InterruptedException ignored) {
            }
            this.v4MulticastScanner = null;
        }
        if (this.v6MulticastScanner != null) {
            this.v6MulticastScanner.interrupt();
            try {
                this.v6MulticastScanner.join();
            } catch (InterruptedException ignored) {
            }
            this.v6MulticastScanner = null;
        }
        if (this.vpnSocket != null) {
            try {
                this.vpnSocket.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing VPN socket: " + e, e);
            }
            this.vpnSocket = null;
        }
        if (this.node != null) {
            this.eventBus.post(new NodeDestroyedEvent());
            this.node.close();
            this.node = null;
        }
        if (this.eventBus.isRegistered(this)) {
            this.eventBus.unregister(this);
        }
        stopForeground(true);
        if (this.notificationManager != null) {
            this.notificationManager.cancel(ZT_NOTIFICATION_TAG);
        }
        if (!stopSelfResult(this.mStartID)) {
            Log.e(TAG, "stopSelfResult() failed!");
        }
    }

    public void onDestroy() {
        try {
            unregisterNetworkMonitor();
            stopZeroTier();
            if (this.vpnSocket != null) {
                try {
                    this.vpnSocket.close();
                } catch (Exception e) {
                    Log.e(TAG, "Error closing VPN socket: " + e, e);
                }
                this.vpnSocket = null;
            }
            stopSelf(this.mStartID);
            if (this.eventBus.isRegistered(this)) {
                this.eventBus.unregister(this);
            }
        } catch (Exception e) {
            Log.e(TAG, e.toString(), e);
        } finally {
            super.onDestroy();
        }
    }

    public void onRevoke() {
        stopZeroTier();
        if (this.vpnSocket != null) {
            try {
                this.vpnSocket.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing VPN socket: " + e, e);
            }
            this.vpnSocket = null;
        }
        stopSelf(this.mStartID);
        if (this.eventBus.isRegistered(this)) {
            this.eventBus.unregister(this);
        }
        super.onRevoke();
    }

    public void run() {
        Log.d(TAG, "ZeroTierOne Service Started");
        Log.d(TAG, "This Node Address: " + com.zerotier.sdk.util.StringUtils.addressToString(this.node.address()));
        while (!Thread.interrupted()) {
            try {
                // 在后台任务截止期前循环进行后台任务
                var taskDeadline = this.nextBackgroundTaskDeadline;
                long currentTime = System.currentTimeMillis();
                int cmp = Long.compare(taskDeadline, currentTime);
                if (cmp <= 0) {
                    long[] newDeadline = {0};
                    var taskResult = this.node.processBackgroundTasks(currentTime, newDeadline);
                    synchronized (this) {
                        this.nextBackgroundTaskDeadline = newDeadline[0];
                    }
                    if (taskResult != ResultCode.RESULT_OK) {
                        Log.e(TAG, "Error on processBackgroundTasks: " + taskResult.toString());
                        shutdown();
                    }
                }
                Thread.sleep(cmp > 0 ? taskDeadline - currentTime : 100);
            } catch (InterruptedException ignored) {
                break;
            } catch (Exception e) {
                Log.e(TAG, e.toString(), e);
            }
        }
        Log.d(TAG, "ZeroTierOne Service Ended");
    }

    @Subscribe(threadMode = ThreadMode.POSTING)
    public void onStopEvent(StopEvent stopEvent) {
        stopZeroTier();
    }

    @Subscribe(threadMode = ThreadMode.POSTING)
    public void onManualDisconnect(ManualDisconnectEvent manualDisconnectEvent) {
        stopZeroTier();
    }

    @Subscribe(threadMode = ThreadMode.POSTING)
    public void onIsServiceRunningRequest(IsServiceRunningRequestEvent event) {
        this.eventBus.post(new IsServiceRunningReplyEvent(true));
    }

    /**
     * 加入 ZT 网络
     */
    public void joinNetwork(long networkId) {
        if (this.node == null) {
            Log.e(TAG, "Can't join network if ZeroTier isn't running");
            return;
        }
        // 连接到新网络
        var result = this.node.join(networkId);
        if (result != ResultCode.RESULT_OK) {
            this.eventBus.post(new ErrorEvent(result));
            return;
        }
        // 连接后事件
        this.eventBus.post(new AfterJoinNetworkEvent());
    }

    /**
     * 离开 ZT 网络
     */
    public void leaveNetwork(long networkId) {
        if (this.node == null) {
            Log.e(TAG, "Can't leave network if ZeroTier isn't running");
            return;
        }
        var result = this.node.leave(networkId);
        if (result != ResultCode.RESULT_OK) {
            this.eventBus.post(new ErrorEvent(result));
            return;
        }
        var networkConfigs = this.node.networkConfigs();
        if (networkConfigs != null && networkConfigs.length != 0) {
            return;
        }
        stopZeroTier();
        if (this.vpnSocket != null) {
            try {
                this.vpnSocket.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing VPN socket", e);
            }
            this.vpnSocket = null;
        }
        stopSelf(this.mStartID);
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onNetworkListRequest(NetworkListRequestEvent requestNetworkListEvent) {
        VirtualNetworkConfig[] networks;
        Node node2 = this.node;
        if (node2 != null && (networks = node2.networkConfigs()) != null && networks.length > 0) {
            this.eventBus.post(new NetworkListReplyEvent(networks));
        }
    }

    /**
     * 请求节点状态事件回调
     *
     * @param event 事件
     */
    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onNodeStatusRequest(NodeStatusRequestEvent event) {
        // 返回节点状态
        if (this.node != null) {
            this.eventBus.post(new NodeStatusEvent(this.node.status(), this.node.getVersion()));
        }
    }

    /**
     * 请求 Peer 信息事件回调
     */
    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onRequestPeerInfo(PeerInfoRequestEvent event) {
        if (this.node == null) {
            this.eventBus.post(new PeerInfoReplyEvent(null));
            return;
        }
        this.eventBus.post(new PeerInfoReplyEvent(this.node.peers()));
    }

    /**
     * 请求网络配置事件回调
     */
    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onVirtualNetworkConfigRequest(VirtualNetworkConfigRequestEvent event) {
        if (this.node == null) {
            this.eventBus.post(new VirtualNetworkConfigReplyEvent(null));
            return;
        }
        var config = this.node.networkConfig(event.getNetworkId());
        this.eventBus.post(new VirtualNetworkConfigReplyEvent(config));
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onNetworkReconfigure(NetworkReconfigureEvent event) {
        boolean isChanged = event.isChanged();
        var network = event.getNetwork();
        var networkConfig = event.getVirtualNetworkConfig();
        boolean configUpdated = isChanged && updateTunnelConfig(network);
        boolean networkIsOk = networkConfig.getStatus() == VirtualNetworkStatus.NETWORK_STATUS_OK;

        if (configUpdated || !networkIsOk) {
            this.eventBus.post(new VirtualNetworkConfigChangedEvent(networkConfig));
        }
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onNetworkConfigChangedByUser(NetworkConfigChangedByUserEvent event) {
        Network network = event.getNetwork();
        if (network.getNetworkId() != this.networkId) {
            return;
        }
        updateTunnelConfig(network);
    }

    /**
     * Zerotier 事件回调
     *
     * @param event {@link Event} enum
     */
    @Override
    public void onEvent(Event event) {
        Log.d(TAG, "Event: " + event.toString());
        // 更新节点状态
        if (this.node.isInited()) {
            this.eventBus.post(new NodeStatusEvent(this.node.status(), this.node.getVersion()));
        }
    }

    @Override // com.zerotier.sdk.EventListener
    public void onTrace(String str) {
        Log.d(TAG, "Trace: " + str);
    }

    /**
     * 当 ZT 网络配置发生更新
     */
    @Override
    public int onNetworkConfigurationUpdated(long networkId, VirtualNetworkConfigOperation op, VirtualNetworkConfig config) {
        Log.i(TAG, "Virtual Network Config Operation: " + op);
        DatabaseUtils.writeLock.lock();
        try {
            // 查找网络 ID 对应的配置
            var networkDao = ((ZeroTierApplication) getApplication())
                    .getDaoSession()
                    .getNetworkDao();
            var matchedNetwork = networkDao.queryBuilder()
                    .where(NetworkDao.Properties.NetworkId.eq(networkId))
                    .list();
            if (matchedNetwork.size() != 1) {
                throw new IllegalStateException("Database is inconsistent");
            }
            var network = matchedNetwork.get(0);
            // 根据当前网络状态确定更改配置的行为
            switch (op) {
                case VIRTUAL_NETWORK_CONFIG_OPERATION_UP:
                    Log.d(TAG, "Network Type: " + config.getType() + " Network Status: " + config.getStatus() + " Network Name: " + config.getName() + " ");
                    // 将网络配置的更新交给第一次 Update
                    break;
                case VIRTUAL_NETWORK_CONFIG_OPERATION_CONFIG_UPDATE:
                    Log.i(TAG, "Network Config Update!");
                    boolean isChanged = setVirtualNetworkConfigAndUpdateDatabase(network, config);
                    this.eventBus.post(new NetworkReconfigureEvent(isChanged, network, config));
                    break;
                case VIRTUAL_NETWORK_CONFIG_OPERATION_DOWN:
                case VIRTUAL_NETWORK_CONFIG_OPERATION_DESTROY:
                    Log.d(TAG, "Network Down!");
                    clearVirtualNetworkConfig(networkId);
                    break;
            }
            return 0;
        } finally {
            DatabaseUtils.writeLock.unlock();
        }
    }

    private boolean setVirtualNetworkConfigAndUpdateDatabase(Network network, VirtualNetworkConfig virtualNetworkConfig) {
        if ((DatabaseUtils.writeLock instanceof ReentrantReadWriteLock.WriteLock) && !((ReentrantReadWriteLock.WriteLock) DatabaseUtils.writeLock).isHeldByCurrentThread()) {
            throw new IllegalStateException("DatabaseUtils.writeLock not held");
        }
        VirtualNetworkConfig virtualNetworkConfig2 = getVirtualNetworkConfig(network.getNetworkId());
        setVirtualNetworkConfig(network.getNetworkId(), virtualNetworkConfig);
        var networkName = virtualNetworkConfig.getName();
        if (networkName != null && !networkName.isEmpty()) {
            network.setNetworkName(networkName);
        }
        network.update();
        return !virtualNetworkConfig.equals(virtualNetworkConfig2);
    }

    protected void shutdown() {
        stopZeroTier();
        if (this.vpnSocket != null) {
            try {
                this.vpnSocket.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing VPN socket", e);
            }
            this.vpnSocket = null;
        }
        stopSelf(this.mStartID);
    }

    private boolean updateTunnelConfig(Network network) {
        long networkId = network.getNetworkId();
        var networkConfig = network.getNetworkConfig();
        var virtualNetworkConfig = getVirtualNetworkConfig(networkId);
        if (virtualNetworkConfig == null) {
            return false;
        }

        // 重启 TUN TAP
        if (this.tunTapAdapter.isRunning()) {
            this.tunTapAdapter.interrupt();
            try {
                this.tunTapAdapter.join();
            } catch (InterruptedException ignored) {
            }
        }
        this.tunTapAdapter.clearRouteMap();

        // 重启 VPN Socket
        if (this.vpnSocket != null) {
            try {
                this.vpnSocket.close();
                this.in.close();
                this.out.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing VPN socket: " + e, e);
            }
            this.vpnSocket = null;
            this.in = null;
            this.out = null;
        }

        // 配置 VPN
        Log.i(TAG, "Configuring VpnService.Builder");
        var builder = new VpnService.Builder();
        var assignedAddresses = virtualNetworkConfig.getAssignedAddresses();
        Log.i(TAG, "address length: " + assignedAddresses.length);
        boolean isRouteViaZeroTier = networkConfig.getRouteViaZeroTier();

        // 遍历 ZT 网络中当前设备的 IP 地址，组播配置
        for (var vpnAddress : assignedAddresses) {
            Log.d(TAG, "Adding VPN Address: " + vpnAddress.getAddress()
                    + " Mac: " + com.zerotier.sdk.util.StringUtils.macAddressToString(virtualNetworkConfig.getMac()));
            byte[] rawAddress = vpnAddress.getAddress().getAddress();

            if (!this.disableIPv6 || !(vpnAddress.getAddress() instanceof Inet6Address)) {
                var address = vpnAddress.getAddress();
                var port = vpnAddress.getPort();
                var route = InetAddressUtils.addressToRoute(address, port);
                if (route == null) {
                    Log.e(TAG, "NULL route calculated!");
                    continue;
                }

                // 计算 VPN 地址相关的组播 MAC 与 ADI
                long multicastGroup;
                long multicastAdi;
                if (rawAddress.length == 4) {
                    // IPv4
                    multicastGroup = InetAddressUtils.BROADCAST_MAC_ADDRESS;
                    multicastAdi = ByteBuffer.wrap(rawAddress).getInt();
                } else {
                    // IPv6
                    multicastGroup = ByteBuffer.wrap(new byte[]{
                                    0, 0, 0x33, 0x33, (byte) 0xFF, rawAddress[13], rawAddress[14], rawAddress[15]})
                            .getLong();
                    multicastAdi = 0;
                }

                // 订阅组播并添加至 TUN TAP 路由
                var result = this.node.multicastSubscribe(networkId, multicastGroup, multicastAdi);
                if (result != ResultCode.RESULT_OK) {
                    Log.e(TAG, "Error joining multicast group");
                } else {
                    Log.d(TAG, "Joined multicast group");
                }
                builder.addAddress(address, port);
                builder.addRoute(route, port);
                this.tunTapAdapter.addRouteAndNetwork(new Route(route, port), networkId);
            }
        }

        // 遍历网络的路由规则，将网络负责路由的地址路由至 VPN
        try {
            var v4Loopback = InetAddress.getByName("0.0.0.0");
            var v6Loopback = InetAddress.getByName("::");
            if (virtualNetworkConfig.getRoutes().length > 0) {
                for (var routeConfig : virtualNetworkConfig.getRoutes()) {
                    var target = routeConfig.getTarget();
                    var via = routeConfig.getVia();
                    var targetAddress = target.getAddress();
                    var targetPort = target.getPort();
                    var viaAddress = InetAddressUtils.addressToRoute(targetAddress, targetPort);

                    boolean isIPv6Route = (targetAddress instanceof Inet6Address) || (viaAddress instanceof Inet6Address);
                    boolean isDisabledV6Route = this.disableIPv6 && isIPv6Route;
                    boolean shouldRouteToZerotier = viaAddress != null && (
                            isRouteViaZeroTier
                                    || (!viaAddress.equals(v4Loopback) && !viaAddress.equals(v6Loopback))
                    );
                    if (!isDisabledV6Route && shouldRouteToZerotier) {
                        builder.addRoute(viaAddress, targetPort);
                        Route route = new Route(viaAddress, targetPort);
                        if (via != null) {
                            route.setGateway(via.getAddress());
                        }
                        this.tunTapAdapter.addRouteAndNetwork(route, networkId);
                    }
                }
            }
            builder.addRoute(InetAddress.getByName("224.0.0.0"), 4);
        } catch (Exception e) {
            this.eventBus.post(new VPNErrorEvent(e.getLocalizedMessage()));
            setConnectionState(STATE_IDLE);
            return false;
        }

        if (Build.VERSION.SDK_INT >= 29) {
            builder.setMetered(false);
        }
        addDNSServers(builder, network);

        // 配置 MTU
        int mtu = virtualNetworkConfig.getMtu();
        Log.i(TAG, "MTU from Network Config: " + mtu);
        if (mtu == 0) {
            mtu = 2800;
        }
        Log.i(TAG, "MTU Set: " + mtu);
        builder.setMtu(mtu);

        builder.setSession(Constants.VPN_SESSION_NAME);

        // 设置部分 APP 不经过 VPN
        if (!isRouteViaZeroTier && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            for (var app : DISALLOWED_APPS) {
                try {
                    builder.addDisallowedApplication(app);
                } catch (Exception e3) {
                    Log.e(TAG, "Cannot disallow app", e3);
                }
            }
        }

        // 建立 VPN 连接
        this.vpnSocket = builder.establish();
        if (this.vpnSocket == null) {
            this.eventBus.post(new VPNErrorEvent(getString(R.string.toast_vpn_application_not_prepared)));
            setConnectionState(STATE_IDLE);
            return false;
        }
        this.in = new FileInputStream(this.vpnSocket.getFileDescriptor());
        this.out = new FileOutputStream(this.vpnSocket.getFileDescriptor());
        this.tunTapAdapter.setVpnSocket(this.vpnSocket);
        this.tunTapAdapter.setFileStreams(this.in, this.out);
        this.tunTapAdapter.startThreads();

        // 状态栏提示
        if (this.notificationManager == null) {
            this.notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            String channelName = getString(R.string.channel_name);
            String description = getString(R.string.channel_description);
            var channel = new NotificationChannel(
                    Constants.CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription(description);
            this.notificationManager.createNotificationChannel(channel);
        }
        int pendingIntentFlag = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) {
            pendingIntentFlag |= PendingIntent.FLAG_IMMUTABLE;
        }
        var pendingIntent =
                PendingIntent.getActivity(this, 0,
                        new Intent(this, NetworkListActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                                        | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        , pendingIntentFlag);
        var notification = new NotificationCompat.Builder(this, Constants.CHANNEL_ID)
                .setPriority(1)
                .setOngoing(true)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.notification_title_connected))
                .setContentText(getString(R.string.notification_text_connected, network.getNetworkIdStr()))
                .setColor(ContextCompat.getColor(getApplicationContext(), R.color.zerotier_orange))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        getString(R.string.notification_action_disconnect),
                        PendingIntent.getService(this, 1,
                                new Intent(this, ZeroTierOneService.class).setAction(ACTION_DISCONNECT),
                                pendingIntentFlag))
                .setContentIntent(pendingIntent).build();
        // 以前台服务运行，避免息屏或切到后台后被系统回收
        startForegroundCompat(notification);
        setConnectionState(STATE_CONNECTED);
        Log.i(TAG, "ZeroTier One Connected");

        // 旧版本 Android 多播处理
        if (Build.VERSION.SDK_INT < 29) {
            if (this.v4MulticastScanner != null && !this.v4MulticastScanner.isAlive()) {
                this.v4MulticastScanner.start();
            }
            if (!this.disableIPv6 && this.v6MulticastScanner != null && !this.v6MulticastScanner.isAlive()) {
                this.v6MulticastScanner.start();
            }
        }
        return true;
    }

    private void addDNSServers(VpnService.Builder builder, Network network) {
        var networkConfig = network.getNetworkConfig();
        var virtualNetworkConfig = getVirtualNetworkConfig(network.getNetworkId());
        var dnsMode = DNSMode.fromInt(networkConfig.getDnsMode());

        switch (dnsMode) {
            case NETWORK_DNS:
                if (virtualNetworkConfig.getDns() == null) {
                    return;
                }
                builder.addSearchDomain(virtualNetworkConfig.getDns().getDomain());
                for (var inetSocketAddress : virtualNetworkConfig.getDns().getServers()) {
                    InetAddress address = inetSocketAddress.getAddress();
                    if (address instanceof Inet4Address) {
                        builder.addDnsServer(address);
                    } else if ((address instanceof Inet6Address) && !this.disableIPv6) {
                        builder.addDnsServer(address);
                    }
                }
                break;
            case CUSTOM_DNS:
                for (var dnsServer : networkConfig.getDnsServers()) {
                    try {
                        InetAddress byName = InetAddress.getByName(dnsServer.getNameserver());
                        if (byName instanceof Inet4Address) {
                            builder.addDnsServer(byName);
                        } else if ((byName instanceof Inet6Address) && !this.disableIPv6) {
                            builder.addDnsServer(byName);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Exception parsing DNS server: " + e, e);
                    }
                }
                break;
            default:
                break;
        }
    }

    /**
     * 入轨事件
     */
    @Subscribe
    public void onOrbitMoonEvent(OrbitMoonEvent event) {
        if (this.node == null) {
            Log.e(TAG, "Can't orbit network if ZeroTier isn't running");
            return;
        }
        // 入轨
        for (MoonOrbit moonOrbit : event.getMoonOrbits()) {
            Log.i(TAG, "Orbiting moon: " + Long.toHexString(moonOrbit.getMoonWorldId()));
            this.orbitNetwork(moonOrbit.getMoonWorldId(), moonOrbit.getMoonSeed());
        }
    }

    /**
     * 当前网络入轨 Moon
     *
     * @param moonWorldId Moon 节点地址
     * @param moonSeed    Moon 种子节点地址
     */
    public void orbitNetwork(Long moonWorldId, Long moonSeed) {
        if (this.node == null) {
            Log.e(TAG, "Can't orbit network if ZeroTier isn't running");
            return;
        }
        // 入轨
        ResultCode result = this.node.orbit(moonWorldId, moonSeed);
        if (result != ResultCode.RESULT_OK) {
            Log.e(TAG, "Failed to orbit " + Long.toHexString(moonWorldId));
            this.eventBus.post(new ErrorEvent(result));
        }
    }

    public class ZeroTierBinder extends Binder {
        public ZeroTierBinder() {
        }

        public ZeroTierOneService getService() {
            return ZeroTierOneService.this;
        }
    }
}
