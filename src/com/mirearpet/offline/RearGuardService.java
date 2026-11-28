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
 * Lightweight, event-driven foreground guard service for Offline Rear Screen Pet.
 * - Recovers Xiaomi default rear theme immediately even while front screen is locked.
 * - Stops polling completely when Display 1 is OFF or in back-face-down eco sleep.
 * - Coordinates Shizuku Display-1 wakelock release/resume with RearPetActivity's accelerometer eco sleep.
 */
public class RearGuardService extends Service {
    public static final String ACTION_START = "com.mirearpet.offline.GUARD_START";
    public static final String ACTION_STOP = "com.mirearpet.offline.GUARD_STOP";
    public static final String ACTION_REPIN_NOW = "com.mirearpet.offline.GUARD_REPIN_NOW";
    public static final String ACTION_UPDATE_CONFIG = "com.mirearpet.offline.GUARD_UPDATE_CONFIG";
    public static volatile boolean isRunning = false;
    private static volatile RearGuardService activeInstance = null;

    private static final String CHANNEL_ID = "mi_rear_pet_offline_guard";
    private static final int NOTIF_ID = 8766;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private DisplayManager displayManager;
    private CameraManager cameraManager;
    private final Set<String> activeCameraIds = new HashSet<>();
    private volatile boolean cameraGuardPaused = false;
    private volatile boolean ecoFaceDownEnabled = true;
    private volatile boolean ecoSleeping = false;
    private volatile boolean keepAliveLockPaused = false;
    private volatile boolean repinInFlight = false;
    private int guardDisplayId = 1;
    private long lastRepinAt = 0L;
    private long lastQuickRefrontAt = 0L;
    private long nextAllowedRepinAt = 0L;

    public static boolean isCameraBusy() {
        RearGuardService svc = activeInstance;
        return svc != null && (svc.cameraGuardPaused || !svc.activeCameraIds.isEmpty());
    }

    public static void notifyRearPetLostTop() {
        RearGuardService svc = activeInstance;
        if (svc != null && isRunning) {
            svc.scheduleQuickRefrontAfterPause();
        }
    }

    public static void notifyEcoSleepState(boolean sleeping) {
        RearGuardService svc = activeInstance;
        if (svc != null && isRunning) {
            svc.handler.post(() -> svc.handleEcoSleepStateChanged(sleeping));
        }
    }

    public static void notifyFreshProjection() {
        RearGuardService svc = activeInstance;
        if (svc != null && isRunning) {
            svc.handler.post(() -> {
                if (svc.ecoSleeping) {
                    svc.ecoSleeping = false;
                    svc.syncKeepAliveLock();
                }
                svc.scheduleCheckLoop(2500);
            });
        }
    }

    public static int getGuardDisplayId() {
        RearGuardService svc = activeInstance;
        return svc != null ? svc.guardDisplayId : 1;
    }

    private void handleEcoSleepStateChanged(boolean sleeping) {
        if (!isRunning || ecoSleeping == sleeping) return;
        ecoSleeping = sleeping;
        if (sleeping) {
            // Pause periodic polling while pure-black eco sleeping, but KEEP Display-1 wakelock active
            // so HyperOS never times out Display 1 back to Xiaomi's default SubScreenLauncher wallpaper!
            handler.removeCallbacks(checkLoop);
        } else {
            if (cameraGuardPaused || !activeCameraIds.isEmpty()) return;
            // Guaranteed instant wake & default-theme recovery if anything took top while on desk
            if (RearPetActivity.hasRearInstance() && ShizukuBridge.isPermissionGranted()) {
                if (!RearPetActivity.isVisible || !ShizukuBridge.isDisplayAwake(this, guardDisplayId)) {
                    handler.postDelayed(() -> {
                        if (isRunning && !cameraGuardPaused && activeCameraIds.isEmpty()) {
                            ShizukuBridge.sendKeepAliveCommand("refront");
                        }
                    }, 80);
                    handler.postDelayed(() -> {
                        if (isRunning && !cameraGuardPaused && activeCameraIds.isEmpty()) {
                            ShizukuBridge.sendKeepAliveCommand("refront");
                        }
                    }, 480);
                }
            } else {
                handler.postDelayed(() -> {
                    if (isRunning) {
                        maybeRepin(false);
                    }
                }, 400);
            }
            scheduleCheckLoop(1800);
        }
    }

    private void syncKeepAliveLock() {
        // ONLY release Display-1 wakelock for Camera; NEVER release for eco-sleep so HyperOS never kicks us off Display 1!
        boolean shouldPause = cameraGuardPaused || !activeCameraIds.isEmpty();
        if (shouldPause == keepAliveLockPaused) return;
        keepAliveLockPaused = shouldPause;
        ShizukuBridge.sendKeepAliveCommand(shouldPause ? "pause_lock" : "resume_lock");
    }

