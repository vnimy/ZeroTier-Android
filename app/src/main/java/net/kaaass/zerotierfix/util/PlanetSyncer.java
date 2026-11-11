package net.kaaass.zerotierfix.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Planet 文件下载、校验与落盘。
 *
 * <p>本类刻意不依赖任何 Android API：App 内由 {@code PlanetSyncManager} 调用，
 * 同时可以在桌面 JVM 上直接运行以做端到端验证。</p>
 *
 * <p>落盘位置由调用方给出，App 传的是 {@code getFilesDir()/planet.custom}，
 * 也就是内嵌 core（ZT 1.12.2）读取 planet 状态对象时使用的路径。</p>
 */
public final class PlanetSyncer {

    /** 下载连接超时 */
    public static final int CONNECT_TIMEOUT_MS = 4000;

    /** 下载读取超时 */
    public static final int READ_TIMEOUT_MS = 6000;

    /** 单次同步的最大尝试次数（含首次） */
    public static final int MAX_ATTEMPTS = 2;

    /**
     * 一次同步的总时间预算。同步发生在 onStartCommand（主线程）里、core 启动之前，
     * 必须远小于 Android 对 Service 的 ANR 阈值（20 秒），因此这里用总预算兜底：
     * 预算耗尽后不再重试，直接走失败回退。
     */
    public static final int TOTAL_BUDGET_MS = 8000;

    /**
     * planet 文件长度下限。World 序列化格式（node/World.hpp serialize）固定头为
     * 1 字节类型 + 8 字节 id + 8 字节时间戳 + 64 字节公钥(ZT_C25519_PUBLIC_KEY_LEN)
     * + 96 字节签名(ZT_C25519_SIGNATURE_LEN) + 1 字节根节点数 = 178 字节，
     * 之后才是根节点身份与端点。官方内置 planet 实测 570 字节（见 test/real-planet-official.bin），
     * 因此 178 是任何合法 planet 都不可能低于的长度，用于拒绝空文件/截断文件。
     */
    public static final int MIN_PLANET_SIZE = 178;

    /** planet 文件长度上限，防止异常源把存储写满 */
    public static final int MAX_PLANET_SIZE = 64 * 1024;

    /** World 类型：0x01 = planet（World.hpp 中 TYPE_PLANET） */
    public static final int PLANET_TYPE = 0x01;

    private static final int MAX_REDIRECTS = 3;

    private PlanetSyncer() {
    }

    /**
     * 同步结果
     */
    public static final class Result {

        public final boolean success;
        public final boolean updated;
        public final String message;
        public final String sha256;
        public final long size;

        private Result(boolean success, boolean updated, String message, String sha256, long size) {
            this.success = success;
            this.updated = updated;
            this.message = message;
            this.sha256 = sha256;
            this.size = size;
        }

        static Result ok(boolean updated, String sha256, long size) {
            return new Result(true, updated, "ok", sha256, size);
        }

        static Result fail(String message) {
            return new Result(false, false, message, null, 0);
        }

        @Override
        public String toString() {
            return "Result{success=" + this.success + ", updated=" + this.updated
                    + ", message=" + this.message + ", size=" + this.size + "}";
        }
    }

