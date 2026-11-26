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
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraManager;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.util.HashSet;
import java.util.Set;

/**
 * Lightweight, event-driven foreground guard service for Offline Rear Screen Pet.
 * - Recovers Xiaomi default rear theme immediately even while front screen is locked.
 * - Stops polling completely when Display 1 is OFF (0% idle wakeups).
 * - Optional hardware-batched back-face-down eco sleep (phone lying screen-up on desk >= 6s):
 *   blanks OLED to pure #000000 and releases Display-1 wakelock, then wakes & refronts instantly on lift.
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
    private static final long FACE_DOWN_SLEEP_DELAY_MS = 6000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private DisplayManager displayManager;
    private CameraManager cameraManager;
    private SensorManager sensorManager;
    private Sensor gravitySensor;
    private boolean sensorRegistered = false;
    private final Set<String> activeCameraIds = new HashSet<>();
    private volatile boolean cameraGuardPaused = false;
    private volatile boolean ecoFaceDownEnabled = true;
    private volatile boolean ecoSleeping = false;
    private volatile boolean keepAliveLockPaused = false;
    private volatile boolean repinInFlight = false;
    private int guardDisplayId = 1;
    private long faceDownSinceMs = 0L;
    private long lastRepinAt = 0L;
    private long lastQuickRefrontAt = 0L;
    private long nextAllowedRepinAt = 0L;

    public static void notifyRearPetLostTop() {
        RearGuardService svc = activeInstance;
        if (svc != null && isRunning) {
            svc.scheduleQuickRefrontAfterPause();
        }
    }

    public static void notifyUserTouchWake() {
        RearGuardService svc = activeInstance;
        if (svc != null && isRunning) {
            svc.handler.post(() -> svc.exitEcoSleep("touch"));
        }
    }

    public static void notifyFreshProjection() {
        RearGuardService svc = activeInstance;
        if (svc != null && isRunning) {
            svc.handler.post(() -> {
                svc.faceDownSinceMs = 0L;
                if (svc.ecoSleeping) {
                    svc.ecoSleeping = false;
                    RearPetActivity.setEcoBlackIfRunning(false);
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

    private void syncKeepAliveLock() {
        boolean shouldPause = cameraGuardPaused || !activeCameraIds.isEmpty() || ecoSleeping;
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

    private void updateSensorRegistration() {
        boolean want = isRunning && ecoFaceDownEnabled;
        if (want == sensorRegistered) return;
        if (sensorManager == null) {
            sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        }
        if (sensorManager == null) return;
        if (want) {
            if (gravitySensor == null) {
                gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
                if (gravitySensor == null) {
                    gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                }
            }
            if (gravitySensor != null) {
                try {
                    // Hardware sensor-hub batched 2Hz sampling (~0.1mA, <=0.5s instant lift wake)
                    sensorRegistered = sensorManager.registerListener(
                            gravityListener, gravitySensor, 500_000, 500_000, handler);
                } catch (Throwable t) {
                    DiagLog.log("Guard", "注册重力传感器失败: " + t.getMessage());
                }
            }
        } else {
            try {
                sensorManager.unregisterListener(gravityListener);
            } catch (Throwable ignored) {}
            sensorRegistered = false;
            faceDownSinceMs = 0L;
        }
    }

    private void enterEcoSleep() {
        if (!isRunning || ecoSleeping || !ecoFaceDownEnabled) return;
        if (cameraGuardPaused || !activeCameraIds.isEmpty()) return;
        if (!RearPetActivity.hasRearInstance()) return;
        ecoSleeping = true;
        handler.removeCallbacks(checkLoop);
        handler.removeCallbacks(displayChangedRepinRunnable);
        DiagLog.log("Guard", "手机正面朝上平放桌面 6 秒，背屏进入纯黑省电休眠");
        RearPetActivity.setEcoBlackIfRunning(true);
        syncKeepAliveLock();
    }

    private void exitEcoSleep(String reason) {
        faceDownSinceMs = 0L;
        if (!ecoSleeping) return;
        ecoSleeping = false;
        DiagLog.log("Guard", "退出背屏省电休眠(" + reason + ")，立即恢复大眼睛");
        RearPetActivity.setEcoBlackIfRunning(false);
        syncKeepAliveLock();
        if (!isRunning || cameraGuardPaused || !activeCameraIds.isEmpty()) return;
        // Guaranteed instant wake & default-theme recovery even while front screen is locked!
        if (RearPetActivity.hasRearInstance() && ShizukuBridge.isPermissionGranted()) {
            handler.postDelayed(() -> {
                if (isRunning && !ecoSleeping && !cameraGuardPaused && activeCameraIds.isEmpty()) {
                    ShizukuBridge.sendKeepAliveCommand("refront");
                }
            }, 100);
            handler.postDelayed(() -> {
                if (isRunning && !ecoSleeping && !cameraGuardPaused && activeCameraIds.isEmpty()) {
                    ShizukuBridge.sendKeepAliveCommand("refront");
                }
            }, 500);
        } else {
            handler.postDelayed(() -> {
                if (isRunning && !ecoSleeping) {
                    maybeRepin(false);
                }
            }, 500);
        }
        scheduleCheckLoop(1800);
    }

    private final SensorEventListener gravityListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (!isRunning || !ecoFaceDownEnabled || event == null || event.values == null || event.values.length < 3) {
                return;
            }
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];
            // Phone lying flat screen-up (back screen pressed against desk): z ≈ +9.8 m/s²
            boolean isFlatBackDown = (z > 8.8f && Math.abs(x) < 2.2f && Math.abs(y) < 2.2f);
            boolean isLiftedOrTilted = (z < 7.6f || Math.abs(x) > 3.2f || Math.abs(y) > 3.2f);

            if (!ecoSleeping) {
                if (isFlatBackDown && !cameraGuardPaused && activeCameraIds.isEmpty() && RearPetActivity.hasRearInstance()) {
                    long now = SystemClock.uptimeMillis();
                    if (faceDownSinceMs == 0L) {
                        faceDownSinceMs = now;
                    } else if ((now - faceDownSinceMs) >= FACE_DOWN_SLEEP_DELAY_MS) {
                        enterEcoSleep();
                    }
                } else {
                    faceDownSinceMs = 0L;
                }
            } else if (isLiftedOrTilted) {
                exitEcoSleep("lifted");
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    private void scheduleQuickRefrontAfterPause() {
        if (ecoSleeping) return;
        handler.postDelayed(() -> tryQuickRefrontIfRearAwake("pet_paused_220ms"), 220);
        handler.postDelayed(() -> tryQuickRefrontIfRearAwake("pet_paused_620ms"), 620);
    }

    private void tryQuickRefrontIfRearAwake(String reason) {
        if (!isRunning || ecoSleeping || cameraGuardPaused || !activeCameraIds.isEmpty()) return;
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
            if (!isRunning || ecoSleeping || cameraGuardPaused || !activeCameraIds.isEmpty()) return;
            if (!ShizukuBridge.isDisplayAwake(RearGuardService.this, guardDisplayId)) {
                // Display 1 is OFF: stop polling completely (0% background wakeups).
                // DisplayListener.onDisplayChanged(guardDisplayId) restarts checkLoop on wake.
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
        if (!isRunning || ecoSleeping || cameraGuardPaused || !activeCameraIds.isEmpty()) return;
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
                DiagLog.log("Guard", "检测到相机开启(id=" + cameraId + ")，暂停背屏守护并释放背屏常亮锁");
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
            if (ecoSleeping || cameraGuardPaused || !activeCameraIds.isEmpty()) return;
            if (ShizukuBridge.isDisplayAwake(RearGuardService.this, guardDisplayId)) {
                handler.removeCallbacks(displayChangedRepinRunnable);
                handler.postDelayed(displayChangedRepinRunnable, 220);
                scheduleCheckLoop(1500);
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
            faceDownSinceMs = 0L;
            RearPetActivity.setEcoBlackIfRunning(false);
            updateSensorRegistration();
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
            updateSensorRegistration();
            if (!ecoFaceDownEnabled && ecoSleeping) {
                exitEcoSleep("switch_off");
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
        faceDownSinceMs = 0L;
        RearPetActivity.setEcoBlackIfRunning(false);
        startForeground(NOTIF_ID, buildNotification());
        if (!wasRunning || !ACTION_REPIN_NOW.equals(action)) {
            keepAliveLockPaused = false;
            ShizukuBridge.startRearKeepAlive(this, guardDisplayId);
            syncKeepAliveLock();
        }
        updateSensorRegistration();

        if (ACTION_REPIN_NOW.equals(action)) {
            maybeRepin(true);
            scheduleCheckLoop(2500);
        } else {
            scheduleCheckLoop(2500);
        }
        return START_STICKY;
    }

    private void maybeRepin(boolean force) {
        if (ecoSleeping || cameraGuardPaused || !activeCameraIds.isEmpty()) return;
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
                .setContentText("目标背屏 displayId=" + guardDisplayId + " · 低功耗事件驱动")
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
        faceDownSinceMs = 0L;
        RearPetActivity.setEcoBlackIfRunning(false);
        updateSensorRegistration();
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
