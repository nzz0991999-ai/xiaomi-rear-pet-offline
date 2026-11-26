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

    private static final int MAX_LINES = 60;
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