    /**
     * 补全用户输入：缺少协议头时按 http 处理
     */
    public static String normalizeUrl(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.indexOf("://") < 0) {
            trimmed = "http://" + trimmed;
        }
        return trimmed;
    }

    /**
     * 下载 planet 到 dest，成功时同步备份旧文件。temp 必须与 dest 位于同一文件系统。
     */
    public static Result downloadAndInstall(File dest, File temp, File backup,
                                            String url, String token, String expectSha256) {
        String normalized = normalizeUrl(url);
        if (normalized.isEmpty()) {
            return Result.fail("空的行星服务器地址");
        }
        deleteQuietly(temp);

        String lastError = "未知错误";
        long deadline = System.currentTimeMillis() + TOTAL_BUDGET_MS;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining < 1000) {
                lastError = "总时间预算 " + TOTAL_BUDGET_MS + "ms 已用尽";
                break;
            }
            try {
                long size = download(normalized, token, temp, remaining);
                String sha256 = sha256Hex(temp);
                if (!matchesExpectedSha256(sha256, expectSha256)) {
                    deleteQuietly(temp);
                    return Result.fail("sha256 不匹配: " + sha256);
                }
                String invalid = validatePlanetFile(temp);
                if (invalid != null) {
                    deleteQuietly(temp);
                    return Result.fail(invalid);
                }
                // 保留上一份可用 planet
                if (dest.exists()) {
                    deleteQuietly(backup);
                    if (!copyFile(dest, backup)) {
                        // 备份失败不阻断更新，仅记录
                        lastError = "备份旧 planet 失败";
                    }
                }
                if (!moveFile(temp, dest)) {
                    deleteQuietly(temp);
                    return Result.fail("无法写入 planet 文件: " + dest);
                }
                return Result.ok(true, sha256, size);
            } catch (IOException e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                deleteQuietly(temp);
            }
        }
        return Result.fail("下载失败（已重试 " + MAX_ATTEMPTS + " 次）: " + lastError);
    }

    /**
     * 校验 planet 文件，返回 null 表示合法，否则返回原因
     */
    public static String validatePlanetFile(File file) {
        if (file == null || !file.isFile()) {
            return "planet 文件不存在";
        }
        long length = file.length();
        if (length < MIN_PLANET_SIZE) {
            return "planet 文件过短: " + length + " 字节";
        }
        if (length > MAX_PLANET_SIZE) {
            return "planet 文件过大: " + length + " 字节";
        }
        try (FileInputStream in = new FileInputStream(file)) {
            int type = in.read();
            if (type != PLANET_TYPE) {
                return String.format(Locale.US, "planet 文件类型错误: 0x%02x", type & 0xff);
            }
        } catch (IOException e) {
            return "无法读取 planet 文件: " + e.getMessage();
        }
        return null;
    }

    /**
     * 计算文件 sha256（小写十六进制）
     */
    public static String sha256Hex(File file) {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) > 0) {
                digest.update(buf, 0, read);
            }
            return toHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            return "";
        }
    }

    private static boolean matchesExpectedSha256(String actual, String expect) {
        if (expect == null) {
            return true;
        }
        String normalized = expect.trim().toLowerCase(Locale.US)
                .replace(":", "").replace(" ", "");
        if (normalized.isEmpty()) {
            return true;
        }
        return normalized.equals(actual);
    }

    private static long download(String url, String token, File target, long budgetMs)
            throws IOException {
        int connectTimeout = (int) Math.min(CONNECT_TIMEOUT_MS, Math.max(500, budgetMs));
        int readTimeout = (int) Math.min(READ_TIMEOUT_MS, Math.max(500, budgetMs));
        URL current = new URL(url);
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setConnectTimeout(connectTimeout);
            connection.setReadTimeout(readTimeout);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/octet-stream,*/*");
            connection.setRequestProperty("User-Agent", "ZerotierFix-planet-sync");
            if (token != null && !token.trim().isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + token.trim());
            }
            try {
                int code = connection.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.isEmpty()) {
                        throw new IOException("重定向缺少 Location");
                    }
                    current = new URL(current, location);
                    continue;
                }
                if (code != 200) {
                    throw new IOException("HTTP " + code);
                }
                return copyLimited(connection.getInputStream(), target);
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("重定向次数过多");
    }

    /**
     * 把响应体写入目标文件，同时施加上限保护
     */
    private static long copyLimited(InputStream in, File target) throws IOException {
        long total = 0;
        byte[] buf = new byte[8192];
        try (OutputStream out = new FileOutputStream(target)) {
            int read;
            while ((read = in.read(buf)) > 0) {
                total += read;
                if (total > MAX_PLANET_SIZE) {
                    throw new IOException("响应超过 " + MAX_PLANET_SIZE + " 字节");
                }
                out.write(buf, 0, read);
            }
            out.flush();
        } finally {
            closeQuietly(in);
        }
        return total;
    }

    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format(Locale.US, "%02x", b));
        }
        return sb.toString();
    }

    private static boolean copyFile(File from, File to) {
        try (InputStream in = new FileInputStream(from);
             OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) > 0) {
                out.write(buf, 0, read);
            }
            out.flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean moveFile(File from, File to) {
        deleteQuietly(to);
        if (from.renameTo(to)) {
            return true;
        }
        // 跨文件系统或 Windows 上的重命名失败时退化为复制
        if (!copyFile(from, to)) {
            return false;
        }
        deleteQuietly(from);
        return true;
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            file.deleteOnExit();
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
        }
    }
}
