package com.mirearpet.offline;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dedicated GitHub OTA updater for Offline Rear Screen Pet (nzz0991999-ai/xiaomi-rear-pet-offline).
 * Supports both direct GitHub Releases and automatic China-friendly mirror fallbacks for fans without VPN.
 */
public final class AppUpdater {
    private static final String GITHUB_LATEST_RELEASE_API =
            "https://api.github.com/repos/nzz0991999-ai/xiaomi-rear-pet-offline/releases/latest";
    private static final String[] VERSION_JSON_URLS = new String[]{
            "https://raw.githubusercontent.com/nzz0991999-ai/xiaomi-rear-pet-offline/main/version.json",
            "https://ghfast.top/https://raw.githubusercontent.com/nzz0991999-ai/xiaomi-rear-pet-offline/main/version.json",
            "https://gh-proxy.com/https://raw.githubusercontent.com/nzz0991999-ai/xiaomi-rear-pet-offline/main/version.json"
    };
    private static final String[] DOWNLOAD_MIRROR_PREFIXES = new String[]{
            "",
            "https://ghfast.top/",
            "https://gh-proxy.com/"
    };

    public static final class UpdateInfo {
        public final boolean hasUpdate;
        public final long localVersionCode;
        public final String localVersionName;
        public final long remoteVersionCode;
        public final String remoteVersionName;
        public final String apkUrl;
        public final String notes;
        public final String error;

        public UpdateInfo(boolean hasUpdate, long localVersionCode, String localVersionName,
                          long remoteVersionCode, String remoteVersionName, String apkUrl,
                          String notes, String error) {
            this.hasUpdate = hasUpdate;
            this.localVersionCode = localVersionCode;
            this.localVersionName = localVersionName;
            this.remoteVersionCode = remoteVersionCode;
            this.remoteVersionName = remoteVersionName;
            this.apkUrl = apkUrl;
            this.notes = notes;
            this.error = error;
        }
    }

    public interface CheckCallback {
        void onResult(UpdateInfo info);
    }

    public interface InstallCallback {
        void onProgress(String statusText);
        void onDone(boolean ok, String detail);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

    private AppUpdater() {}

    public static long getLocalVersionCode(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return pi.getLongVersionCode();
            }
            //noinspection deprecation
            return pi.versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    public static String getLocalVersionName(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName != null ? pi.versionName : "1.0.0";
        } catch (Throwable t) {
            return "1.0.0";
        }
    }

    private static int compareSemver(String a, String b) {
        if (a == null) a = "0";
        if (b == null) b = "0";
        String ca = a.trim().replaceFirst("^[vV]", "");
        String cb = b.trim().replaceFirst("^[vV]", "");
        String[] pa = ca.split("\\.");
        String[] pb = cb.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = i < pa.length ? parseIntSafe(pa[i]) : 0;
            int vb = i < pb.length ? parseIntSafe(pb[i]) : 0;
            if (va != vb) return Integer.compare(va, vb);
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try {
            String digits = s.replaceAll("[^0-9].*$", "");
            return digits.isEmpty() ? 0 : Integer.parseInt(digits);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static void checkUpdateAsync(Context ctx, CheckCallback cb) {
        final Context app = ctx.getApplicationContext();
        final long localVc = getLocalVersionCode(app);
        final String localVn = getLocalVersionName(app);

        EXEC.execute(() -> {
            UpdateInfo result = null;
            String lastErr = "";

            // 1) Try version.json (direct raw + China mirror fallbacks; zero GitHub API rate-limit issues)
            for (String metaUrl : VERSION_JSON_URLS) {
                try {
                    byte[] body = httpGetFollowRedirects(metaUrl, 3500, 4500);
                    JSONObject json = new JSONObject(new String(body, StandardCharsets.UTF_8));
                    long remoteVc = json.optLong("version_code", 0);
                    String remoteVn = json.optString("version_name", "").replaceFirst("^[vV]", "");
                    String apkUrl = json.optString("apk_url", "");
                    String notes = json.optString("notes", "");
                    if (!remoteVn.isEmpty() && !apkUrl.isEmpty()) {
                        boolean hasUpdate = (remoteVc > localVc) || (compareSemver(remoteVn, localVn) > 0);
                        result = new UpdateInfo(hasUpdate, localVc, localVn, remoteVc, remoteVn, apkUrl, notes, "");
                        break;
                    }
                } catch (Throwable t) {
                    lastErr = String.valueOf(t.getMessage());
                }
            }

            // 2) Fallback: official GitHub Releases API
            if (result == null) {
                try {
                    byte[] body = httpGetFollowRedirects(GITHUB_LATEST_RELEASE_API, 3500, 5000);
                    JSONObject rel = new JSONObject(new String(body, StandardCharsets.UTF_8));
                    String tag = rel.optString("tag_name", "").replaceFirst("^[vV]", "");
                    String apkDownloadUrl = "";
                    JSONArray assets = rel.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject a = assets.optJSONObject(i);
                            if (a == null) continue;
                            String name = a.optString("name", "");
                            if (name.endsWith(".apk") && name.toLowerCase().contains("offline")) {
                                apkDownloadUrl = a.optString("browser_download_url", "");
                                break;
                            }
                        }
                    }
                    if (!tag.isEmpty() && !apkDownloadUrl.isEmpty()) {
                        boolean hasUpdate = compareSemver(tag, localVn) > 0;
                        result = new UpdateInfo(hasUpdate, localVc, localVn,
                                hasUpdate ? localVc + 1 : localVc, tag, apkDownloadUrl,
                                rel.optString("name", ""), "");
                    }
                } catch (Throwable t) {
                    if (lastErr.isEmpty()) lastErr = String.valueOf(t.getMessage());
                }
            }

            final UpdateInfo finalInfo = result != null
                    ? result
                    : new UpdateInfo(false, localVc, localVn, 0, "", "", "", lastErr);
            MAIN.post(() -> cb.onResult(finalInfo));
        });
    }

