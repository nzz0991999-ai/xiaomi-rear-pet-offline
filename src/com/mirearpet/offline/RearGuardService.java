package com.mirearpet.offline;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.camera2.CameraManager;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.HashSet;
import java.util.Set;

/**
 * Lightweight foreground guard service for Offline Rear Screen Pet.
 */
public class RearGuardService extends Service {
    public static final String ACTION_START = "com.mirearpet.offline.GUARD_START";
    public static final String ACTION_STOP = "com.mirearpet.offline.GUARD_STOP";
    public static final String ACTION_REPIN_NOW = "com.mirearpet.offline.GUARD_REPIN_NOW";
    public static volatile boolean isRunning = false;
    private static volatile RearGuardService activeInstance = null;

    private static final String CHANNEL_ID = "mi_rear_pet_offline_guard";
    private static final int NOTIF_ID = 8766;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private DisplayManager displayManager;
    private CameraManager cameraManager;
    private final Set<String> activeCameraIds = new HashSet<>();
    private volatile boolean cameraGuardPaused = false;
    private volatile boolean repinInFlight = false;
    private int guardDisplayId = 1;
    private long lastRepinAt = 0L;
    private long lastQuickRefrontAt = 0L;
    private long nextAllowedRepinAt = 0L;

    public static void notifyRearPetLostTop() {
        RearGuardService svc = activeInstance;
        if (svc != null && isRunning) {
            svc.scheduleQuickRefrontAfterPause();
        }
    }

    private void scheduleQuickRefrontAfterPause() {
        handler.postDelayed(() -> tryQuickRefrontIfRearAwake("pet_paused_220ms"), 220);
        handler.postDelayed(() -> tryQuickRefrontIfRearAwake("pet_paused_620ms"), 620);
    }

    private void tryQuickRefrontIfRearAwake(String reason) {
        if (!isRunning || cameraGuardPaused || !activeCameraIds.isEmpty()) return;
        if (ShizukuBridge.isProjectionInFlight()) return;
        if (RearPetActivity.isVisible && RearPetActivity.activeDisplayId == guardDisplayId) return;
        if (!RearPetActivity.hasRearInstance()) return;
        if (!ShizukuBridge.isDisplayAwake(this, guardDisplayId)) return;
        if (!ShizukuBridge.isPermissionGranted()) return;
        long now = System.currentTimeMillis();
        if ((now - lastQuickRefrontAt) < 450) return;
        lastQuickRefrontAt = now;
        DiagLog.log("Guard", "背屏被切回系统主题(" + reason + ")，立即原位拉回桌宠（无需解锁主屏）");
        ShizukuBridge.sendKeepAliveCommand("refront");
    }

