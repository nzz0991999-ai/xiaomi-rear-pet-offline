package com.mirearpet.offline;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/**
 * Pure-black, borderless, sticky-immersive OFFLINE pet container for Xiaomi Rear Screen
 * (and any regular Android phone / tablet via FullscreenPetActivity).
 * Always loads bundled local asset file:///android_asset/pet.html — zero PC or network needed.
 */
public class RearPetActivity extends Activity {
    public static final String OFFLINE_URL = "file:///android_asset/pet.html";
    public static final String PREFS_NAME = "mi_rear_pet_offline";

    public static volatile boolean isVisible = false;
    public static volatile int activeDisplayId = -1;
    private static volatile WeakReference<RearPetActivity> rearInstanceRef = new WeakReference<>(null);
    private static volatile WeakReference<RearPetActivity> frontInstanceRef = new WeakReference<>(null);

    private FrameLayout rootLayout;
    private WebView webView;
    private TextView statusOverlay;
    private DisplayManager displayManager;
    private SensorManager sensorManager;
    private Sensor accelSensor;
    private boolean ecoSensorRegistered = false;
    private boolean ecoFaceDownEnabled = true;
    private boolean faceDownTimerPending = false;
    private float lastSensorZ = 0f;
    private int webViewCreatedDisplayId = -1;
    private boolean createdOnDisplay0 = false;
    private boolean hasEverBeenOnRearDisplay = false;
    private boolean stoppedOnDisplay0 = false;
    private volatile boolean currentEcoBlack = false;
    private boolean lastJsSuspended = false;
    private static final long FACE_DOWN_SLEEP_DELAY_MS = 5000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable faceDownSleepRunnable = () -> {
        faceDownTimerPending = false;
        enterEcoSleep();
    };
    private final SensorEventListener ecoAccelListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (isFrontFullscreenMode() || !hasEverBeenOnRearDisplay || !ecoFaceDownEnabled || isFinishing()) return;
            if (event == null || event.values == null || event.values.length < 3) return;
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];
            lastSensorZ = z;
            // Phone lying flat screen-up (back screen pressed against desk, allowing up to ~21° camera bump / case tilt)
            boolean isFlatBackDown = (z > 8.2f && Math.abs(x) < 3.6f && Math.abs(y) < 3.6f);
            boolean isLiftedOrTilted = (z < 7.0f || Math.abs(x) > 4.6f || Math.abs(y) > 4.6f);

            if (!currentEcoBlack) {
                if (isFlatBackDown && !RearGuardService.isCameraBusy()) {
                    if (!faceDownTimerPending) {
                        faceDownTimerPending = true;
                        handler.removeCallbacks(faceDownSleepRunnable);
                        handler.postDelayed(faceDownSleepRunnable, FACE_DOWN_SLEEP_DELAY_MS);
                    }
                } else if (faceDownTimerPending) {
                    faceDownTimerPending = false;
                    handler.removeCallbacks(faceDownSleepRunnable);
                }
            } else if (isLiftedOrTilted) {
                exitEcoSleep("lifted");
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };
    private final DisplayManager.DisplayListener rearDisplayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {}

        @Override
        public void onDisplayRemoved(int displayId) {}

        @Override
        public void onDisplayChanged(int displayId) {
            if (isFrontFullscreenMode() || displayId == 0) return;
            if (displayId != currentDisplayId()) return;
            int state = ShizukuBridge.getDisplayState(RearPetActivity.this, displayId);
            boolean off = (state == android.view.Display.STATE_OFF);
            if (off != lastJsSuspended) {
                lastJsSuspended = off;
                DiagLog.log("Web", "d=" + displayId + " state=" + state + (off ? " 熄屏暂停" : " 亮屏恢复"));
                setJsSuspended(off);
            }
        }
    };
    private final Runnable retryRunnable = () -> {
        if (webView != null && !isFinishing()) {
            DiagLog.log("Web", "重试加载本地桌宠");
            webView.loadUrl(OFFLINE_URL);
        }
    };
    private final Runnable autoHideOnDisplay0Runnable = () -> {
        if (!isFrontFullscreenMode() && !isFinishing() && !hasEverBeenOnRearDisplay && currentDisplayId() == 0) {
            DiagLog.log("PetAct", "主屏暂存自动退后台 moveTaskToBack(true)，加速释放主屏图层");
            try {
                moveTaskToBack(true);
            } catch (Throwable ignored) {}
        }
    };
    private final Runnable frontStuckWatchdog = () -> {
        if (!isFrontFullscreenMode() && !isFinishing() && !hasEverBeenOnRearDisplay && currentDisplayId() == 0) {
            DiagLog.log("PetAct", "主屏暂存超过 5 秒未完成搬运，自动关闭以避免停留正面");
            try {
                finishAndRemoveTask();
            } catch (Throwable ignored) {
                finish();
            }
        }
    };

    protected boolean isFrontFullscreenMode() {
        return false;
    }

    public static boolean hasRearInstance() {
        RearPetActivity act = rearInstanceRef.get();
        return act != null && !act.isFinishing() && !act.isDestroyed();
    }

    public static void finishRearInstanceIfRunning() {
        RearPetActivity act = rearInstanceRef.get();
        if (act != null && !act.isFinishing()) {
            act.runOnUiThread(() -> {
                try {
                    act.finishAndRemoveTask();
                } catch (Throwable ignored) {
                    act.finish();
                }
            });
        }
    }

    public static void pushMoodIfRunning(String mood) {
        if (mood == null || mood.isEmpty()) return;
        String safe = mood.replaceAll("[^a-z_]", "");
        RearPetActivity[] targets = new RearPetActivity[]{ rearInstanceRef.get(), frontInstanceRef.get() };
        for (RearPetActivity act : targets) {
            if (act != null && !act.isFinishing() && !act.isDestroyed()) {
                act.runOnUiThread(() -> {
                    if (act.webView != null) {
                        act.webView.evaluateJavascript(
                                "window.setStandaloneMood && window.setStandaloneMood('" + safe + "');",
                                null
                        );
                    }
                });
            }
        }
    }

    public static void setEcoFaceDownEnabled(boolean enabled) {
        RearPetActivity act = rearInstanceRef.get();
        if (act != null && !act.isFinishing() && !act.isDestroyed()) {
            act.runOnUiThread(() -> {
                act.ecoFaceDownEnabled = enabled;
                act.updateEcoSensorRegistration();
                if (!enabled && act.currentEcoBlack) {
                    act.exitEcoSleep("switch_off");
                }
            });
        }
    }

    public static void setEcoBlackIfRunning(boolean ecoBlack) {
        RearPetActivity act = rearInstanceRef.get();
        if (act != null && !act.isFinishing() && !act.isDestroyed()) {
            act.runOnUiThread(() -> {
                if (ecoBlack) {
                    act.enterEcoSleep();
                } else {
                    act.exitEcoSleep("external");
                }
            });
        }
    }

    private void updateEcoSensorRegistration() {
        if (isFrontFullscreenMode()) return;
        boolean want = hasEverBeenOnRearDisplay && ecoFaceDownEnabled && !isFinishing();
        if (want == ecoSensorRegistered) return;
        if (sensorManager == null) {
            sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        }
        if (sensorManager == null) return;
        if (want) {
            if (accelSensor == null) {
                accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            }
            if (accelSensor != null) {
                try {
                    ecoSensorRegistered = sensorManager.registerListener(
                            ecoAccelListener, accelSensor, SensorManager.SENSOR_DELAY_NORMAL, handler);
                    DiagLog.log("Pet", "贴桌传感器就绪: " + ecoSensorRegistered);
                } catch (Throwable t) {
                    DiagLog.log("Pet", "注册贴桌传感器失败: " + t.getMessage());
                }
            }
        } else {
            faceDownTimerPending = false;
            handler.removeCallbacks(faceDownSleepRunnable);
            try {
                sensorManager.unregisterListener(ecoAccelListener);
            } catch (Throwable ignored) {}
            ecoSensorRegistered = false;
        }
    }

    private void enterEcoSleep() {
        if (isFrontFullscreenMode() || !hasEverBeenOnRearDisplay || currentEcoBlack || isFinishing()) return;
        if (RearGuardService.isCameraBusy()) return;
        currentEcoBlack = true;
        faceDownTimerPending = false;
        handler.removeCallbacks(faceDownSleepRunnable);
        DiagLog.log("Pet", String.format(java.util.Locale.US, "贴桌休眠(z=%.1f)", lastSensorZ));
        if (currentDisplayId() != 0) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (webView != null) {
            webView.evaluateJavascript("window.setPetEcoBlack && window.setPetEcoBlack(true);", null);
        }
        RearGuardService.notifyEcoSleepState(true);
    }

    private void exitEcoSleep(String reason) {
        faceDownTimerPending = false;
        handler.removeCallbacks(faceDownSleepRunnable);
        if (!currentEcoBlack) return;
        currentEcoBlack = false;
        DiagLog.log("Pet", String.format(java.util.Locale.US, "退出休眠(%s,z=%.1f)", reason, lastSensorZ));
        int dId = currentDisplayId();
        if (!isFrontFullscreenMode() && dId != 0) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (webView != null) {
            lastJsSuspended = false;
            webView.evaluateJavascript(
                    "window.setPetEcoBlack && window.setPetEcoBlack(false);"
                            + "window.setPetSuspended && window.setPetSuspended(false);",
                    null
            );
        }
        if (RearGuardService.isRunning) {
            RearGuardService.notifyEcoSleepState(false);
        } else if (!isFrontFullscreenMode() && dId != 0
                && ShizukuBridge.getDisplayState(this, dId) == Display.STATE_OFF
                && ShizukuBridge.isPermissionGranted()) {
            ShizukuBridge.wakeRearDisplayOnly(this, dId);
        }
    }

    private void setJsSuspended(boolean suspended) {
        runOnUiThread(() -> {
            if (webView != null && !isFinishing()) {
                webView.evaluateJavascript(
                        "window.setPetSuspended && window.setPetSuspended(" + suspended + ");",
                        null
                );
            }
        });
    }

    private final class PetJsBridge {
        @JavascriptInterface
        public String getConfigJson() {
            return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString("web_cfg_json", "");
        }

        @JavascriptInterface
        public void saveConfigJson(String json) {
            if (json == null) return;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString("web_cfg_json", json).apply();
        }

        @JavascriptInterface
        public String getPetMood() {
            return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString("pet_mood", "idle");
        }

        @JavascriptInterface
        public void setPetMood(String mood) {
            if (mood == null || mood.isEmpty()) return;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString("pet_mood", mood).apply();
        }

        @JavascriptInterface
        public void onUserTouchWake() {
            runOnUiThread(() -> exitEcoSleep("touch"));
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!isFrontFullscreenMode()) {
            rearInstanceRef = new WeakReference<>(this);
            createdOnDisplay0 = (currentDisplayId() == 0);
            ecoFaceDownEnabled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean("eco_facedown", true);
            displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            if (displayManager != null) {
                displayManager.registerDisplayListener(rearDisplayListener, handler);
            }
        } else {
            frontInstanceRef = new WeakReference<>(this);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        getWindow().getDecorView().setBackgroundColor(Color.BLACK);

        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.BLACK);

        statusOverlay = new TextView(this);
        statusOverlay.setBackgroundColor(Color.BLACK);
        statusOverlay.setTextColor(Color.parseColor("#7FEAFF"));
        statusOverlay.setTextSize(14f);
        statusOverlay.setGravity(Gravity.CENTER);
        statusOverlay.setPadding(32, 32, 32, 32);
        statusOverlay.setText("正在加载背屏桌宠...");
        statusOverlay.setOnClickListener(v -> {
            statusOverlay.setText("正在重新加载桌宠...");
            if (webView != null) webView.loadUrl(OFFLINE_URL);
        });

        rootLayout.addView(statusOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        setContentView(rootLayout);

        applyImmersive();
        logLifecycle("onCreate");
        ensureWebViewReadyIfOnTargetDisplay();
    }

    private int currentDisplayId() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && getDisplay() != null) {
            return getDisplay().getDisplayId();
        }
        return 0;
    }

    private void applyRearLockscreenPolicy(int dId) {
        if (isFrontFullscreenMode()) return;
        boolean onRear = (dId != 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(onRear);
        }
        if (onRear) {
            // Keep pet visible on Display 1 even when the front screen locks, and restore full touch input!
            //noinspection deprecation
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
            if (!currentEcoBlack) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        } else {
            // On Display 0 staging: NEVER touch front lockscreen, NEVER keep Display 0 awake,
            // and NEVER intercept/freeze front touches!
            //noinspection deprecation
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        }
    }

    private boolean isMainScreenBounds() {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        return Math.max(w, h) >= 1600;
    }

    private boolean abortIfBouncedToMainScreen(String where) {
        if (isFrontFullscreenMode() || !hasEverBeenOnRearDisplay) return false;
        int dId = currentDisplayId();
        if (dId == 0 || isMainScreenBounds()) {
            int w = getResources().getDisplayMetrics().widthPixels;
            int h = getResources().getDisplayMetrics().heightPixels;
            DiagLog.log("PetAct", "背屏实例被拉回主屏(" + where + ",d=" + dId + "," + w + "x" + h + ")，立即自毁");
            if (webView != null) {
                try {
                    rootLayout.removeView(webView);
                    webView.destroy();
                } catch (Throwable ignored) {}
                webView = null;
                webViewCreatedDisplayId = -1;
            }
            try {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            } catch (Throwable ignored) {}
            try {
                moveTaskToBack(true);
            } catch (Throwable ignored) {}
            try {
                finishAndRemoveTask();
            } catch (Throwable ignored) {
                finish();
            }
            return true;
        }
        return false;
    }

    private void ensureWebViewReadyIfOnTargetDisplay() {
        if (abortIfBouncedToMainScreen("ensure")) {
            return;
        }
        int dId = currentDisplayId();
        applyRearLockscreenPolicy(dId);
        if (!isFrontFullscreenMode() && dId == 0) {
            if (webView != null) {
                try {
                    rootLayout.removeView(webView);
                    webView.destroy();
                } catch (Throwable ignored) {}
                webView = null;
                webViewCreatedDisplayId = -1;
            }
            statusOverlay.setText("正在投射到背屏...");
            statusOverlay.setVisibility(View.VISIBLE);
            handler.removeCallbacks(frontStuckWatchdog);
            handler.postDelayed(frontStuckWatchdog, 5000);
            return;
        }
        if (!isFrontFullscreenMode() && dId != 0) {
            handler.removeCallbacks(autoHideOnDisplay0Runnable);
            handler.removeCallbacks(frontStuckWatchdog);
            // HARD SAFETY GATE:
            // If this Activity was staged on Display 0 and somehow moved to Display 1 WITHOUT ever completing
            // onStop(d=0), HyperOS SurfaceFlinger can leak a frozen 976x596 WebView on the front screen!
            // Refuse to create WebView and self-abort cleanly so the user's front screen is NEVER locked up.
            if (!hasEverBeenOnRearDisplay && createdOnDisplay0 && !stoppedOnDisplay0) {
                DiagLog.log("PetAct", "安全熔断：抵达背屏 d=" + dId + " 前未完成 onStop(d=0)，自动退出以防止正面卡死！");
                try {
                    finishAndRemoveTask();
                } catch (Throwable ignored) {
                    finish();
                }
                return;
            }
            hasEverBeenOnRearDisplay = true;
            ensureRearLandscapeOrientation(dId);
            updateEcoSensorRegistration();
        }
        if (webView != null && webViewCreatedDisplayId == dId) {
            return;
        }
        if (webView != null) {
            try {
                rootLayout.removeView(webView);
                webView.destroy();
            } catch (Throwable ignored) {}
            webView = null;
        }

        DiagLog.log("Web", "在 displayId=" + dId + " 原生创建离线 WebView");
        webViewCreatedDisplayId = dId;
        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        webView.addJavascriptInterface(new PetJsBridge(), "AndroidPetBridge");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                if (cm != null && cm.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    DiagLog.log("WebErr", "L" + cm.lineNumber() + ": " + cm.message());
                }
                return super.onConsoleMessage(cm);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                int dId = currentDisplayId();
                int dState = ShizukuBridge.getDisplayState(RearPetActivity.this, dId);
                int curRot = ShizukuBridge.getDisplayRotation(RearPetActivity.this, dId);
                int offRot = getOfficialRearRotation();
                DiagLog.log("Web", "onPageFinished d=" + dId + " st=" + dState
                        + " r=" + curRot + "/" + offRot
                        + " view=" + view.getWidth() + "x" + view.getHeight());
                if (url != null && !url.startsWith("about:")) {
                    statusOverlay.setVisibility(View.GONE);
                    if (!isFrontFullscreenMode() && dId != 0) {
                        pushRearRotationDeltaToJs(dId);
                        if (currentEcoBlack) {
                            view.evaluateJavascript("window.setPetEcoBlack && window.setPetEcoBlack(true);", null);
                        }
                    }
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && !request.isForMainFrame()) return;
                String desc = error != null ? (error.getErrorCode() + ":" + error.getDescription()) : "unknown";
                DiagLog.log("Web", "onReceivedError " + desc);
                handler.removeCallbacks(retryRunnable);
                statusOverlay.setText("本地桌宠加载异常，正在重试...\n（点击立即重试）");
                statusOverlay.setVisibility(View.VISIBLE);
                handler.postDelayed(retryRunnable, 2000);
            }
        });

        rootLayout.addView(webView, 0, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        statusOverlay.setVisibility(View.VISIBLE);
        statusOverlay.setText("正在加载背屏桌宠...");
        webView.loadUrl(OFFLINE_URL);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        ensureWebViewReadyIfOnTargetDisplay();
    }

    private void logLifecycle(String event) {
        int dId = currentDisplayId();
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        int dpi = getResources().getDisplayMetrics().densityDpi;
        DiagLog.log("PetAct", getClass().getSimpleName() + "." + event
                + " displayId=" + dId + " size=" + w + "x" + h + " dpi=" + dpi
                + " taskId=" + getTaskId());
    }

    private int getOfficialRearRotation() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt("official_rear_rot", -1);
    }

    private int rotationToScreenOrientation(boolean naturalIsPortrait, int rot) {
        if (naturalIsPortrait) {
            switch (rot) {
                case 0: return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                case 1: return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                case 2: return ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
                case 3: return ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
                default: return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
            }
        } else {
            switch (rot) {
                case 0: return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                case 1: return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                case 2: return ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
                case 3: return ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
                default: return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
            }
        }
    }

    private int computeRearRotationDeltaDeg(int dId) {
        if (isFrontFullscreenMode() || dId == 0 || !hasEverBeenOnRearDisplay) return 0;
        int curRot = ShizukuBridge.getDisplayRotation(this, dId);
        int offRot = getOfficialRearRotation();
        if (curRot >= 0 && offRot >= 0) {
            int delta = (offRot - curRot + 4) % 4;
            switch (delta) {
                case 1: return -90;
                case 2: return 180;
                case 3: return 90;
                default: return 0;
            }
        }
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        return (w > 0 && h > 0 && w < h) ? -90 : 0;
    }

    private void pushRearRotationDeltaToJs(int dId) {
        if (isFrontFullscreenMode() || dId == 0 || webView == null) return;
        int deg = computeRearRotationDeltaDeg(dId);
        webView.evaluateJavascript("window.setRearRotationDelta && window.setRearRotationDelta(" + deg + ");", null);
    }

    private void ensureRearLandscapeOrientation(int dId) {
        if (isFrontFullscreenMode() || dId == 0 || !hasEverBeenOnRearDisplay) return;
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        int curRot = ShizukuBridge.getDisplayRotation(this, dId);
        int offRot = getOfficialRearRotation();
        boolean natPort = ShizukuBridge.isDisplayNaturalPortrait(this, dId);

        if (offRot >= 0 && curRot >= 0 && curRot != offRot) {
            int targetOri = rotationToScreenOrientation(natPort, offRot);
            DiagLog.log("Pet", "背屏方向对齐 r=" + curRot + "->" + offRot + " (" + w + "x" + h + ")");
            try {
                setRequestedOrientation(targetOri);
            } catch (Throwable ignored) {}
        } else if (w > 0 && h > 0 && w < h) {
            int targetOri = (offRot >= 0)
                    ? rotationToScreenOrientation(natPort, offRot)
                    : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
            DiagLog.log("Pet", "背屏竖屏(" + w + "x" + h + ")，请求横屏");
            try {
                setRequestedOrientation(targetOri);
            } catch (Throwable ignored) {}
        }
        pushRearRotationDeltaToJs(dId);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateTrackedDisplayId();
        applyImmersive();
        logLifecycle("onConfigChanged");
        ensureWebViewReadyIfOnTargetDisplay();
        ensureRearLandscapeOrientation(currentDisplayId());
    }

    private void updateTrackedDisplayId() {
        if (isFrontFullscreenMode()) return;
        activeDisplayId = currentDisplayId();
    }

    @Override
    protected void onStart() {
        super.onStart();
        logLifecycle("onStart");
        abortIfBouncedToMainScreen("onStart");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!isFrontFullscreenMode()) {
            isVisible = true;
            updateTrackedDisplayId();
        }
        applyImmersive();
        ensureWebViewReadyIfOnTargetDisplay();
        if (!isFrontFullscreenMode() && !hasEverBeenOnRearDisplay && currentDisplayId() == 0) {
            handler.removeCallbacks(autoHideOnDisplay0Runnable);
            handler.postDelayed(autoHideOnDisplay0Runnable, 30);
        }
        if (webView != null) {
            webView.onResume();
            if (!isFrontFullscreenMode() && currentDisplayId() != 0 && !currentEcoBlack) {
                lastJsSuspended = false;
                setJsSuspended(false);
            }
        }
        logLifecycle("onResume");
    }

    @Override
    protected void onPause() {
        logLifecycle("onPause");
        if (!isFrontFullscreenMode()) {
            isVisible = false;
            if (hasEverBeenOnRearDisplay && currentDisplayId() != 0 && !isFinishing() && RearGuardService.isRunning) {
                RearGuardService.notifyRearPetLostTop();
            }
        }
        if (isFrontFullscreenMode() && webView != null) {
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (!isFrontFullscreenMode() && currentDisplayId() == 0) {
            stoppedOnDisplay0 = true;
            DiagLog.log("PetAct", "onStop(d=0) 已完成，主屏图层已解绑");
        }
        logLifecycle("onStop");
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        logLifecycle("onDestroy");
        handler.removeCallbacks(autoHideOnDisplay0Runnable);
        handler.removeCallbacks(frontStuckWatchdog);
        handler.removeCallbacks(faceDownSleepRunnable);
        if (sensorManager != null && ecoSensorRegistered) {
            try { sensorManager.unregisterListener(ecoAccelListener); } catch (Throwable ignored) {}
            ecoSensorRegistered = false;
        }
        if (currentEcoBlack) {
            currentEcoBlack = false;
            RearGuardService.notifyEcoSleepState(false);
        }
        if (displayManager != null) {
            try { displayManager.unregisterDisplayListener(rearDisplayListener); } catch (Throwable ignored) {}
            displayManager = null;
        }
        if (!isFrontFullscreenMode()) {
            isVisible = false;
            activeDisplayId = -1;
            if (rearInstanceRef.get() == this) {
                rearInstanceRef = new WeakReference<>(null);
            }
        } else if (frontInstanceRef.get() == this) {
            frontInstanceRef = new WeakReference<>(null);
        }
        handler.removeCallbacks(retryRunnable);
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            if (abortIfBouncedToMainScreen("focus")) return;
            applyImmersive();
            if (!isFrontFullscreenMode() && currentDisplayId() != 0 && !currentEcoBlack) {
                lastJsSuspended = false;
                setJsSuspended(false);
            }
        }
    }

    private void applyImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController ctrl = getWindow().getInsetsController();
            if (ctrl != null) {
                ctrl.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                ctrl.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            //noinspection deprecation
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        }
    }
}
