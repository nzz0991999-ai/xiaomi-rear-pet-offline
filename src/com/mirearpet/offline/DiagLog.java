package com.mirearpet.offline;

import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Pure local in-app ring-buffer diagnostic logger (zero network calls).
 */
public final class DiagLog {
    public interface Listener {
        void onLogUpdated();
    }

    private static final int MAX_LINES = 40;
    private static final List<String> LINES = new ArrayList<>();
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private DiagLog() {}

    public static void addListener(Listener l) {
        if (l != null && !LISTENERS.contains(l)) LISTENERS.add(l);
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    public static void log(String tag, String msg) {
        if (msg == null) msg = "";
        String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        String[] split = msg.replace("\r", "").split("\n");
        synchronized (LINES) {
            for (String part : split) {
                if (part.trim().isEmpty()) continue;
                LINES.add("[" + ts + "] [" + tag + "] " + part);
                while (LINES.size() > MAX_LINES) {
                    LINES.remove(0);
                }
            }
        }
        notifyListeners();
    }

    public static String getAllText() {
        synchronized (LINES) {
            if (LINES.isEmpty()) return "（暂无日志）";
            StringBuilder sb = new StringBuilder();
            for (String l : LINES) {
                sb.append(l).append('\n');
            }
            return sb.toString().trim();
        }
    }

    /**
     * Builds a concise diagnostic summary guaranteed to fit in a single Douyin/WeChat chat message.
     */
    public static String getCompactSummary(String header, int maxChars) {
        StringBuilder out = new StringBuilder();
        if (header != null && !header.isEmpty()) {
            out.append(header).append('\n');
        }
        List<String> snapshot;
        synchronized (LINES) {
            snapshot = new ArrayList<>(LINES);
        }
        if (snapshot.isEmpty()) {
            out.append("(暂无日志)");
            return out.toString();
        }
        List<String> picked = new ArrayList<>();
        int used = out.length();
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            String compact = compactLine(snapshot.get(i));
            if (compact.isEmpty()) continue;
            if (used + compact.length() + 1 > maxChars) {
                break;
            }
            picked.add(compact);
            used += compact.length() + 1;
        }
        for (int i = picked.size() - 1; i >= 0; i--) {
            out.append(picked.get(i));
            if (i > 0) out.append('\n');
        }
        return out.toString().trim();
    }

    private static String compactLine(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        // Skip bulky verbose dumps in compact chat summary (still visible in UI log)
        if (s.contains("LOG:roots_before") || s.contains("LOG:roots_after")
                || s.contains("LOG:start mode=") || s.contains("LOG:am_start_pet=")) {
            return "";
        }
        // Shorten [HH:mm:ss.SSS] -> HH:mm:ss
        s = s.replaceFirst("^\\[(\\d{2}:\\d{2}:\\d{2})\\.\\d{3}\\]\\s*", "$1 ");
        s = s.replace("com.mirearpet.offline/.", ".")
                .replace("com.mirearpet.offline", "pkg")
                .replace("com.xiaomi.subscreencenter/.SubScreenLauncher", "SubScr")
                .replace("android.hardware.input.InputManagerGlobal", "IM")
                .replace("RearPetActivity.", "Pet.")
                .replace("displayId=", "d=")
                .replace("主屏暂存自动退后台 moveTaskToBack(true)，加速释放主屏图层", "d0退后台")
                .replace("onStop(d=0) 已完成，主屏图层已解绑", "onStop(d0)完成")
                .replace("在 d=1 原生创建离线 WebView", "创建WebView(d1)");
        if (s.length() > 74) {
            s = s.substring(0, 71) + "...";
        }
        return s;
    }

    public static String getTailText(int maxLines) {
        synchronized (LINES) {
            if (LINES.isEmpty()) return "（暂无日志，点上方按钮操作后会自动记录轨迹）";
            int start = Math.max(0, LINES.size() - maxLines);
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < LINES.size(); i++) {
                sb.append(LINES.get(i)).append('\n');
            }
            return sb.toString().trim();
        }
    }

    public static void clear() {
        synchronized (LINES) {
            LINES.clear();
        }
        notifyListeners();
    }

    private static void notifyListeners() {
        MAIN.post(() -> {
            for (Listener l : LISTENERS) {
                try {
                    l.onLogUpdated();
                } catch (Throwable ignored) {}
            }
        });
    }
}
