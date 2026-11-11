package net.kaaass.zerotierfix.util;

/**
 * 维护程序中公共的常量
 *
 * @author kaaass
 */
public class Constants {

    public static final String PREF_NETWORK_USE_CELLULAR_DATA = "network_use_cellular_data";

    public static final String PREF_PLANET_USE_CUSTOM = "planet_use_custom";

    public static final String PREF_SET_PLANET_FILE = "set_planet_file";

    public static final String PREF_NETWORK_DISABLE_IPV6 = "network_disable_ipv6";

    public static final String PREF_DISABLE_NO_NOTIFICATION_ALERT = "disable_no_notification_alert";

    public static final String FILE_CUSTOM_PLANET = "planet.custom";

    public static final String FILE_PLANET_TEMP = "planet.custom.tmp";

    public static final String FILE_PLANET_BACKUP = "planet.custom.bak";

    /**
     * 行星服务器（Planet 服务器）下载地址，为空则完全维持原有行为
     */
    public static final String PREF_PLANET_SERVER_URL = "planet_server_url";

    /**
     * 行星服务器下载 token（可选）
     */
    public static final String PREF_PLANET_SERVER_TOKEN = "planet_server_token";

    /**
     * 每次启动都重新下载 planet（可选，默认关闭）
     */
    public static final String PREF_PLANET_SERVER_AUTO_UPDATE = "planet_server_auto_update";

    /**
     * 可选的 planet 文件 sha256 校验值
     */
    public static final String PREF_PLANET_SERVER_SHA256 = "planet_server_sha256";
    public static final String PREF_PLANET_SCAN_QR = "planet_scan_qr";

    /**
     * 上次成功下载 planet 的时间戳（毫秒）
     */
    public static final String PREF_PLANET_SERVER_LAST_SYNC = "planet_server_last_sync";

    public static final String FILE_TEMP = "temp";

    public static final String FILE_PLANET = "planet";

    public static final String CHANNEL_ID = "ZerotierFix";

    public static final String VPN_SESSION_NAME = "ZerotierFix";
}
