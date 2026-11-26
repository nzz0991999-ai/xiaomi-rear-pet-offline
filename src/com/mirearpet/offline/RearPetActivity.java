package com.mirearpet.offline;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
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
    private int webViewCreatedDisplayId = -1;
    private boolean hasEverBeenOnRearDisplay = false;
    private boolean stoppedOnDisplay0 = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
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
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!isFrontFullscreenMode()) {
            rearInstanceRef = new WeakReference<>(this);
        } else {
            frontInstanceRef = new WeakReference<>(this);
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        } else {
            // On Display 0 staging: NEVER touch front lockscreen and NEVER intercept/freeze front touches!
            //noinspection deprecation
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        }
    }

    private void ensureWebViewReadyIfOnTargetDisplay() {
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
            if (hasEverBeenOnRearDisplay) {
                DiagLog.log("PetAct", "背屏实例被退回主屏(d=0)，自动退出以避免遮挡前景应用");
                try {
                    moveTaskToBack(true);
                } catch (Throwable ignored) {}
                try {
                    finishAndRemoveTask();
                } catch (Throwable ignored) {
                    finish();
                }
                return;
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
            if (!hasEverBeenOnRearDisplay && !stoppedOnDisplay0) {
                DiagLog.log("PetAct", "提示：抵达背屏 d=" + dId + " 时 stoppedOnDisplay0=false");
            }
            hasEverBeenOnRearDisplay = true;
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

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                DiagLog.log("Web", "onPageFinished d=" + currentDisplayId()
                        + " view=" + view.getWidth() + "x" + view.getHeight());
                if (url != null && !url.startsWith("about:")) {
                    statusOverlay.setVisibility(View.GONE);
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

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateTrackedDisplayId();
        applyImmersive();
        logLifecycle("onConfigChanged");
        ensureWebViewReadyIfOnTargetDisplay();
    }

    private void updateTrackedDisplayId() {
        if (isFrontFullscreenMode()) return;
        activeDisplayId = currentDisplayId();
    }

    @Override
    protected void onStart() {
        super.onStart();
        logLifecycle("onStart");
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
            handler.postDelayed(autoHideOnDisplay0Runnable, 120);
        }
        if (webView != null) webView.onResume();
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
        if (hasFocus) applyImmersive();
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