    public static void downloadAndInstallAsync(Activity activity, String apkUrl, String versionName, InstallCallback cb) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.getPackageManager().canRequestPackageInstalls()) {
                try {
                    Intent permIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + activity.getPackageName()));
                    activity.startActivity(permIntent);
                } catch (Throwable ignored) {}
                cb.onDone(false, "请先开启「允许来自此来源的应用」，返回后再点一次即可安装");
                return;
            }
        }

        final Context app = activity.getApplicationContext();
        cb.onProgress("正在下载 v" + versionName + "...");
        DiagLog.log("Update", "开始下载更新: " + apkUrl);

        EXEC.execute(() -> {
            PackageInstaller.Session session = null;
            try {
                byte[] apkBytes = null;
                String lastErr = "";
                for (int i = 0; i < DOWNLOAD_MIRROR_PREFIXES.length; i++) {
                    String prefix = DOWNLOAD_MIRROR_PREFIXES[i];
                    String tryUrl = prefix.isEmpty() ? apkUrl : (prefix + apkUrl);
                    try {
                        if (i > 0) {
                            final int idx = i;
                            MAIN.post(() -> cb.onProgress("直连超时，切换加速镜像 " + idx + " 下载中..."));
                        }
                        byte[] candidate = httpGetFollowRedirects(tryUrl, 5000, 20000);
                        if (candidate.length >= 16384 && candidate[0] == 'P' && candidate[1] == 'K') {
                            apkBytes = candidate;
                            DiagLog.log("Update", "下载成功 size=" + candidate.length + " via=" + (prefix.isEmpty() ? "direct" : prefix));
                            break;
                        }
                        lastErr = "文件校验异常(" + candidate.length + "B)";
                    } catch (Throwable t) {
                        lastErr = String.valueOf(t.getMessage());
                    }
                }
                if (apkBytes == null) {
                    throw new RuntimeException(lastErr.isEmpty() ? "下载失败，请检查网络" : lastErr);
                }

                final int kb = apkBytes.length / 1024;
                MAIN.post(() -> cb.onProgress("下载完成 (" + kb + " KB)，正在拉起安装..."));

                PackageInstaller installer = app.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(app.getPackageName());
                int sessionId = installer.createSession(params);
                session = installer.openSession(sessionId);
                try (OutputStream out = session.openWrite("package.apk", 0, apkBytes.length)) {
                    out.write(apkBytes);
                    session.fsync(out);
                }

                Intent callbackIntent = new Intent(app, UpdateInstallReceiver.class);
                callbackIntent.setAction(UpdateInstallReceiver.ACTION_INSTALL_RESULT);
                int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    piFlags |= PendingIntent.FLAG_MUTABLE;
                }
                PendingIntent pi = PendingIntent.getBroadcast(app, sessionId, callbackIntent, piFlags);
                session.commit(pi.getIntentSender());
                session.close();
                session = null;

                MAIN.post(() -> cb.onDone(true, "已拉起系统安装弹窗，点「更新」即可完成升级！"));
            } catch (Throwable t) {
                if (session != null) {
                    try { session.abandon(); } catch (Throwable ignored) {}
                }
                String err = "更新失败: " + t.getMessage();
                DiagLog.log("Update", err);
                MAIN.post(() -> cb.onDone(false, err));
            }
        });
    }

    private static byte[] httpGetFollowRedirects(String targetUrl, int connectMs, int readMs) throws Exception {
        URL url = new URL(targetUrl);
        HttpURLConnection conn = null;
        try {
            int redirects = 0;
            while (redirects < 6) {
                conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(connectMs);
                conn.setReadTimeout(readMs);
                conn.setRequestProperty("Accept", "application/vnd.github+json, */*");
                conn.setRequestProperty("User-Agent", "MiRearPetOffline");
                int code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (loc == null || loc.isEmpty()) throw new RuntimeException("重定向地址为空");
                    url = new URL(url, loc);
                    redirects++;
                    continue;
                }
                if (code != 200) {
                    throw new RuntimeException("HTTP " + code);
                }
                return readAll(conn.getInputStream());
            }
            throw new RuntimeException("重定向过多");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream is = in; ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }
}