    private final Runnable checkLoop = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;
            maybeRepin(false);
            handler.postDelayed(this, 2200);
        }
    };

    private final Runnable displayChangedRepinRunnable = () -> {
        if (!isRunning || cameraGuardPaused || !activeCameraIds.isEmpty()) return;
        maybeRepin(false);
    };

    private final Runnable cameraResumeRunnable = () -> {
        if (!isRunning || !activeCameraIds.isEmpty()) return;
        cameraGuardPaused = false;
        DiagLog.log("Guard", "相机已关闭，恢复背屏守护与常亮锁");
        ShizukuBridge.sendKeepAliveCommand("resume_lock");
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (km != null && km.isKeyguardLocked()) {
            if (RearPetActivity.hasRearInstance() && ShizukuBridge.isPermissionGranted()) {
                handler.postDelayed(() -> {
                    if (isRunning && !cameraGuardPaused && activeCameraIds.isEmpty()) {
                        ShizukuBridge.sendKeepAliveCommand("refront");
                    }
                }, 250);
            }
        } else {
            handler.postDelayed(() -> maybeRepin(false), 350);
        }
    };

    private final CameraManager.AvailabilityCallback cameraCallback = new CameraManager.AvailabilityCallback() {
        @Override
        public void onCameraUnavailable(String cameraId) {
            boolean wasActive = !activeCameraIds.isEmpty();
            activeCameraIds.add(cameraId);
            handler.removeCallbacks(cameraResumeRunnable);
            handler.removeCallbacks(displayChangedRepinRunnable);
            cameraGuardPaused = true;
            if (!wasActive) {
                DiagLog.log("Guard", "检测到相机开启(id=" + cameraId + ")，暂停背屏守护并释放背屏常亮锁");
                ShizukuBridge.sendKeepAliveCommand("pause_lock");
            }
        }

        @Override
        public void onCameraAvailable(String cameraId) {
            boolean removed = activeCameraIds.remove(cameraId);
            if (removed && activeCameraIds.isEmpty() && cameraGuardPaused) {
                handler.removeCallbacks(cameraResumeRunnable);
                handler.postDelayed(cameraResumeRunnable, 1500);
            }
        }
    };

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
            if (displayId == guardDisplayId && !cameraGuardPaused && activeCameraIds.isEmpty()) {
                handler.removeCallbacks(displayChangedRepinRunnable);
                handler.postDelayed(displayChangedRepinRunnable, 280);
            }
        }

        @Override
        public void onDisplayRemoved(int displayId) {}

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId == guardDisplayId && !cameraGuardPaused && activeCameraIds.isEmpty()) {
                handler.removeCallbacks(displayChangedRepinRunnable);
                handler.postDelayed(displayChangedRepinRunnable, 280);
            }
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String act = intent != null ? intent.getAction() : "";
            if (Intent.ACTION_SCREEN_OFF.equals(act)) {
                if (cameraGuardPaused || !activeCameraIds.isEmpty()) return;
                if (RearPetActivity.hasRearInstance() && ShizukuBridge.isPermissionGranted()) {
                    handler.postDelayed(() -> {
                        if (!cameraGuardPaused && activeCameraIds.isEmpty()) {
                            ShizukuBridge.sendKeepAliveCommand("refront");
                        }
                    }, 150);
                    handler.postDelayed(() -> {
                        if (!cameraGuardPaused && activeCameraIds.isEmpty()) {
                            ShizukuBridge.sendKeepAliveCommand("refront");
                        }
                    }, 500);
                }
                return;
            }
            if (Intent.ACTION_USER_PRESENT.equals(act)) {
                handler.postDelayed(() -> maybeRepin(false), 450);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        activeInstance = this;
        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (displayManager != null) {
            displayManager.registerDisplayListener(displayListener, handler);
        }
        try {
            cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (cameraManager != null) {
                cameraManager.registerAvailabilityCallback(cameraCallback, handler);
            }
        } catch (Throwable t) {
            DiagLog.log("Guard", "注册相机监听失败: " + t.getMessage());
        }
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenReceiver, f);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            isRunning = false;
            ShizukuBridge.stopRearKeepAlive();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent != null) {
            guardDisplayId = intent.getIntExtra("display_id", guardDisplayId);
        } else {
            guardDisplayId = getSharedPreferences(RearPetActivity.PREFS_NAME, MODE_PRIVATE)
                    .getInt("display_id", 1);
        }

        boolean wasRunning = isRunning;
        isRunning = true;
        startForeground(NOTIF_ID, buildNotification());
        if (!wasRunning || !ACTION_REPIN_NOW.equals(action)) {
            ShizukuBridge.startRearKeepAlive(this, guardDisplayId);
            if (cameraGuardPaused || !activeCameraIds.isEmpty()) {
                ShizukuBridge.sendKeepAliveCommand("pause_lock");
            }
        }

        if (ACTION_REPIN_NOW.equals(action)) {
            maybeRepin(true);
        } else {
            handler.removeCallbacks(checkLoop);
            handler.postDelayed(checkLoop, 2200);
        }
        return START_STICKY;
    }

    private void maybeRepin(boolean force) {
        if (cameraGuardPaused || !activeCameraIds.isEmpty()) return;
        if (repinInFlight || ShizukuBridge.isProjectionInFlight()) return;
        if (!force && RearPetActivity.isVisible && RearPetActivity.activeDisplayId == guardDisplayId) return;
        if (!force && !ShizukuBridge.isDisplayAwake(this, guardDisplayId)) return;
        if (!ShizukuBridge.isPermissionGranted()) return;

        // Works EVEN WHILE THE FRONT SCREEN IS LOCKED:
        // If RearPetActivity is still alive on Display 1 (just covered by Xiaomi's SubScreenLauncher after double-tap),
        // instantly pull it back on top in-place via the persistent keepalive daemon — NO front-screen unlock needed!
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        boolean keyguardLocked = (km != null && km.isKeyguardLocked());
        if (keyguardLocked) {
            if (RearPetActivity.hasRearInstance()) {
                tryQuickRefrontIfRearAwake("keyguard_locked");
            }
            return;
        }

        // Unlocked fast path: prefer instant in-place refront first if instance is already on Display 1
        if (!force && RearPetActivity.hasRearInstance() && RearPetActivity.activeDisplayId == guardDisplayId) {
            tryQuickRefrontIfRearAwake("unlocked_fast");
        }

        long now = System.currentTimeMillis();
        if (!force && (now - lastRepinAt) < 3000) return;
        if (!force && now < nextAllowedRepinAt) return;

        lastRepinAt = now;
        repinInFlight = true;
        ShizukuBridge.guardRepinOnRearDisplay(this, RearPetActivity.OFFLINE_URL, guardDisplayId, (ok, detail) -> {
            repinInFlight = false;
            if (detail != null && (detail.contains("OK:yield:") || detail.contains("OK:defer_front_busy:"))) {
                nextAllowedRepinAt = System.currentTimeMillis() + 6000L;
            }
        });
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "背屏桌宠离线版守护", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("背屏亮起或切回默认壁纸时自动重新投射桌宠");
            nm.createNotificationChannel(ch);
        }

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent openApp = PendingIntent.getActivity(
                this, 11, new Intent(this, MainActivity.class), piFlags);
        PendingIntent repinIntent = PendingIntent.getService(
                this, 12, new Intent(this, RearGuardService.class).setAction(ACTION_REPIN_NOW), piFlags);
        PendingIntent stopIntent = PendingIntent.getService(
                this, 13, new Intent(this, RearGuardService.class).setAction(ACTION_STOP), piFlags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return b.setContentTitle("背屏桌宠（离线版）守护中")
                .setContentText("目标背屏 displayId=" + guardDisplayId + " · 免电脑离线运行")
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true)
                .setContentIntent(openApp)
                .addAction(new Notification.Action.Builder(null, "立即拉回背屏", repinIntent).build())
                .addAction(new Notification.Action.Builder(null, "停止守护", stopIntent).build())
                .build();
    }

    @Override
    public void onDestroy() {
        if (activeInstance == this) {
            activeInstance = null;
        }
        isRunning = false;
        ShizukuBridge.stopRearKeepAlive();
        handler.removeCallbacks(checkLoop);
        handler.removeCallbacks(cameraResumeRunnable);
        handler.removeCallbacks(displayChangedRepinRunnable);
        if (cameraManager != null) {
            try { cameraManager.unregisterAvailabilityCallback(cameraCallback); } catch (Throwable ignored) {}
        }
        try { unregisterReceiver(screenReceiver); } catch (Throwable ignored) {}
        if (displayManager != null) {
            try { displayManager.unregisterDisplayListener(displayListener); } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
