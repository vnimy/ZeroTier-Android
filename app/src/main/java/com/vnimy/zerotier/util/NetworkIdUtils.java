package com.vnimy.zerotier.util;

import java.math.BigInteger;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网络号处理工具类
 */
public class NetworkIdUtils {

    /**
     * 独立的 16 位十六进制网络 ID。前后不能是十六进制字符，
     * 避免从更长的哈希/令牌中截取出一段。
     */
    private static final Pattern NETWORK_ID_PATTERN =
            Pattern.compile("(?i)(?<![0-9a-f])([0-9a-f]{16})(?![0-9a-f])");

    public static long hexStringToLong(String str) {
        return new BigInteger(str, 16).longValue();
    }

    /**
     * 判断字符串是否是合法的 16 位十六进制网络 ID。
     */
    public static boolean isValidNetworkId(String networkId) {
        return networkId != null && networkId.matches("(?i)[0-9a-f]{16}");
    }

    /**
     * 从二维码内容中提取网络 ID。
     * <p>
     * 支持纯网络 ID、带空格/冒号/短横线的网络 ID、0x 前缀，
     * 以及包含网络 ID 的 URL（如 https://my.zerotier.com/network/&lt;id&gt;）、
     * zerotier://&lt;id&gt; 和 JSON 文本。
     *
     * @return 大写网络 ID，未找到时返回 null
     */
    public static String extractNetworkId(String content) {
        if (content == null) {
            return null;
        }
        String text = content.trim();
        if (text.isEmpty() || text.length() > 4096) {
            return null;
        }

        // 纯网络 ID，允许常见分隔符和 0x 前缀
        String compact = text.replaceAll("[\\s:._-]", "");
        if (compact.regionMatches(true, 0, "0x", 0, 2)) {
            compact = compact.substring(2);
        }
        if (isValidNetworkId(compact)) {
            return compact.toUpperCase(Locale.ROOT);
        }

        // URL、URI、JSON 等文本中的网络 ID
        Matcher matcher = NETWORK_ID_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).toUpperCase(Locale.ROOT);
        }
        return null;
    }
}