    private void scheduleCheckLoop(long delayMs) {
        handler.removeCallbacks(checkLoop);
        if (isRunning && !ecoSleeping && !cameraGuardPaused && activeCameraIds.isEmpty()) {
            handler.postDelayed(checkLoop, delayMs);
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
        DiagLog.log("Guard", "切回系统主题(" + reason + ")，原位拉回桌宠");
        ShizukuBridge.sendKeepAliveCommand("refront");
    }

    private final Runnable checkLoop = new Runnable() {
        @Override
        public void run() {
            if (!isRunning || ecoSleeping || cameraGuardPaused || !activeCameraIds.isEmpty()) return;
            if (!ShizukuBridge.isDisplayAwake(RearGuardService.this, guardDisplayId)) {
                return;
            }
            maybeRepin(false);
            long nextDelay = (RearPetActivity.isVisible && RearPetActivity.activeDisplayId == guardDisplayId)
                    ? 12000L
                    : 2500L;
            handler.postDelayed(this, nextDelay);
        }
    };

    private final Runnable displayChangedRepinRunnable = () -> {
        if (!isRunning || cameraGuardPaused || !activeCameraIds.isEmpty()) return;
        maybeRepin(false);
    };

    private final Runnable cameraResumeRunnable = () -> {
        if (!isRunning || !activeCameraIds.isEmpty()) return;
        cameraGuardPaused = false;
        DiagLog.log("Guard", "相机已关闭，恢复背屏守护");
        syncKeepAliveLock();
        if (ecoSleeping) return;
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (km != null && km.isKeyguardLocked()) {
            if (RearPetActivity.hasRearInstance() && ShizukuBridge.isPermissionGranted()) {
                handler.postDelayed(() -> {
                    if (isRunning && !ecoSleeping && !cameraGuardPaused && activeCameraIds.isEmpty()) {
                        ShizukuBridge.sendKeepAliveCommand("refront");
                    }
                }, 250);
            }
        } else {
            handler.postDelayed(() -> {
                if (isRunning && !ecoSleeping) {
                    maybeRepin(false);
                }
            }, 350);
        }
        scheduleCheckLoop(2000);
    };

    private final CameraManager.AvailabilityCallback cameraCallback = new CameraManager.AvailabilityCallback() {
        @Override
        public void onCameraUnavailable(String cameraId) {
            boolean wasActive = !activeCameraIds.isEmpty();
            activeCameraIds.add(cameraId);
            handler.removeCallbacks(cameraResumeRunnable);
            handler.removeCallbacks(displayChangedRepinRunnable);
            handler.removeCallbacks(checkLoop);
            cameraGuardPaused = true;
            if (!wasActive) {
                DiagLog.log("Guard", "相机开启(id=" + cameraId + ")，暂停守护");
                syncKeepAliveLock();
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
            if (displayId == guardDisplayId && !ecoSleeping && !cameraGuardPaused && activeCameraIds.isEmpty()) {
                handler.removeCallbacks(displayChangedRepinRunnable);
                handler.postDelayed(displayChangedRepinRunnable, 220);
                scheduleCheckLoop(1500);
            }
        }

        @Override
        public void onDisplayRemoved(int displayId) {}

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId != guardDisplayId) return;
            if (cameraGuardPaused || !activeCameraIds.isEmpty()) return;
            if (ShizukuBridge.isDisplayAwake(RearGuardService.this, guardDisplayId)) {
                handler.removeCallbacks(displayChangedRepinRunnable);
                handler.postDelayed(displayChangedRepinRunnable, 220);
                if (!ecoSleeping) {
                    scheduleCheckLoop(1500);
                }
            } else {
                handler.removeCallbacks(displayChangedRepinRunnable);
                handler.removeCallbacks(checkLoop);
            }
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String act = intent != null ? intent.getAction() : "";
            if (Intent.ACTION_SCREEN_OFF.equals(act)) {
                if (ecoSleeping || cameraGuardPaused || !activeCameraIds.isEmpty()) return;
                if (RearPetActivity.hasRearInstance() && ShizukuBridge.isPermissionGranted()) {
                    handler.postDelayed(() -> {
                        if (isRunning && !ecoSleeping && !cameraGuardPaused && activeCameraIds.isEmpty()) {
                            ShizukuBridge.sendKeepAliveCommand("refront");
                        }
                    }, 150);
                    handler.postDelayed(() -> {
                        if (isRunning && !ecoSleeping && !cameraGuardPaused && activeCameraIds.isEmpty()) {
                            ShizukuBridge.sendKeepAliveCommand("refront");
                        }
                    }, 500);
                }
                return;
            }
            if (Intent.ACTION_USER_PRESENT.equals(act)) {
                if (ecoSleeping || cameraGuardPaused || !activeCameraIds.isEmpty()) return;
                handler.postDelayed(() -> {
                    if (isRunning && !ecoSleeping) {
                        maybeRepin(false);
                        scheduleCheckLoop(2500);
                    }
                }, 450);
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
            ecoSleeping = false;
            keepAliveLockPaused = false;
            ShizukuBridge.stopRearKeepAlive();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        boolean prefEco = getSharedPreferences(RearPetActivity.PREFS_NAME, MODE_PRIVATE)
                .getBoolean("eco_facedown", true);
        if (ACTION_UPDATE_CONFIG.equals(action)) {
            ecoFaceDownEnabled = intent != null ? intent.getBooleanExtra("eco_facedown", prefEco) : prefEco;
            if (!isRunning) return START_NOT_STICKY;
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIF_ID, buildNotification());
            }
            return START_STICKY;
        }

        if (intent != null) {
            guardDisplayId = intent.getIntExtra("display_id", guardDisplayId);
            ecoFaceDownEnabled = intent.getBooleanExtra("eco_facedown", prefEco);
        } else {
            guardDisplayId = getSharedPreferences(RearPetActivity.PREFS_NAME, MODE_PRIVATE)
                    .getInt("display_id", 1);
            ecoFaceDownEnabled = prefEco;
        }

        boolean wasRunning = isRunning;
        isRunning = true;
        ecoSleeping = false;
        RearPetActivity.setEcoBlackIfRunning(false);
        startForeground(NOTIF_ID, buildNotification());
        if (!wasRunning || !ACTION_REPIN_NOW.equals(action)) {
            keepAliveLockPaused = false;
            ShizukuBridge.startRearKeepAlive(this, guardDisplayId);
            syncKeepAliveLock();
        }

        if (ACTION_REPIN_NOW.equals(action)) {
            maybeRepin(true);
            scheduleCheckLoop(2500);
        } else {
            scheduleCheckLoop(2500);
        }
        return START_STICKY;
    }

