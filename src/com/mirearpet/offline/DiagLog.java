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
        // Skip noisy routine/redundant lines so critical events fit in <=280 chars for Douyin chat
        if (s.contains("LOG:roots_before") || s.contains("LOG:roots_after")
                || s.contains("LOG:start mode=") || s.contains("LOG:am_start_pet=")
                || s.contains("LOG:bring_main=Starting:") || s.contains("LOG:wait_hidden CONFIRMED")
                || s.contains("LOG:withdraw_target") || s.contains("LOG:removeTask(")
                || s.contains("withdraw exit=0") || s.contains("auto-withdraw exit=0")
                || s.contains("onStop(d=0) 已完成") || s.contains("主屏暂存自动退后台")
                || s.contains("贴桌传感器就绪: true") || s.contains("launchOnRearDisplay start")
                || s.contains("检测到已有背屏实例") || s.contains("RearPetActivity.onStart ")
                || s.contains("RearPetActivity.onCreate ")) {
            return "";
        }
        // Extract mm:ss only (saves 7 chars per line) and drop verbose [Tag]
        String ts = "";
        if (s.length() >= 10 && s.charAt(0) == '[' && s.charAt(9) == '.') {
            ts = s.substring(4, 9) + " ";
            s = s.replaceFirst("^\\[[^\\]]+\\]\\s*", "");
        }
        s = s.replaceFirst("^\\[(PetAct|Helper|Web|Guard|Pet|Bridge|KeepAlive)\\]\\s*", "");
        // Compact common lifecycle & status messages
        s = s.replace("com.mirearpet.offline/.", ".")
                .replace("com.mirearpet.offline", "pkg")
                .replace("com.xiaomi.subscreencenter/.SubScreenLauncher", "SubScr")
                .replace("android.hardware.input.InputManagerGlobal", "IM")
                .replace("RearPetActivity.onConfigChanged ", "cfg ")
                .replace("RearPetActivity.onResume ", "res ")
                .replace("RearPetActivity.onPause ", "pau ")
                .replace("RearPetActivity.onStop ", "stp ")
                .replace("RearPetActivity.onDestroy ", "dst ")
                .replace("RearPetActivity.", "Pet.")
                .replace("displayId=", "d")
                .replace(" size=", " ")
                .replaceAll(" dpi=\\d+", "")
                .replaceAll(" taskId=\\d+", "")
                .replace("在 d1 原生创建离线 WebView", "mkWeb d1")
                .replace("onPageFinished ", "web ")
                .replace(" view=", " ")
                .replace("LOG:wakeDisplay(1) via IM ok=true", "wk1=ok")
                .replace("LOG:rear_wakelock=", "wl=")
                .replace("OK:moved:", "mvOK:")
                .replace("OK:withdrawn:", "wdOK:")
                .replace("OK:guard_rebuilt:", "grdOK:")
                .replace("背屏竖屏(", "rot(")
                .replace(")，请求横屏", ")")
                .replace("贴桌休眠(", "slp(")
                .replace("退出休眠(", "wk(")
                .replace("安全熔断：抵达背屏 ", "ABORT:")
                .replace("背屏实例被拉回主屏(", "EX_D0(")
                .replace("，立即自毁", "");
        String line = ts + s.trim();
        if (line.length() > 48) {
            line = line.substring(0, 45) + "...";
        }
        return line;
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