    private void maybeRepin(boolean force) {
        if (cameraGuardPaused || !activeCameraIds.isEmpty()) return;
        if (repinInFlight || ShizukuBridge.isProjectionInFlight()) return;
        if (!force && RearPetActivity.isVisible && RearPetActivity.activeDisplayId == guardDisplayId) return;
        if (!force && !ShizukuBridge.isDisplayAwake(this, guardDisplayId)) return;
        if (!ShizukuBridge.isPermissionGranted()) return;

        // Explicit user tap on notification "立即拉回背屏": if RearPetActivity was killed, safely re-project it.
        if (force && !RearPetActivity.hasRearInstance()) {
            ShizukuBridge.launchOnRearDisplay(this, RearPetActivity.OFFLINE_URL, guardDisplayId, null);
            return;
        }

        // Automatic background guard NEVER cold-launches or moves RearPetActivity onto Display 0!
        if (!RearPetActivity.hasRearInstance()) {
            return;
        }

        // Works EVEN WHILE THE FRONT SCREEN IS LOCKED (strictly in-place on Display 1)
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        boolean keyguardLocked = (km != null && km.isKeyguardLocked());
        if (keyguardLocked) {
            tryQuickRefrontIfRearAwake("keyguard_locked");
            return;
        }

        if (!force && RearPetActivity.activeDisplayId == guardDisplayId) {
            tryQuickRefrontIfRearAwake("unlocked_fast");
        }

        long now = System.currentTimeMillis();
        if (!force && (now - lastRepinAt) < 3500) return;
        if (!force && now < nextAllowedRepinAt) return;

        lastRepinAt = now;
        repinInFlight = true;
        ShizukuBridge.guardRepinOnRearDisplay(this, RearPetActivity.OFFLINE_URL, guardDisplayId, (ok, detail) -> {
            repinInFlight = false;
            if (!ok || (detail != null && (detail.contains("OK:yield:")
                    || detail.contains("OK:refront_pending:")
                    || detail.contains("OK:no_rear_task")))) {
                nextAllowedRepinAt = System.currentTimeMillis() + 8000L;
            }
        });
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "背屏桌宠守护", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("保持背屏桌宠后台常驻，息屏或切走后自动拉回");
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

        String subtitle = ecoFaceDownEnabled
                ? "息屏防切走 · 贴桌自动黑屏省电"
                : "息屏与切走后自动恢复大眼睛";

        return b.setContentTitle("背屏桌宠守护中")
                .setContentText(subtitle)
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
        ecoSleeping = false;
        keepAliveLockPaused = false;
        ShizukuBridge.stopRearKeepAlive();
        handler.removeCallbacksAndMessages(null);
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
