package com.magpie.android;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    public static final String ACTION_SHOW_LOG = "com.magpie.android.SHOW_LOG";
    public static final String ACTION_FLOAT = "com.magpie.android.FLOAT";
    public static final String ACTION_RESTART = "com.magpie.android.RESTART";
    private static final String PERMISSION_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK";
    private static final int POLLS_BEFORE_GIVING_UP = 90;
    private static final long POLL_MS = 700;
    private static final int REQUEST_LOCAL_NETWORK = 2;
    private static final int REQUEST_NOTIFICATIONS = 1;
    private static final int CPU_SECONDS = 30;

    private TextView addressText;
    private View appBar;
    private TextView appTitle;
    private ImageView logoBadge;
    private ImageButton menuButton;
    private View overlay;
    private Button overlayButton;
    private View overlayCard;
    private View overlayMark;
    private View overlayProgress;
    private TextView overlayText;
    private boolean pageLoaded;
    private View root;
    private View statusDot;
    private TextView statusText;
    private int waits;
    private WebView web;
    private View webHost;
    private String magpieVersion = "";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService cli = Executors.newCachedThreadPool();

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            io.execute(() -> {
                final boolean up = MagpieGateway.portOpen("127.0.0.1", MagpiePrefs.webPort(MainActivity.this), 400);
                handler.post(() -> polled(up));
            });
            handler.postDelayed(this, POLL_MS);
        }
    };

    private void polled(boolean up) {
        if (isDestroyed()) {
            return;
        }
        status(up);
        if (!up && !Updater.installed(this)) {
            // first run: magpie is being downloaded
            int pc = Updater.percent;
            String st = Updater.stage.isEmpty() ? getString(R.string.dl_checking) : Updater.stage;
            if (pc == -2) {
                overlay(st, getString(R.string.dl_failed_hint), true);
            } else {
                overlay(pc >= 0 ? st + "  " + pc + "%" : st, getString(R.string.dl_first_hint), false);
            }
            waits = 0;
            return;
        }
        if (up && magpieVersion.isEmpty()) {
            refreshVersion();
        }
        if (!up && MagpieGateway.userStopped) {
            waits = 0;
            if (pageLoaded) {
                pageLoaded = false;
                web.loadUrl("about:blank");
            }
            stoppedOverlay();
            return;
        }
        if (up) {
            waits = 0;
            if (pageLoaded) {
                return;
            }
            pageLoaded = true;
            web.loadUrl(MagpieGateway.pageUrl(this));
            return;
        }
        waits++;
        if (waits == POLLS_BEFORE_GIVING_UP) {
            overlay(getString(R.string.overlay_failed), getString(R.string.overlay_hint), true);
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        CrashLog.install(this);
        if (state == null) handler.postDelayed(this::autoCheckShell, 2500L);
        setContentView(R.layout.activity_main);
        web = findViewById(R.id.web);
        root = findViewById(R.id.root);
        appBar = findViewById(R.id.appBar);
        webHost = findViewById(R.id.webHost);
        overlay = findViewById(R.id.overlay);
        overlayCard = findViewById(R.id.overlayCard);
        overlayProgress = findViewById(R.id.overlayProgress);
        overlayMark = findViewById(R.id.overlayMark);
        logoBadge = findViewById(R.id.logoBadge);
        menuButton = findViewById(R.id.menuButton);
        appTitle = findViewById(R.id.appTitle);
        statusDot = findViewById(R.id.statusDot);
        statusText = findViewById(R.id.statusText);
        addressText = findViewById(R.id.addressText);
        overlayText = findViewById(R.id.overlayText);
        overlayButton = findViewById(R.id.overlayButton);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom));
            return WindowInsets.CONSUMED;
        });
        logoBadge.setClickable(true);
        logoBadge.setFocusable(true);
        logoBadge.setOnClickListener(v -> floatAndGoHome());
        overlayButton.setOnClickListener(v -> {
            waits = 0;
            pageLoaded = false;
            Updater.percent = -1;
            Updater.stage = "";
            overlay(getString(R.string.status_starting), null, false);
            MagpieService.start(this);
        });
        overlayButton.setOnLongClickListener(v -> { showUpdates(); return true; });
        menuButton.setOnClickListener(v -> showMenu());
        addressText.setOnClickListener(v -> copyGateway());
        findViewById(R.id.statusChip).setOnClickListener(this::showGatewayCapsule);
        addressText.setOnLongClickListener(v -> { showAddresses(); return true; });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        web.setBackgroundColor(getColor(R.color.app_bg));
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        CookieManager.getInstance().setAcceptCookie(true);
        WebFiles.attach(this, web);
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, android.webkit.ValueCallback<Uri[]> cb, FileChooserParams params) {
                return WebFiles.choose(MainActivity.this, cb, params, null);
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, android.webkit.JsResult result) {
                new Ask().setTitle("magpie").setMessage(message)
                        .setPositiveButton("好", (x, y) -> result.confirm())
                        .setOnCancelListener(x -> result.confirm()).show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, android.webkit.JsResult result) {
                new Ask().setTitle("magpie").setMessage(message)
                        .setPositiveButton("确定", (x, y) -> result.confirm())
                        .setNegativeButton("取消", (x, y) -> result.cancel())
                        .setOnCancelListener(x -> result.cancel()).show();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String def, android.webkit.JsPromptResult result) {
                final EditText e = new EditText(MainActivity.this);
                e.setSingleLine(true);
                e.setText(def == null ? "" : def);
                e.setTextColor(getColor(R.color.text_primary));
                e.setBackground(getDrawable(R.drawable.bg_field_group));
                e.setPadding(dp(14), dp(12), dp(14), dp(12));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = dp(6);
                e.setLayoutParams(lp);
                new Ask().setTitle("magpie").setMessage(message).setView(e)
                        .setPositiveButton("确定", (x, y) -> result.confirm(e.getText().toString()))
                        .setNegativeButton("取消", (x, y) -> result.cancel())
                        .setOnCancelListener(x -> result.cancel()).show();
                return true;
            }

            @Override
            public boolean onJsBeforeUnload(WebView view, String url, String message, android.webkit.JsResult result) {
                result.confirm();
                return true;
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean dialog, boolean userGesture, Message msg) {
                final WebView popup = new WebView(MainActivity.this);
                popup.getSettings().setJavaScriptEnabled(false);
                final boolean[] spent = {false};
                handler.postDelayed(() -> {
                    if (!spent[0]) {
                        spent[0] = true;
                        popup.destroy();
                    }
                }, 8000L);
                popup.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                        if (!spent[0]) {
                            spent[0] = true;
                            openExternal(r.getUrl().toString());
                            handler.post(v::destroy);
                        }
                        return true;
                    }
                });
                ((WebView.WebViewTransport) msg.obj).setWebView(popup);
                msg.sendToTarget();
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageCommitVisible(WebView view, String url) {
                Skin.paint(MainActivity.this, web, false);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                CookieManager.getInstance().flush();
                Skin.paint(MainActivity.this, web, false);
                view.evaluateJavascript(WebFiles.HOOK_JS, null);
                hideOverlay();
            }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                CrashLog.write(MainActivity.this, "main page renderer gone (crashed=" + detail.didCrash() + ")", new RuntimeException("renderer gone"));
                handler.post(MainActivity.this::recreate);
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) {
                    pageLoaded = false;
                    overlay(getString(R.string.overlay_failed), err == null ? null : String.valueOf(err.getDescription()), true);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                if (!req.isForMainFrame()) {
                    return false;
                }
                String url = req.getUrl().toString();
                if (isMagpiePage(url)) {
                    return false;
                }
                openExternal(url);
                return true;
            }
        });
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, this::handleBack);
        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQUEST_NOTIFICATIONS);
        }
        showAddress();
        requestLocalNetworkPermissionIfNeeded();
        MagpieService.start(this);
        refreshVersion();
        autoCheck();
        handleAction(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAction(intent);
    }

    /** launcher shortcuts and notification taps */
    private void handleAction(Intent i) {
        String a = i == null ? null : i.getAction();
        if (a == null) return;
        i.setAction(Intent.ACTION_MAIN);
        if (ACTION_FLOAT.equals(a)) {
            handler.postDelayed(this::floatAndGoHome, 300L);
        } else if (ACTION_RESTART.equals(a)) {
            restart();
            toast("正在重启网关…");
        } else if (ACTION_SHOW_LOG.equals(a)) {
            handler.postDelayed(this::showLog, 300L);
        }
    }

    private void refreshVersion() {
        cli.execute(() -> {
            File bin = MagpieGateway.binary(this);
            if (bin == null) return;
            final String ver = MagpieGateway.versionOf(this, bin);
            if (!ver.isEmpty()) MagpiePrefs.saveInstalledVersion(this, ver);
            handler.post(() -> magpieVersion = ver);
        });
    }

    /** at most every 6 h: say when a newer magpie is out */
    private void autoCheck() {
        if (System.currentTimeMillis() - MagpiePrefs.lastUpdateCheck(this) < 6 * 3600_000L || !Updater.installed(this)) {
            return;
        }
        cli.execute(() -> {
            try {
                Updater.Release r = Updater.latest(this);
                MagpiePrefs.saveLastUpdateCheck(this, System.currentTimeMillis());
                File bin = MagpieGateway.binary(this);
                String cur = bin == null ? "" : MagpieGateway.versionOf(this, bin);
                if (!cur.isEmpty() && MagpieGateway.compareVersions(r.version, cur) > 0) {
                    handler.post(() -> toast(getString(R.string.upd_available_toast, r.version)));
                }
            } catch (IOException ignored) {
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(addressTick);
        handler.post(addressTick);
        if (pendingFloatHome) {
            pendingFloatHome = false;
            if (Settings.canDrawOverlays(this)) {
                handler.postDelayed(this::floatAndGoHome, 150L);
            }
        }
        showAddress();
        handler.postDelayed(poll, 100L);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(poll);
        handler.removeCallbacks(addressTick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(poll);
        io.shutdownNow();
        cli.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        handleBack();
    }

    /** Back: step back inside the magpie page if there is a real previous page, otherwise press twice within 2 s to exit. */
    private void handleBack() {
        if (web != null && web.canGoBack()) {
            android.webkit.WebBackForwardList list = web.copyBackForwardList();
            int i = list.getCurrentIndex();
            String cur = list.getCurrentItem() == null ? "" : stripHash(list.getCurrentItem().getUrl());
            String prev = i > 0 ? stripHash(list.getItemAtIndex(i - 1).getUrl()) : "";
            if (!prev.isEmpty() && !prev.equals(cur) && isMagpiePage(prev)) {
                lastBack = 0;
                web.goBack();
                return;
            }
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastBack < 2000L) {
            lastBack = 0;
            finishAndRemoveTask();
        } else {
            lastBack = now;
            toast("再按一次退出（网关继续在后台运行）");
        }
    }

    private static String stripHash(String u) {
        if (u == null) return "";
        int h = u.indexOf('#');
        return h >= 0 ? u.substring(0, h) : u;
    }

    private long lastBack;

    @Override
    public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        boolean night = (config.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.setSystemBarsAppearance(night ? 0 : 24, 24);
        }
        paintChrome();
        status(MagpieGateway.isRunning());
        showAddress();
    }

    /** Address the bar shows and copies: Cloudflare, then Tailscale, then LAN / loopback. */
    private String preferredBase() {
        String cf = Tunnels.cloudflareUrl();
        if (!cf.isEmpty()) return cf;
        String ts = Tunnels.tailscaleIp();
        if (!ts.isEmpty()) return "http://" + ts + ":" + MagpiePrefs.gatewayPort(this);
        if (MagpiePrefs.lan(this)) {
            String ip = lanIp();
            if (ip != null) return "http://" + ip + ":" + MagpiePrefs.gatewayPort(this);
        }
        return MagpieGateway.gatewayUrl(this);
    }

    private final Runnable addressTick = new Runnable() {
        @Override public void run() {
            showAddress();
            handler.postDelayed(this, 2000L);
        }
    };

    private void showAddress() {
        String cf = Tunnels.cloudflareUrl();
        String ts = Tunnels.tailscaleIp();
        if (!cf.isEmpty()) {
            addressText.setText(cf.replaceFirst("^https?://", ""));
            addressText.setTextColor(0xFFF38020);
            return;
        }
        if (!ts.isEmpty()) {
            addressText.setText(ts + ":" + MagpiePrefs.gatewayPort(this));
            addressText.setTextColor(0xFF5B7CFA);
            return;
        }
        if (MagpiePrefs.lan(this)) {
            String ip = lanIp();
            addressText.setText((ip == null ? "0.0.0.0" : ip) + ":" + MagpiePrefs.gatewayPort(this));
            addressText.setTextColor(0xFFE0861A);
        } else {
            addressText.setText("127.0.0.1:" + MagpiePrefs.gatewayPort(this));
            addressText.setTextColor(getColor(R.color.accent));
        }
    }

    /** the phone's LAN IPv4 (192.168.x.x preferred, then 10.x / 172.16-31.x), or null */
    private String lanIp() {
        String best = null;
        int bestRank = 99;
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName();
                boolean tunnel = name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("ipsec");
                for (java.net.InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof java.net.Inet4Address) || a.isLoopbackAddress()) continue;
                    String ip = a.getHostAddress();
                    int rank = ip.startsWith("192.168.") ? 0 : a.isSiteLocalAddress() ? 1 : 3;
                    if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan")) rank -= 0; else rank += 1;
                    if (tunnel) rank += 5;
                    if (rank < bestRank) { bestRank = rank; best = ip; }
                }
            }
        } catch (Exception ignored) {
        }
        return best;
    }

    // ------------------------------------------------------------------ gateway capsule

    private View capsuleButton(int icon, String text, int color, Runnable action) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        android.graphics.drawable.GradientDrawable mask = new android.graphics.drawable.GradientDrawable();
        mask.setColor(0xffffffff);
        mask.setCornerRadius(dp(999));
        b.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(getColor(R.color.menu_ripple)), null, mask));
        b.setPadding(dp(14), dp(9), dp(16), dp(9));
        b.setClickable(true);
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setImageTintList(ColorStateList.valueOf(color));
        b.addView(iv, new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setTextColor(color);
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.leftMargin = dp(7);
        b.addView(t, tl);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    /** Tapping the 运行中 status opens a small capsule with 重启 / 停止 (or 启动 when stopped). */
    private void showGatewayCapsule(View anchor) {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(getColor(R.color.app_surface));
        bg.setCornerRadius(dp(999));
        bg.setStroke(Math.max(1, dp(1)), getColor(R.color.app_line));
        pill.setPadding(dp(4), dp(4), dp(4), dp(4));
        final android.widget.PopupWindow pw = new android.widget.PopupWindow(pill, -2, -2, true);
        boolean running = MagpieGateway.isRunning();
        if (running) {
            pill.addView(capsuleButton(R.drawable.ic_restart, "重启", getColor(R.color.accent), () -> { pw.dismiss(); restart(); }));
            View div = new View(this);
            div.setBackgroundColor(getColor(R.color.app_line));
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(Math.max(1, dp(1)), dp(20));
            dl.leftMargin = dp(2);
            dl.rightMargin = dp(2);
            pill.addView(div, dl);
            pill.addView(capsuleButton(R.drawable.ic_power, "停止", getColor(R.color.status_error), () -> { pw.dismiss(); toggleGateway(); }));
        } else {
            pill.addView(capsuleButton(R.drawable.ic_power, "启动网关", getColor(R.color.status_ok), () -> { pw.dismiss(); toggleGateway(); }));
        }
        pw.setBackgroundDrawable(bg);
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(8));
        pw.setAnimationStyle(android.R.style.Animation_Dialog);
        pw.showAsDropDown(anchor, -dp(4), dp(6));
    }

    private void status(boolean up) {
        statusDot.setBackgroundTintList(ColorStateList.valueOf(getColor(up ? R.color.status_ok : R.color.status_off)));
        statusText.setText(up ? R.string.status_running : MagpieGateway.userStopped ? R.string.status_stopped : R.string.status_starting);
    }

    /** Shown while the user has stopped the gateway: no spinner, no error mark, one button to start it. */
    private void stoppedOverlay() {
        overlay.setVisibility(View.VISIBLE);
        overlayProgress.setVisibility(View.GONE);
        overlayMark.setVisibility(View.GONE);
        overlayText.setText("网关已停止\n点下面的按钮，或点顶栏的状态重新启动。");
        overlayButton.setText("启动网关");
        overlayButton.setVisibility(View.VISIBLE);
    }

    private void overlay(String text, String hint, boolean failed) {
        overlayButton.setText(R.string.retry);
        overlay.setVisibility(View.VISIBLE);
        overlayProgress.setVisibility(failed ? View.GONE : View.VISIBLE);
        overlayMark.setVisibility(failed ? View.VISIBLE : View.GONE);
        overlayText.setText(hint != null ? text + "\n" + hint : text);
        overlayButton.setVisibility(failed ? View.VISIBLE : View.GONE);
    }

    private void hideOverlay() {
        overlay.setVisibility(View.GONE);
    }

    private void paintChrome() {
        root.setBackground(getDrawable(R.drawable.bg_app_root));
        appBar.setBackground(getDrawable(R.drawable.bg_app_bar));
        logoBadge.setBackground(getDrawable(R.drawable.bg_logo_badge));
        appTitle.setTextColor(getColor(R.color.text_primary));
        statusText.setTextColor(getColor(R.color.text_secondary));
        statusDot.setBackground(getDrawable(R.drawable.status_dot));
        addressText.setTextColor(getColor(R.color.accent));
        addressText.setBackground(getDrawable(R.drawable.bg_address_chip));
        menuButton.setBackground(getDrawable(R.drawable.bg_icon_button));
        menuButton.setImageTintList(ColorStateList.valueOf(getColor(R.color.text_primary)));
        webHost.setBackground(getDrawable(R.drawable.bg_web_sheet));
        web.setBackgroundColor(getColor(R.color.app_bg));
        overlay.setBackgroundColor(getColor(R.color.app_bg));
        overlayCard.setBackground(getDrawable(R.drawable.bg_overlay_card));
        ((ProgressBar) overlayProgress).setIndeterminateTintList(ColorStateList.valueOf(getColor(R.color.accent)));
        overlayText.setTextColor(getColor(R.color.text_primary));
        overlayButton.setBackground(getDrawable(R.drawable.bg_button_primary));
        overlayButton.setTextColor(getColor(R.color.text_on_accent));
    }

    // ------------------------------------------------------------------ sheets

    private Dialog sheet(int layout, int rootId) {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View v = LayoutInflater.from(this).inflate(layout, null);
        d.setContentView(v);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.addFlags(2);
            w.setDimAmount(0.42f);
            w.setLayout(-1, -2);
            w.setGravity(Gravity.BOTTOM);
            w.setWindowAnimations(R.style.BottomSheetAnimation);
            w.setSoftInputMode(16);
        }
        View inner = v.findViewById(rootId);
        if (inner == null) {
            inner = v;
        }
        inner.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(),
                    Math.max(insets.getInsets(WindowInsets.Type.navigationBars()).bottom, insets.getInsets(WindowInsets.Type.ime()).bottom));
            return WindowInsets.CONSUMED;
        });
        return d;
    }

    private Dialog plainSheet(CharSequence title, CharSequence subtitle) {
        Dialog d = sheet(R.layout.dialog_sheet, R.id.sheetRoot);
        ((TextView) d.findViewById(R.id.sheetTitle)).setText(title);
        TextView sub = d.findViewById(R.id.sheetSubtitle);
        if (subtitle == null || subtitle.length() == 0) {
            sub.setVisibility(View.GONE);
        } else {
            sub.setText(subtitle);
        }
        return d;
    }

    private View addSheetItem(LinearLayout parent, int icon, CharSequence title, CharSequence subtitle, Runnable action) {
        View v = LayoutInflater.from(this).inflate(R.layout.sheet_item_action, parent, false);
        ((ImageView) v.findViewById(R.id.sheetItemIcon)).setImageResource(icon);
        ((TextView) v.findViewById(R.id.sheetItemTitle)).setText(title);
        TextView sub = v.findViewById(R.id.sheetItemSubtitle);
        if (subtitle == null || subtitle.length() == 0) {
            sub.setVisibility(View.GONE);
        } else {
            sub.setText(subtitle);
            sub.setVisibility(View.VISIBLE);
        }
        v.setOnClickListener(x -> action.run());
        parent.addView(v);
        return v;
    }

    private void addSheetItem(LinearLayout parent, int icon, int title, CharSequence subtitle, Runnable action) {
        addSheetItem(parent, icon, getString(title), subtitle, action);
    }

    private void showMenu() {
        final Dialog d = plainSheet(getString(R.string.menu),
                getString(R.string.menu_subtitle, "127.0.0.1:" + MagpiePrefs.gatewayPort(this), "127.0.0.1:" + MagpiePrefs.webPort(this)));
        LinearLayout items = d.findViewById(R.id.sheetItems);
        ImageView gh = d.findViewById(R.id.sheetAction);
        gh.setImageResource(R.drawable.ic_github);
        gh.setContentDescription("GitHub 项目主页");
        gh.setVisibility(View.VISIBLE);
        gh.setOnClickListener(v -> { d.dismiss(); openExternal(ShellUpdater.HOME); });
        addSheetItem(items, R.drawable.ic_refresh, R.string.menu_reload, getString(R.string.menu_reload_hint), () -> { d.dismiss(); reload(); });
        addSheetItem(items, R.drawable.ic_open_external, R.string.menu_open_browser, getString(R.string.menu_open_browser_hint), () -> { d.dismiss(); openExternal(MagpieGateway.pageUrl(this)); });
        addSheetItem(items, R.drawable.ic_float, FloatService.isShowing() ? R.string.menu_float_hide : R.string.menu_float_show,
                getString(R.string.menu_float_hint), () -> { d.dismiss(); toggleFloat(); });
        addSheetItem(items, R.drawable.ic_settings_sliders, R.string.menu_float_settings,
                getString(R.string.menu_float_settings_hint, MagpiePrefs.floatAlpha(this), MagpiePrefs.floatWidth(this), MagpiePrefs.floatHeight(this)),
                () -> { d.dismiss(); showFloatSettings(); });
        addSheetItem(items, R.drawable.ic_globe, "远程访问", remoteHint(), () -> { d.dismiss(); showRemote(); });
        addSheetItem(items, R.drawable.ic_toolbox, R.string.menu_tools,
                getString(R.string.menu_tools_hint, magpieVersion.isEmpty() ? "?" : magpieVersion), () -> { d.dismiss(); showTools(); });
        addSheetItem(items, R.drawable.ic_settings_sliders, R.string.menu_settings, getString(R.string.menu_settings_hint), () -> { d.dismiss(); showSettings(); });
        addSheetItem(items, R.drawable.ic_storage, "空间管理", "查看各类文件占用的空间，按大小排序，可分类清除", () -> { d.dismiss(); showStorage(); });
        addSheetItem(items, R.drawable.ic_terminal, R.string.menu_log, getString(R.string.menu_log_hint), () -> { d.dismiss(); showLog(); });
        addSheetItem(items, R.drawable.ic_download, R.string.menu_update,
                getString(R.string.menu_update_hint, magpieVersion.isEmpty() ? "?" : magpieVersion, ShellUpdater.current(this)), () -> { d.dismiss(); showUpdates(); });
        d.show();
    }

    // ------------------------------------------------------------------ floating window

    private boolean pendingFloatHome = false;

    /** Logo tap: open the floating window (if not already open) and return to the launcher. */
    private void floatAndGoHome() {
        if (!Settings.canDrawOverlays(this)) {
            pendingFloatHome = true;
            toast(getString(R.string.float_need_permission));
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            } catch (ActivityNotFoundException ignored) {
                pendingFloatHome = false;
            }
            return;
        }
        MagpiePrefs.saveFloatMini(this, true);
        FloatService.show(this);
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(home);
        } catch (ActivityNotFoundException e) {
            moveTaskToBack(true);
        }
    }

    private void toggleFloat() {
        if (FloatService.isShowing()) {
            FloatService.hide(this);
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            toast(getString(R.string.float_need_permission));
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            } catch (ActivityNotFoundException ignored) {
            }
            return;
        }
        MagpiePrefs.saveFloatMini(this, false);
        FloatService.show(this);
        moveTaskToBack(true);
    }

    private void showFloatSettings() {
        final Dialog d = plainSheet(getString(R.string.menu_float_settings), getString(R.string.float_settings_subtitle));
        LinearLayout items = d.findViewById(R.id.sheetItems);
        final SeekBar a = slider(items, R.string.float_alpha, 20, 100, MagpiePrefs.floatAlpha(this));
        final SeekBar w = slider(items, R.string.float_width, 30, 100, MagpiePrefs.floatWidth(this));
        final SeekBar h = slider(items, R.string.float_height, 20, 95, MagpiePrefs.floatHeight(this));
        SeekBar.OnSeekBarChangeListener l = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                ((TextView) ((View) s.getTag())).setText(p + "%");
                if (fromUser) {
                    MagpiePrefs.saveFloatLook(MainActivity.this, a.getProgress(), w.getProgress(), h.getProgress());
                    FloatService.apply(MainActivity.this);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        };
        a.setOnSeekBarChangeListener(l);
        w.setOnSeekBarChangeListener(l);
        h.setOnSeekBarChangeListener(l);
        final SeekBar bs = slider(items, R.string.bubble_size, 32, 96, MagpiePrefs.bubbleSize(this));
        final SeekBar ba = slider(items, R.string.bubble_alpha, 20, 100, MagpiePrefs.bubbleAlpha(this));
        final SeekBar bd = slider(items, R.string.bubble_delay, 1, 15, MagpiePrefs.bubbleHideDelay(this));
        ((TextView) ((View) bs.getTag())).setText(bs.getProgress() + "dp");
        ((TextView) ((View) bd.getTag())).setText(bd.getProgress() + "s");
        SeekBar.OnSeekBarChangeListener bl = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                ((TextView) ((View) s.getTag())).setText(p + (s == bs ? "dp" : s == bd ? "s" : "%"));
                if (fromUser) {
                    MagpiePrefs.saveBubbleLook(MainActivity.this, bs.getProgress(), ba.getProgress());
                    MagpiePrefs.saveBubbleHideDelay(MainActivity.this, bd.getProgress());
                    FloatService.apply(MainActivity.this);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        };
        bs.setOnSeekBarChangeListener(bl);
        ba.setOnSeekBarChangeListener(bl);
        bd.setOnSeekBarChangeListener(bl);
        switchRow(items, getString(R.string.bubble_snap), getString(R.string.bubble_snap_hint), MagpiePrefs.bubbleSnap(this), on -> {
            MagpiePrefs.saveBubbleSnap(this, on);
            FloatService.apply(this);
        });
        switchRow(items, getString(R.string.bubble_hide), getString(R.string.bubble_hide_hint), MagpiePrefs.bubbleHide(this), on -> {
            MagpiePrefs.saveBubbleHide(this, on);
            FloatService.apply(this);
        });
        switchRow(items, getString(R.string.float_lock), getString(R.string.float_settings_lock_hint), MagpiePrefs.floatLock(this), on -> {
            MagpiePrefs.saveFloatLock(this, on);
            FloatService.apply(this);
        });
        addSheetItem(items, R.drawable.ic_restart, R.string.float_reset, null, () -> {
            MagpiePrefs.saveFloatLook(this, 90, 90, 45);
            MagpiePrefs.saveFloatPos(this, 40, 220);
            MagpiePrefs.saveFloatLock(this, false);
            MagpiePrefs.saveBubbleLook(this, 36, 90);
            MagpiePrefs.saveBubbleHideDelay(this, 3);
            MagpiePrefs.saveBubblePos(this, -1, -1);
            bs.setProgress(36);
            ba.setProgress(90);
            bd.setProgress(3);
            a.setProgress(90);
            w.setProgress(90);
            h.setProgress(45);
            FloatService.apply(this);
        });
        addSheetItem(items, R.drawable.ic_float, FloatService.isShowing() ? R.string.menu_float_hide : R.string.menu_float_show, null,
                () -> { d.dismiss(); toggleFloat(); });
        d.show();
    }

    private SeekBar slider(LinearLayout parent, int label, int min, int max, int value) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(4), dp(6), dp(4));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(14);
        t.setTextColor(getColor(R.color.text_primary));
        row.addView(t, new LinearLayout.LayoutParams(dp(72), -2));
        SeekBar bar = new SeekBar(this);
        bar.setMin(min);
        bar.setMax(max);
        bar.setProgress(value);
        int accent = getColor(R.color.accent);
        bar.setProgressTintList(ColorStateList.valueOf(accent));
        bar.setThumbTintList(ColorStateList.valueOf(accent));
        row.addView(bar, new LinearLayout.LayoutParams(0, dp(44), 1f));
        TextView val = new TextView(this);
        val.setText(value + "%");
        val.setTextSize(13);
        val.setGravity(Gravity.END);
        val.setTextColor(getColor(R.color.text_secondary));
        row.addView(val, new LinearLayout.LayoutParams(dp(48), -2));
        bar.setTag(val);
        parent.addView(row);
        return bar;
    }

    private interface OnToggle { void toggled(boolean on); }

    private Switch switchRow(LinearLayout parent, String title, String hint, boolean checked, OnToggle cb) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        row.setBackground(getDrawable(R.drawable.bg_switch_card));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTextColor(getColor(R.color.text_primary));
        texts.addView(t);
        if (hint != null) {
            TextView hv = new TextView(this);
            hv.setText(hint);
            hv.setTextSize(12);
            hv.setTextColor(getColor(R.color.text_secondary));
            texts.addView(hv);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setThumbTintList(getColorStateList(R.color.switch_thumb));
        sw.setTrackTintList(getColorStateList(R.color.switch_track));
        sw.setOnCheckedChangeListener((b, on) -> cb.toggled(on));
        row.addView(sw);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(6);
        parent.addView(row, lp);
        return sw;
    }

    // ------------------------------------------------------------------ updates

    /** One update target (内核 / 外壳) drawn as a card: icon, name, version, status pill, notes, action buttons, small links. */
    private final class UpdCard {
        final LinearLayout root, actions, links;
        final TextView sub, pill, notes, status;

        UpdCard(LinearLayout parent, int icon, CharSequence name, CharSequence version) {
            root = new LinearLayout(MainActivity.this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackground(getDrawable(R.drawable.bg_switch_card));
            root.setPadding(dp(16), dp(14), dp(16), dp(14));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(10);
            parent.addView(root, lp);

            LinearLayout top = new LinearLayout(MainActivity.this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            root.addView(top);
            ImageView ic = new ImageView(MainActivity.this);
            ic.setImageResource(icon);
            ic.setBackground(getDrawable(R.drawable.bg_icon_tile));
            ic.setPadding(dp(9), dp(9), dp(9), dp(9));
            top.addView(ic, new LinearLayout.LayoutParams(dp(40), dp(40)));
            LinearLayout texts = new LinearLayout(MainActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
            tl.leftMargin = dp(12);
            top.addView(texts, tl);
            TextView t = new TextView(MainActivity.this);
            t.setText(name);
            t.setTextSize(16);
            t.setTextColor(getColor(R.color.text_primary));
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            texts.addView(t);
            sub = new TextView(MainActivity.this);
            sub.setText(version);
            sub.setTextSize(12.5f);
            sub.setTextColor(getColor(R.color.text_muted));
            texts.addView(sub);
            pill = new TextView(MainActivity.this);
            pill.setTextSize(12);
            pill.setPadding(dp(10), dp(4), dp(10), dp(4));
            pill.setBackground(getDrawable(R.drawable.bg_address_chip));
            top.addView(pill);

            status = new TextView(MainActivity.this);
            status.setTextSize(13);
            status.setTextColor(getColor(R.color.text_secondary));
            status.setPadding(0, dp(10), 0, 0);
            status.setVisibility(View.GONE);
            root.addView(status);

            notes = new TextView(MainActivity.this);
            notes.setTextSize(13);
            notes.setLineSpacing(dp(2), 1f);
            notes.setTextColor(getColor(R.color.text_secondary));
            notes.setBackground(getDrawable(R.drawable.bg_log_box));
            notes.setPadding(dp(12), dp(10), dp(12), dp(10));
            notes.setMaxLines(10);
            notes.setEllipsize(android.text.TextUtils.TruncateAt.END);
            notes.setTextIsSelectable(true);
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-1, -2);
            nl.topMargin = dp(10);
            notes.setLayoutParams(nl);
            notes.setVisibility(View.GONE);
            root.addView(notes);

            actions = new LinearLayout(MainActivity.this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, -2);
            al.topMargin = dp(12);
            actions.setLayoutParams(al);
            actions.setVisibility(View.GONE);
            root.addView(actions);

            links = new LinearLayout(MainActivity.this);
            links.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2);
            ll.topMargin = dp(6);
            links.setLayoutParams(ll);
            root.addView(links);
            state("检查中", false);
        }

        void state(CharSequence text, boolean highlight) {
            pill.setText(text);
            pill.setTextColor(getColor(highlight ? R.color.accent : R.color.text_secondary));
        }

        void say(CharSequence text) {
            status.setText(text);
            status.setVisibility(text == null || text.length() == 0 ? View.GONE : View.VISIBLE);
        }

        void showNotes(CharSequence text) {
            notes.setText(text);
            notes.setVisibility(text == null || text.length() == 0 ? View.GONE : View.VISIBLE);
        }

        TextView button(CharSequence text, boolean primary, Runnable r) {
            TextView b = new TextView(MainActivity.this);
            b.setText(text);
            b.setTextSize(14.5f);
            b.setGravity(Gravity.CENTER);
            b.setSingleLine(true);
            b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            b.setTextColor(getColor(primary ? R.color.text_on_accent : R.color.text_primary));
            b.setBackground(getDrawable(primary ? R.drawable.bg_button_primary : R.drawable.bg_button_secondary));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), primary ? 1.2f : 1f);
            if (actions.getChildCount() > 0) lp.leftMargin = dp(10);
            actions.addView(b, lp);
            b.setOnClickListener(v -> r.run());
            return b;
        }

        TextView link(CharSequence text, Runnable r) {
            TextView l = new TextView(MainActivity.this);
            l.setText(text);
            l.setTextSize(13);
            l.setTextColor(getColor(R.color.accent));
            l.setPadding(0, dp(8), dp(18), dp(4));
            l.setOnClickListener(v -> r.run());
            links.addView(l);
            return l;
        }
    }

    private void showUpdates() {
        final Dialog d = plainSheet("检查更新", "内核是 magpie 命令行，外壳是这个安卓应用，两者各自更新。");
        LinearLayout items = d.findViewById(R.id.sheetItems);

        // ---- 内核
        final UpdCard k = new UpdCard(items, R.drawable.ic_terminal, "内核 · magpie",
                "当前 " + (magpieVersion.isEmpty() ? "—" : magpieVersion) + " · 来自 usemagpie.ai / GitHub");
        final Updater.Release[] kFound = {null};
        k.button("更新内核", true, () -> {
            Updater.Release r = kFound[0];
            if (r == null || Updater.busy) return;
            k.actions.setEnabled(false);
            k.actions.setAlpha(0.5f);
            cli.execute(() -> {
                try {
                    Updater.install(this, r, (stage, pc) -> handler.post(() -> k.say(pc >= 0 ? stage + "  " + pc + "%" : stage)));
                    MagpieGateway.stop();
                    handler.post(() -> {
                        k.say(getString(R.string.upd_done, r.version));
                        k.state("已更新", true);
                        k.actions.setVisibility(View.GONE);
                        magpieVersion = r.version;
                        restart();
                    });
                } catch (IOException e) {
                    handler.post(() -> {
                        k.say(getString(R.string.dl_failed, String.valueOf(e.getMessage())) + "\n" + getString(R.string.dl_failed_hint));
                        k.actions.setAlpha(1f);
                    });
                }
            });
        });
        k.link("重新检查", () -> checkInto(k, kFound));
        if (MagpieGateway.canRollback(this)) {
            String pv = MagpiePrefs.prevVersion(this);
            final TextView[] rb = {null};
            rb[0] = k.link("回退到上一版" + (pv.isEmpty() ? "" : " " + pv), () -> new Ask()
                    .setTitle("回退内核")
                    .setMessage("回到上一版" + (pv.isEmpty() ? "" : " " + pv) + "，网关会重启。新版本有问题时用；更新后 60 秒内打不开网页也会自动回退。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("回退", (x, y) -> {
                        rb[0].setVisibility(View.GONE);
                        k.say("正在回退…");
                        pageLoaded = false;
                        web.loadUrl("about:blank");
                        overlay(getString(R.string.status_starting), null, false);
                        cli.execute(() -> {
                            String v = MagpieGateway.rollback(this, "手动回退");
                            handler.post(() -> {
                                k.say(v == null ? "回退失败，上一版文件不在了" : "已回退到 " + v + "，网关正在重启");
                                if (v != null) magpieVersion = v;
                            });
                        });
                    }).show());
        }

        // ---- 外壳
        final String cur = ShellUpdater.current(this);
        final UpdCard sh = new UpdCard(items, R.drawable.ic_float, "外壳 · magpie-android", "当前 " + cur + " · 来自 GitHub Releases");
        final ShellUpdater.Info[] sFound = {null};
        sh.button("浏览器下载", false, () -> {
            if (sFound[0] != null) openExternal(Updater.mirrored(this, sFound[0].apkUrl));
        });
        sh.button("直接下载", true, () -> {
            if (sFound[0] != null) runShellInstall(sFound[0], sh.status, sh.actions);
            sh.status.setVisibility(View.VISIBLE);
        });
        sh.link("重新检查", () -> checkShellInto(sh, sFound, cur));
        sh.link("发布页", () -> openExternal(sFound[0] != null && !sFound[0].page.isEmpty() ? sFound[0].page : ShellUpdater.RELEASES));

        // ---- 公共设置
        sectionHeader(items, "下载设置", "下面的镜像同时用于内核和外壳的下载（包括浏览器下载），文件照样校验。");
        switchRow(items, "内核只跟稳定通道", getString(R.string.upd_stable_hint), "stable".equals(MagpiePrefs.updateChannel(this)), on -> {
            String ch = on ? "stable" : "every";
            MagpiePrefs.saveUpdateChannel(this, ch);
            cli.execute(() -> MagpieGateway.runCli(this, 20, "update", "channel", ch));
            checkInto(k, kFound);
        });
        final EditText mirror = field(items, getString(R.string.upd_mirror_hint), false);
        mirror.setText(MagpiePrefs.mirror(this));
        addSheetItem(items, R.drawable.ic_globe, "保存下载镜像", "内核、外壳都走这个镜像；留空则直连 GitHub", () -> {
            String m = mirror.getText().toString().trim();
            if (!m.isEmpty() && !m.startsWith("http")) m = "https://" + m;
            if (!m.isEmpty() && !m.endsWith("/")) m = m + "/";
            final String mm = m;
            mirror.setText(mm);
            MagpiePrefs.saveMirror(this, mm);
            cli.execute(() -> MagpieGateway.runCli(this, 20, "update", "mirror", mm.isEmpty() ? "off" : mm));
            toast(mm.isEmpty() ? "已改为直连 GitHub" : "已保存，内核和外壳都会走这个镜像");
            checkInto(k, kFound);
            checkShellInto(sh, sFound, cur);
        });
        d.show();
        checkInto(k, kFound);
        checkShellInto(sh, sFound, cur);
    }

    private void sectionHeader(LinearLayout items, CharSequence title, CharSequence hint) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(13);
        t.setTextColor(getColor(R.color.accent));
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        t.setPadding(dp(4), items.getChildCount() == 0 ? dp(4) : dp(22), dp(4), 0);
        items.addView(t);
        if (hint != null && hint.length() > 0) {
            TextView h = new TextView(this);
            h.setText(hint);
            h.setTextSize(12);
            h.setTextColor(getColor(R.color.text_muted));
            h.setLineSpacing(dp(1), 1f);
            h.setPadding(dp(4), dp(3), dp(4), dp(6));
            items.addView(h);
        }
    }

    /** Direct download: fetch, verify, hand to the system installer. info shows progress; row (may be null) is disabled meanwhile. */
    private void runShellInstall(final ShellUpdater.Info r, final TextView info, final View row) {
        if (ShellUpdater.busy) { toast("外壳更新已经在进行"); return; }
        if (!ShellUpdater.canInstall(this)) {
            new Ask().setTitle("需要安装权限")
                    .setMessage("第一次在应用内更新外壳，需要在系统设置里允许 magpie「安装未知应用」。打开后返回这里再点一次。")
                    .setPositiveButton("去设置", (x, y) -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                        } catch (ActivityNotFoundException e) {
                            toast("打不开系统设置");
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        ShellUpdater.busy = true;
        if (row != null) row.setEnabled(false);
        info.setText("准备下载外壳 " + r.version + "…");
        cli.execute(() -> {
            try {
                File apk = ShellUpdater.download(this, r, (st, pc) -> handler.post(() -> info.setText(pc >= 0 ? st + "  " + pc + "%" : st)));
                handler.post(() -> {
                    info.setText("下载完成，请在系统界面确认安装");
                    try {
                        ShellUpdater.install(this, apk, msg -> handler.post(() -> {
                            info.setText(msg);
                            if (row != null) row.setEnabled(true);
                        }));
                    } catch (IOException e) {
                        ShellUpdater.busy = false;
                        info.setText("安装失败：" + e.getMessage());
                        if (row != null) row.setEnabled(true);
                    }
                });
            } catch (IOException e) {
                ShellUpdater.busy = false;
                handler.post(() -> {
                    info.setText("下载失败：" + e.getMessage() + "\n可以设置下载镜像后重试，或改用浏览器下载。");
                    if (row != null) row.setEnabled(true);
                });
            }
        });
    }

    /** Once per launch: if the GitHub release is newer than this shell, offer it with its release notes. */
    private void autoCheckShell() {
        final String cur = ShellUpdater.current(this);
        cli.execute(() -> {
            final ShellUpdater.Info r;
            try {
                r = ShellUpdater.latest(this);
            } catch (IOException e) {
                android.util.Log.w("magpie", "shell auto-check: " + e.getMessage());
                return;
            }
            if (MagpieGateway.compareVersions(r.version, cur) <= 0) return;
            handler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                String notes = ShellUpdater.plainNotes(r.notes);
                if (notes.length() > 1200) notes = notes.substring(0, 1200) + "…";
                String msg = "当前 " + cur + "  →  最新 " + r.version
                        + (r.size > 0 ? "（" + Storage.human(r.size) + "）" : "")
                        + "\n\n" + (notes.isEmpty() ? "这个版本没有写更新日志。" : notes);
                new Ask().setTitle("外壳有新版本 " + r.version)
                        .setMessage(msg)
                        .setNegativeButton("以后", null)
                        .setNeutralButton("浏览器下载", (x, y) -> openExternal(Updater.mirrored(this, r.apkUrl)))
                        .setPositiveButton("直接下载", (x, y) -> {
                            Dialog d = plainSheet("正在更新外壳 " + r.version, "下载完成后系统会弹出安装确认，装好后重新打开 magpie。");
                            TextView info = outputBox((LinearLayout) d.findViewById(R.id.sheetItems), 80);
                            d.show();
                            runShellInstall(r, info, null);
                        })
                        .show();
            });
        });
    }

    private void checkShellInto(UpdCard c, ShellUpdater.Info[] found, String cur) {
        c.state("检查中", false);
        c.say("");
        c.showNotes("");
        c.actions.setVisibility(View.GONE);
        cli.execute(() -> {
            try {
                ShellUpdater.Info r = ShellUpdater.latest(this);
                boolean newer = MagpieGateway.compareVersions(r.version, cur) > 0;
                String notes = ShellUpdater.plainNotes(r.notes);
                handler.post(() -> {
                    found[0] = r;
                    c.state(newer ? "新版本 " + r.version : "已是最新", newer);
                    if (newer) {
                        c.showNotes(notes.isEmpty() ? "这个版本没有写更新日志。" : notes);
                        if (r.size > 0) c.say("安装包 " + Storage.human(r.size) + "，校验签名一致后交给系统安装，设置不会丢。");
                    }
                    c.actions.setVisibility(newer ? View.VISIBLE : View.GONE);
                    c.actions.setAlpha(1f);
                });
            } catch (IOException e) {
                handler.post(() -> {
                    c.state("检查失败", false);
                    c.say(String.valueOf(e.getMessage()));
                });
            }
        });
    }

    private void checkInto(UpdCard c, Updater.Release[] found) {
        c.state("检查中", false);
        c.say("");
        c.showNotes("");
        c.actions.setVisibility(View.GONE);
        cli.execute(() -> {
            File bin = MagpieGateway.binary(this);
            String cur = bin == null ? "" : MagpieGateway.versionOf(this, bin);
            try {
                Updater.Release r = Updater.latest(this);
                MagpiePrefs.saveLastUpdateCheck(this, System.currentTimeMillis());
                boolean newer = cur.isEmpty() || MagpieGateway.compareVersions(r.version, cur) > 0;
                String notes = ShellUpdater.plainNotes(r.notes.replaceAll("(?s)### Install.*", ""));
                String ch = "stable".equals(MagpiePrefs.updateChannel(this)) ? "稳定通道" : "全部版本";
                handler.post(() -> {
                    found[0] = r;
                    c.sub.setText("当前 " + (cur.isEmpty() ? "—" : cur) + " · " + ch);
                    c.state(newer ? "新版本 " + r.version : "已是最新", newer);
                    if (newer) {
                        c.showNotes(notes.isEmpty() ? "这个版本没有写更新日志。" : notes);
                        c.say(getString(R.string.upd_install_hint));
                    }
                    c.actions.setVisibility(newer ? View.VISIBLE : View.GONE);
                    c.actions.setAlpha(1f);
                });
            } catch (IOException e) {
                handler.post(() -> {
                    c.state("检查失败", false);
                    c.say(getString(R.string.dl_failed, String.valueOf(e.getMessage())) + "\n" + getString(R.string.dl_failed_hint));
                });
            }
        });
    }

    // ------------------------------------------------------------------ new magpie features

    private void showTools() {
        final Dialog d = plainSheet(getString(R.string.menu_tools) + (magpieVersion.isEmpty() ? "" : " · " + magpieVersion), getString(R.string.tools_subtitle));
        LinearLayout items = d.findViewById(R.id.sheetItems);
        addSheetItem(items, R.drawable.ic_toolbox, R.string.tools_list, getString(R.string.tools_list_hint), () -> runAndShow("tools", null, 30, "tools"));
        addSheetItem(items, R.drawable.ic_wrench, R.string.tools_call, getString(R.string.tools_call_hint), () -> { d.dismiss(); showToolCall(); });
        addSheetItem(items, R.drawable.ic_pulse, R.string.tools_cpu, getString(R.string.tools_cpu_hint, CPU_SECONDS), () -> {
            File out = new File(MagpieGateway.exportDir(this), "magpie-cpu-" + stamp() + ".pb.gz");
            runAndShow("cpu", out, CPU_SECONDS + 60, "cpu", out.getAbsolutePath(), "--seconds", String.valueOf(CPU_SECONDS));
        });
        addSheetItem(items, R.drawable.ic_terminal, R.string.tools_goroutines, getString(R.string.tools_goroutines_hint), () -> {
            File out = new File(MagpieGateway.exportDir(this), "magpie-goroutines-" + stamp() + ".txt");
            runAndShow("goroutines", out, 60, "goroutines", out.getAbsolutePath());
        });
        addSheetItem(items, R.drawable.ic_doc, R.string.tools_licenses, getString(R.string.tools_licenses_hint), () -> runAndShow("licenses", null, 30, "licenses"));
        addSheetItem(items, R.drawable.ic_doc, R.string.tools_version, null, () -> runAndShow("version", null, 15, "version"));
        d.show();
    }

    private void showToolCall() {
        final Dialog d = plainSheet(getString(R.string.tools_call), getString(R.string.tools_call_hint));
        LinearLayout items = d.findViewById(R.id.sheetItems);
        final EditText name = field(items, getString(R.string.tools_call_name), false);
        final EditText args = field(items, getString(R.string.tools_call_args), true);
        addSheetItem(items, R.drawable.ic_wrench, R.string.tools_call_run, null, () -> {
            String n = name.getText().toString().trim();
            if (n.isEmpty()) {
                name.requestFocus();
                return;
            }
            List<String> cmd = new ArrayList<>();
            cmd.add("tool");
            cmd.add(n);
            cmd.addAll(tokens(args.getText().toString()));
            File dir = new File(MagpieGateway.exportDir(this), "tool-" + stamp());
            dir.mkdirs();
            cmd.add("-o");
            cmd.add(dir.getAbsolutePath());
            runAndShow("tool " + n, dir, 600, cmd.toArray(new String[0]));
        });
        d.show();
    }

    private EditText field(LinearLayout parent, String hint, boolean multi) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(15);
        e.setTextColor(getColor(R.color.text_primary));
        e.setHintTextColor(getColor(R.color.text_muted));
        e.setBackground(getDrawable(R.drawable.bg_field_group));
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        if (multi) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            e.setMinLines(2);
        } else {
            e.setSingleLine(true);
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(4);
        parent.addView(e, lp);
        return e;
    }

    /** shell-like split: spaces separate, "double" or 'single' quotes group */
    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        boolean have = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                } else {
                    cur.append(ch);
                }
            } else if (ch == '"' || ch == '\'' || ch == '“' || ch == '”') {
                quote = (ch == '“') ? '”' : ch;
                have = true;
            } else if (Character.isWhitespace(ch)) {
                if (have || cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    have = false;
                }
            } else {
                cur.append(ch);
            }
        }
        if (have || cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    /** Runs a magpie subcommand, shows its output in a sheet, and copies any file it wrote to Downloads/magpie. */
    private void runAndShow(String label, final File product, long timeout, final String... args) {
        boolean needsGateway = !"licenses".equals(args[0]) && !"version".equals(args[0]);
        if (needsGateway && !MagpieGateway.isRunning()) {
            toast(getString(R.string.tools_need_gateway));
            return;
        }
        final Dialog d = plainSheet(getString(R.string.tools_output_title, label), null);
        LinearLayout items = d.findViewById(R.id.sheetItems);
        final TextView out = outputBox(items, 340);
        out.setText(getString(R.string.tools_running, label));
        addSheetItem(items, R.drawable.ic_copy, R.string.copy_output, null, () -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("magpie", out.getText()));
            toast(getString(R.string.copied, label));
        });
        addSheetItem(items, R.drawable.ic_close, R.string.close, null, d::dismiss);
        d.show();
        final Set<String> before = new HashSet<>();
        if (product != null && product.isDirectory()) {
            String[] names = product.list();
            if (names != null) {
                for (String n : names) before.add(n);
            }
        }
        cli.execute(() -> {
            MagpieGateway.CliResult r = MagpieGateway.runCli(this, timeout, args);
            StringBuilder text = new StringBuilder(r.output.trim().isEmpty() ? "(no output, exit " + r.code + ")" : r.output.trim());
            List<String> saved = new ArrayList<>();
            if (product != null) {
                if (product.isFile()) {
                    if (toDownloads(product)) saved.add(product.getName());
                } else if (product.isDirectory()) {
                    File[] files = product.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            if (f.isFile() && !before.contains(f.getName()) && toDownloads(f)) saved.add(f.getName());
                        }
                    }
                }
            }
            if (!saved.isEmpty()) {
                text.append("\n\n").append(getString(R.string.tools_saved, String.join(", ", saved)));
            }
            handler.post(() -> {
                if (!isDestroyed()) {
                    out.setText(text.toString());
                    if (!saved.isEmpty()) toast(getString(R.string.tools_saved, String.join(", ", saved)));
                }
            });
        });
    }

    private TextView outputBox(LinearLayout parent, int heightDp) {
        TextView tv = new TextView(this);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(11.5f);
        tv.setTextColor(getColor(R.color.text_secondary));
        tv.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundResource(R.drawable.bg_log_box);
        int p = dp(14);
        sv.setPadding(p, p, p, p);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.addView(tv);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(heightDp));
        lp.topMargin = dp(4);
        parent.addView(sv, lp);
        return tv;
    }

    private boolean toDownloads(File f) {
        ContentResolver cr = getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, f.getName());
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime(f.getName()));
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/magpie");
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) {
            return false;
        }
        try (InputStream in = new FileInputStream(f); OutputStream os = cr.openOutputStream(uri)) {
            if (os == null) return false;
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) >= 0) os.write(b, 0, n);
        } catch (IOException e) {
            cr.delete(uri, null, null);
            return false;
        }
        v.clear();
        v.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(uri, v, null, null);
        return true;
    }

    private static String mime(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".txt") || n.endsWith(".md")) return "text/plain";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".gz")) return "application/gzip";
        return "application/octet-stream";
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
    }

    // ------------------------------------------------------------------ original actions

    private void reload() {
        pageLoaded = false;
        waits = 0;
        overlay(getString(R.string.status_starting), null, false);
    }

    private void toggleGateway() {
        if (MagpieGateway.isRunning()) {
            MagpieService.stop(this);
            status(false);
            toast(getString(R.string.status_stopped));
        } else {
            MagpieService.start(this);
            reload();
        }
    }

    private void restart() {
        pageLoaded = false;
        waits = 0;
        web.loadUrl("about:blank");
        overlay(getString(R.string.status_starting), null, false);
        cli.execute(() -> {
            MagpieGateway.stop();
            handler.post(() -> MagpieService.start(this));
        });
    }

    private void openExternal(String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            toast(getString(R.string.no_browser));
        }
    }

    private boolean isMagpiePage(String url) {
        Uri u = Uri.parse(url);
        String scheme = u.getScheme();
        if (scheme == null || "about".equals(scheme) || "data".equals(scheme) || "blob".equals(scheme)) {
            return true;
        }
        return "http".equals(scheme) && "127.0.0.1".equals(u.getHost()) && u.getPort() == MagpiePrefs.webPort(this);
    }

    private void copyGateway() {
        String url = preferredBase() + "/v1";
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("magpie", url));
        toast(getString(R.string.copied, url));
    }

    private void showAddresses() {
        final Dialog d = plainSheet("所有可用地址", "点按任意一项复制。API 地址都已带上 /v1。");
        LinearLayout items = d.findViewById(R.id.sheetItems);
        int gw = MagpiePrefs.gatewayPort(this);
        String cf = Tunnels.cloudflareUrl();
        if (!cf.isEmpty()) addCopyRow(items, d, "Cloudflare 公网", cf + "/v1");
        String ts = Tunnels.tailscaleIp();
        if (!ts.isEmpty()) addCopyRow(items, d, "Tailscale", "http://" + ts + ":" + gw + "/v1");
        if (MagpiePrefs.lan(this)) {
            String ip = lanIp();
            if (ip != null) addCopyRow(items, d, "局域网", "http://" + ip + ":" + gw + "/v1");
        }
        addCopyRow(items, d, "本地回环", "http://127.0.0.1:" + gw + "/v1");
        addCopyRow(items, d, "网页界面（仅本机）", MagpieGateway.pageUrl(this));
        d.show();
    }

    private void addCopyRow(LinearLayout items, Dialog d, String label, String url) {
        addSheetItem(items, R.drawable.ic_copy, label, url, () -> { copy("magpie", url); d.dismiss(); });
    }

    private void showLog() {
        final Dialog d = plainSheet(getString(R.string.log_title), getString(R.string.log_subtitle));
        LinearLayout items = d.findViewById(R.id.sheetItems);
        final TextView tv = outputBox(items, 260);
        readLogInto(tv);
        addSheetItem(items, R.drawable.ic_refresh, R.string.refresh, null, () -> readLogInto(tv));
        addSheetItem(items, R.drawable.ic_open_external, "分享诊断日志", "网关日志、崩溃记录和设备信息打包成一个文件，保存到 下载/magpie 并分享", this::shareDiagnostics);
        addSheetItem(items, R.drawable.ic_close, R.string.close, null, d::dismiss);
        d.show();
    }

    private void shareDiagnostics() {
        cli.execute(() -> {
            try {
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(buf)) {
                    z.putNextEntry(new java.util.zip.ZipEntry("device.txt"));
                    z.write(CrashLog.deviceInfo(this).getBytes(StandardCharsets.UTF_8));
                    z.closeEntry();
                    File logs = MagpieGateway.logFile(this).getParentFile();
                    for (String n : new String[]{"magpie.log.1", "magpie.log", "crash.log"}) {
                        File f = new File(logs, n);
                        if (!f.isFile()) continue;
                        z.putNextEntry(new java.util.zip.ZipEntry(n));
                        try (InputStream in = new FileInputStream(f)) {
                            byte[] b = new byte[16384];
                            int k;
                            while ((k = in.read(b)) >= 0) z.write(b, 0, k);
                        }
                        z.closeEntry();
                    }
                }
                final Uri uri = WebFiles.store(this, new java.io.ByteArrayInputStream(buf.toByteArray()), "magpie-diagnostics-" + stamp() + ".zip", "application/zip");
                handler.post(() -> {
                    Intent send = new Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try {
                        startActivity(Intent.createChooser(send, "分享诊断日志"));
                    } catch (ActivityNotFoundException ignored) {
                    }
                });
            } catch (IOException e) {
                handler.post(() -> toast("打包失败：" + e.getMessage()));
            }
        });
    }

    // ------------------------------------------------------------------ remote access

    private String remoteHint() {
        List<String> on = new ArrayList<>();
        if (Tunnels.cloudflareRunning()) on.add("Cloudflare");
        if (Tunnels.tailscaleRunning()) on.add("Tailscale");
        return on.isEmpty() ? "Cloudflare 公网地址 · Tailscale 组网" : "已开启：" + String.join("、", on);
    }

    private TextView sectionLabel(LinearLayout parent, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(getColor(R.color.accent));
        t.setPadding(dp(4), dp(14), dp(4), dp(2));
        parent.addView(t);
        return t;
    }

    private TextView statusLine(LinearLayout parent) {
        TextView t = new TextView(this);
        t.setTextSize(13);
        t.setTextColor(getColor(R.color.text_secondary));
        t.setTextIsSelectable(true);
        t.setPadding(dp(4), dp(2), dp(4), dp(4));
        parent.addView(t);
        return t;
    }

    private void copy(String label, String text) {
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(label, text));
        toast(getString(R.string.copied, text));
    }

    private void showRemote() {
        final Dialog d = plainSheet("远程访问", "不用公网 IP 也能在外面用这台手机的网关。cloudflared 和 Tailscale 第一次打开时下载，以后直接用。");
        LinearLayout items = d.findViewById(R.id.sheetItems);
        final int gw = MagpiePrefs.gatewayPort(this);

        // Cloudflare
        sectionLabel(items, "Cloudflare 临时公网地址");
        final TextView cfStatus = statusLine(items);
        final Switch[] cfSw = {null};
        cfSw[0] = switchRow(items, "开启 HTTPS 公网地址", "生成 xxx.trycloudflare.com，任何人拿到地址都能访问，务必先在 magpie 网页里设置网关密钥。每次开启地址都会变。",
                Tunnels.cloudflareRunning(), on -> {
            if (!on) {
                MagpiePrefs.saveCfOn(this, false);
                cli.execute(() -> Tunnels.stopCloudflare(true));
                return;
            }
            new Ask()
                    .setTitle("暴露到公网？")
                    .setMessage("开启后网关会出现在一个公开的 HTTPS 地址上，别人拿到地址就能消耗你的 API 额度。确认已经设置网关密钥再继续。")
                    .setPositiveButton("开启", (x, y) -> {
                        MagpiePrefs.saveCfOn(this, true);
                        cli.execute(() -> {
                            try {
                                if (!Tunnels.hasCloudflared(this)) {
                                    Tunnels.downloadCloudflared(this, (st, pc) -> handler.post(() -> cfStatus.setText(pc >= 0 ? st + " " + pc + "%" : st)));
                                }
                                Tunnels.startCloudflare(this);
                            } catch (IOException e) {
                                MagpiePrefs.saveCfOn(this, false);
                                handler.post(() -> {
                                    cfStatus.setText("失败：" + e.getMessage());
                                    cfSw[0].setChecked(false);
                                });
                            }
                        });
                    })
                    .setNegativeButton("取消", (x, y) -> cfSw[0].setChecked(false))
                    .setOnCancelListener(x -> cfSw[0].setChecked(false))
                    .show();
        });
        addSheetItem(items, R.drawable.ic_copy, "复制公网 API 地址", "OpenAI 兼容接口：公网地址 + /v1", () -> {
            String u = Tunnels.cloudflareUrl();
            if (u.isEmpty()) toast("还没有公网地址"); else copy("magpie", u + "/v1");
        });

        // Tailscale
        sectionLabel(items, "Tailscale 虚拟局域网");
        final TextView tsStatus = statusLine(items);
        final EditText key = field(items, "AuthKey（可空，空着就用浏览器登录）", false);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setText(MagpiePrefs.tsAuthKey(this));
        final EditText host = field(items, "主机名，例如 magpie-android", false);
        host.setText(MagpiePrefs.tsHostname(this));
        final String[] authUrl = {""};
        final String[] tsIp = {""};
        final Switch[] tsSw = {null};
        tsSw[0] = switchRow(items, "开启 Tailscale（用户态，免 Root）", "不占系统 VPN；同一 tailnet 的设备用 100.x.x.x:" + gw + " 访问网关",
                Tunnels.tailscaleRunning(), on -> {
            MagpiePrefs.saveTs(this, key.getText().toString().trim(), host.getText().toString().trim());
            MagpiePrefs.saveTsOn(this, on);
            if (!on) {
                cli.execute(() -> Tunnels.stopTailscale(true));
                return;
            }
            cli.execute(() -> {
                try {
                    if (!Tunnels.hasTailscale(this)) {
                        Tunnels.downloadTailscale(this, (st, pc) -> handler.post(() -> tsStatus.setText(pc >= 0 ? st + " " + pc + "%" : st)));
                    }
                    handler.post(() -> tsStatus.setText("正在启动 tailscaled…"));
                    Tunnels.startTailscale(this);
                } catch (IOException e) {
                    MagpiePrefs.saveTsOn(this, false);
                    handler.post(() -> {
                        tsStatus.setText("失败：" + e.getMessage());
                        tsSw[0].setChecked(false);
                    });
                }
            });
        });
        addSheetItem(items, R.drawable.ic_open_external, "登录 / 应用设置", "保存 AuthKey 和主机名并重新连接；没有 AuthKey 时打开浏览器登录", () -> {
            MagpiePrefs.saveTs(this, key.getText().toString().trim(), host.getText().toString().trim());
            if (!authUrl[0].isEmpty()) {
                openExternal(authUrl[0]);
                return;
            }
            if (!Tunnels.tailscaleRunning()) {
                toast("先打开上面的 Tailscale 开关");
                return;
            }
            tsStatus.setText("正在重新连接…");
            cli.execute(() -> Tunnels.up(this));
        });
        addSheetItem(items, R.drawable.ic_close, "退出 Tailscale 登录", "从 tailnet 移除这台设备的登录状态", () -> {
            if (!Tunnels.tailscaleRunning()) return;
            cli.execute(() -> Tunnels.ts(this, 15, "logout"));
            toast("已退出登录");
        });
        addSheetItem(items, R.drawable.ic_copy, "复制 Tailscale API 地址", null, () -> {
            if (tsIp[0].isEmpty()) toast("还没有 Tailscale IP"); else copy("magpie", "http://" + tsIp[0] + ":" + gw + "/v1");
        });

        sectionLabel(items, "组件与日志");
        addSheetItem(items, R.drawable.ic_download, "检查 cloudflared / Tailscale 更新", "只检查已经下载过的组件，有新版本再由你决定是否更新", () -> checkComponentUpdates());
        addSheetItem(items, R.drawable.ic_terminal, "查看隧道日志", "cloudflared 和 tailscaled 最后 60 行输出", () -> {
            final Dialog l = plainSheet("隧道日志", null);
            LinearLayout li = l.findViewById(R.id.sheetItems);
            TextView tv = outputBox(li, 320);
            tv.setText("== cloudflared ==\n" + Tunnels.tail(Tunnels.log(this, "cloudflared"), 60)
                    + "\n\n== tailscaled ==\n" + Tunnels.tail(Tunnels.log(this, "tailscaled"), 60));
            l.show();
        });

        final Runnable[] tick = {null};
        tick[0] = () -> {
            if (!d.isShowing()) return;
            cli.execute(() -> {
                String cfUrl = Tunnels.cloudflareUrl(), cfSt = Tunnels.cloudflareState();
                String[] ts = Tunnels.tailscaleStatus(this);
                handler.post(() -> {
                    if (!d.isShowing()) return;
                    if (!cfUrl.isEmpty()) cfStatus.setText("已连接：" + cfUrl);
                    else if (!cfSt.isEmpty()) cfStatus.setText(cfSt);
                    else if (!cfStatus.getText().toString().startsWith("失败") && !cfStatus.getText().toString().startsWith("正在下载"))
                        cfStatus.setText(Tunnels.hasCloudflared(this) ? "未开启" : "未开启（首次开启会下载约 40 MB）");
                    authUrl[0] = ts[3];
                    tsIp[0] = ts[1];
                    String cur = tsStatus.getText().toString();
                    switch (ts[0]) {
                        case "Running":
                            tsStatus.setText("已连接：" + ts[1] + ":" + gw + (ts[2].isEmpty() ? "" : "\n" + ts[2]));
                            break;
                        case "NeedsLogin":
                            tsStatus.setText(ts[3].isEmpty() ? "需要登录：填 AuthKey 后点「登录 / 应用设置」" : "需要登录：点「登录 / 应用设置」在浏览器里完成授权");
                            break;
                        case "Stopped":
                            if (!cur.startsWith("失败") && !cur.startsWith("正在"))
                                tsStatus.setText(Tunnels.hasTailscale(this) ? "未开启" : "未开启（首次开启会下载约 30 MB）");
                            break;
                        default:
                            tsStatus.setText("状态：" + ts[0]);
                    }
                });
            });
            handler.postDelayed(tick[0], 2000L);
        };
        d.show();
        tick[0].run();
    }

    private void checkComponentUpdates() {
        final String[] names = {"cloudflared", "tailscale"};
        final boolean[] installed = {Tunnels.hasCloudflared(this), Tunnels.hasTailscale(this)};
        if (!installed[0] && !installed[1]) {
            toast("还没有下载过 cloudflared 或 Tailscale，第一次打开开关时会下载最新版");
            return;
        }
        toast("正在检查更新…");
        cli.execute(() -> {
            final StringBuilder msg = new StringBuilder();
            final List<String> outdated = new ArrayList<>();
            for (int i = 0; i < names.length; i++) {
                String label = i == 0 ? "cloudflared" : "Tailscale";
                if (!installed[i]) { msg.append(label).append("：未下载\n"); continue; }
                String cur = Tunnels.installedVersion(this, names[i]);
                String latest;
                try { latest = Tunnels.latestVersion(this, names[i]); } catch (IOException e) { latest = ""; }
                msg.append(label).append("：当前 ").append(cur.isEmpty() ? "?" : cur)
                        .append("，最新 ").append(latest.isEmpty() ? "查询失败" : latest);
                if (Tunnels.newer(latest, cur)) { msg.append("（可更新）"); outdated.add(names[i]); }
                else if (!latest.isEmpty()) msg.append("（已是最新）");
                msg.append("\n");
            }
            handler.post(() -> {
                Ask b = new Ask()
                        .setTitle("组件更新")
                        .setMessage(msg.toString().trim());
                if (outdated.isEmpty()) {
                    b.setPositiveButton("好", null);
                } else {
                    b.setPositiveButton("更新", (x, y) -> {
                        toast("正在后台更新，运行中的隧道会短暂断开");
                        cli.execute(() -> {
                            StringBuilder r = new StringBuilder();
                            for (String n : outdated) {
                                try {
                                    Tunnels.updateComponent(this, n, null);
                                    r.append(n).append(" 已更新到 ").append(Tunnels.installedVersion(this, n)).append("\n");
                                } catch (IOException e) {
                                    r.append(n).append(" 更新失败：").append(e.getMessage()).append("\n");
                                }
                            }
                            handler.post(() -> new Ask()
                                    .setTitle("组件更新").setMessage(r.toString().trim()).setPositiveButton("好", null).show());
                        });
                    }).setNegativeButton("以后", null);
                }
                b.show();
            });
        });
    }

    private void readLogInto(TextView tv) {
        String log = readLog();
        tv.setText(log.isEmpty() ? getString(R.string.log_empty) : log);
    }

    private String readLog() {
        ArrayDeque<String> lines = new ArrayDeque<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(MagpieGateway.logFile(this)), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) {
                lines.addLast(l);
                if (lines.size() > 300) lines.removeFirst();
            }
        } catch (IOException e) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        return sb.toString();
    }

    private void showSettings() {
        final Dialog d = sheet(R.layout.dialog_settings, R.id.settingsRoot);
        final EditText gw = d.findViewById(R.id.settingsGatewayPort);
        final EditText wp = d.findViewById(R.id.settingsWebPort);
        final Switch lan = d.findViewById(R.id.settingsLan);
        final Switch auto = d.findViewById(R.id.settingsAutostart);
        gw.setText(String.valueOf(MagpiePrefs.gatewayPort(this)));
        wp.setText(String.valueOf(MagpiePrefs.webPort(this)));
        lan.setChecked(MagpiePrefs.lan(this));
        auto.setChecked(MagpiePrefs.autostart(this));
        lan.setOnCheckedChangeListener((b, on) -> {
            View mv = d.findViewById(R.id.settingsMdns);
            if (mv != null) mv.setEnabled(on);
            if (!on || MagpiePrefs.lan(this)) return;
            new Ask()
                    .setTitle("开启局域网访问？")
                    .setMessage("同一网络里的其他设备将能直接调用你的网关，并消耗你在 magpie 里配置的 API 额度。只在家里或公司这类可信网络中开启，并在 magpie 网页里设置网关密钥。")
                    .setPositiveButton("开启", null)
                    .setNegativeButton("取消", (x, y) -> b.setChecked(false))
                    .setOnCancelListener(x -> b.setChecked(false))
                    .show();
        });
        final Switch mdns = d.findViewById(R.id.settingsMdns);
        mdns.setChecked(MagpiePrefs.mdns(this) && MagpiePrefs.lan(this));
        mdns.setEnabled(lan.isChecked());
        final Switch compat = d.findViewById(R.id.settingsCompat);
        compat.setChecked(MagpiePrefs.compatNet(this));
        addUpstreamRow(d, compat);
        d.findViewById(R.id.settingsBattery).setOnClickListener(v -> askForBatteryExemption());
        TextView batt = d.findViewById(R.id.settingsBatteryHint);
        if (getSystemService(android.os.PowerManager.class).isIgnoringBatteryOptimizations(getPackageName())) {
            batt.setText(R.string.settings_battery_done);
        }
        d.findViewById(R.id.settingsCancel).setOnClickListener(v -> d.dismiss());
        d.findViewById(R.id.settingsSave).setOnClickListener(v -> {
            int g = parsePort(gw), w = parsePort(wp);
            if (!MagpiePrefs.validPort(g) || !MagpiePrefs.validPort(w)) {
                toast(getString(R.string.settings_invalid_port));
                return;
            }
            if (g == w) {
                toast(getString(R.string.settings_same_port));
                return;
            }
            boolean l = lan.isChecked(), a = auto.isChecked();
            boolean changed = g != MagpiePrefs.gatewayPort(this) || w != MagpiePrefs.webPort(this) || l != MagpiePrefs.lan(this)
                    || compat.isChecked() != MagpiePrefs.compatNet(this);
            MagpiePrefs.save(this, g, w, l, a);
            MagpiePrefs.saveCompatNet(this, compat.isChecked());
            boolean m = l && mdns.isChecked();
            MagpiePrefs.saveMdns(this, m);
            if (m) Tunnels.startMdns(this); else Tunnels.stopMdns(this);
            d.dismiss();
            showAddress();
            if (l) requestLocalNetworkPermissionIfNeeded();
            if (changed) restart();
        });
        d.show();
    }

    // ------------------------------------------------------------------ upstream proxy

    private String upstreamHint() {
        String up = MagpiePrefs.upstreamProxy(this);
        String local = "http://127.0.0.1:" + (DnsProxy.port() > 0 ? DnsProxy.port() : MagpiePrefs.proxyPort(this));
        return (up.isEmpty() ? "未设置，直连" : maskProxy(up)) + "\n外壳本地代理固定为 " + local;
    }

    private static String maskProxy(String p) {
        int at = p.lastIndexOf('@');
        int s = p.indexOf("://");
        if (at < 0) return p;
        return p.substring(0, s < 0 ? 0 : s + 3) + "***@" + p.substring(at + 1);
    }

    /** Puts an "上游代理" row right under the 网络兼容模式 card in 应用设置. */
    private void addUpstreamRow(Dialog d, Switch compat) {
        try {
            View card = (View) compat.getParent();
            ViewGroup list = (ViewGroup) card.getParent();
            LinearLayout holder = new LinearLayout(this);
            holder.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(10);
            holder.setLayoutParams(lp);
            final View[] row = new View[1];
            row[0] = addSheetItem(holder, R.drawable.ic_globe, "上游代理（需开启网络兼容模式）", upstreamHint(), () -> editUpstream(row[0]));
            list.addView(holder, list.indexOfChild(card) + 1);
        } catch (RuntimeException ignored) {
        }
    }

    private void editUpstream(View row) {
        final EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint("http://192.168.1.5:7890 或 socks5://用户:密码@127.0.0.1:1080");
        e.setText(MagpiePrefs.upstreamProxy(this));
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        e.setTextColor(getColor(R.color.text_primary));
        e.setHintTextColor(getColor(R.color.text_muted));
        e.setTextSize(14);
        e.setBackground(getDrawable(R.drawable.bg_field_group));
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout box = new LinearLayout(this);
        box.setPadding(0, dp(10), 0, 0);
        box.addView(e, new LinearLayout.LayoutParams(-1, -2));
        new Ask()
                .setTitle("上游代理")
                .setMessage("magpie 的所有出站请求先经过外壳的本地代理（负责解析域名），再转发到这里填的代理。支持 http:// 和 socks5://，可带用户名密码，主机写域名或 IP 都行。留空表示直连。\n\n"
                        + "magpie 网页「设置」里的代理请留空，或者填外壳本地代理 http://127.0.0.1:" + MagpiePrefs.proxyPort(this)
                        + "。如果在网页里直接填别的代理，主机必须写 IP，而且就不走这里了。")
                .setView(box)
                .setPositiveButton("保存", (x, y) -> {
                    String v = e.getText().toString().trim();
                    String err = DnsProxy.setUpstream(v);
                    if (err != null) {
                        DnsProxy.setUpstream(MagpiePrefs.upstreamProxy(this));
                        toast(err);
                        return;
                    }
                    MagpiePrefs.saveUpstreamProxy(this, v);
                    ((TextView) row.findViewById(R.id.sheetItemSubtitle)).setText(upstreamHint());
                    toast(v.isEmpty() ? "已改为直连，立即生效" : "已保存，新连接立即生效");
                })
                .setNeutralButton("清空", (x, y) -> {
                    DnsProxy.setUpstream("");
                    MagpiePrefs.saveUpstreamProxy(this, "");
                    ((TextView) row.findViewById(R.id.sheetItemSubtitle)).setText(upstreamHint());
                    toast("已改为直连");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ------------------------------------------------------------------ storage

    private void showStorage() {
        final Dialog d = plainSheet("空间管理", "正在计算…");
        final LinearLayout items = d.findViewById(R.id.sheetItems);
        storageAnimated = false;
        d.show();
        fillStorage(d, items);
    }

    private boolean storageAnimated;

    private void fillStorage(final Dialog d, final LinearLayout items) {
        final TextView sub = d.findViewById(R.id.sheetSubtitle);
        sub.setVisibility(View.VISIBLE);
        sub.setText("正在计算…");
        if (items.getChildCount() == 0) {
            ProgressBar pb = new ProgressBar(this);
            pb.setIndeterminateTintList(ColorStateList.valueOf(getColor(R.color.accent)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            lp.topMargin = dp(36);
            lp.bottomMargin = dp(36);
            items.addView(pb, lp);
        }
        cli.execute(() -> {
            final java.util.List<Storage.Cat> cats = Storage.scan(getApplicationContext());
            long devTotal = 0, devFree = 0;
            try {
                android.os.StatFs fs = new android.os.StatFs(getDataDir().getAbsolutePath());
                devTotal = fs.getTotalBytes();
                devFree = fs.getAvailableBytes();
            } catch (RuntimeException ignored) {
            }
            final long dt = devTotal, df = devFree;
            handler.post(() -> {
                if (!d.isShowing()) return;
                boolean animate = !storageAnimated;
                storageAnimated = true;
                renderStorage(d, items, sub, cats, dt, df, animate);
            });
        });
    }

    private TextView label(CharSequence s, float sp, int color, boolean medium) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(getColor(color));
        if (medium) t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        return t;
    }

    private android.graphics.drawable.GradientDrawable roundRect(int fill, float radiusDp) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(1) * radiusDp);
        return g;
    }

    private View dot(int color, int sizeDp) {
        View v = new View(this);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(color);
        v.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        lp.gravity = Gravity.CENTER_VERTICAL;
        v.setLayoutParams(lp);
        return v;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(getDrawable(R.drawable.bg_switch_card));
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        c.setLayoutParams(lp);
        return c;
    }

    private void renderStorage(final Dialog d, final LinearLayout items, TextView sub, final java.util.List<Storage.Cat> all,
                               long devTotal, long devFree, boolean animate) {
        items.removeAllViews();
        final java.util.List<Storage.Cat> cats = new java.util.ArrayList<>();
        for (Storage.Cat k : all) if (k.size > 0 || k.key.equals(Storage.CLI)) cats.add(k);
        final long total = Storage.total(cats);
        long freeable = 0;
        for (Storage.Cat k : cats) if (k.clearable && k.warn == null) freeable += k.size;
        final long safe = freeable;
        sub.setText(cats.size() + " 个类别，按占用从大到小排列");

        // ---- overview: donut + legend
        LinearLayout over = card();
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        StorageViews.Donut donut = new StorageViews.Donut(this);
        long[] vals = new long[cats.size()];
        int[] cols = new int[cats.size()];
        for (int i = 0; i < cats.size(); i++) {
            vals[i] = cats.get(i).size;
            cols[i] = StorageViews.color(this, cats.get(i).key);
        }
        donut.set(vals, cols, Storage.human(total), "总占用", animate);
        top.addView(donut);
        LinearLayout legend = new LinearLayout(this);
        legend.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, -2, 1);
        llp.leftMargin = dp(18);
        legend.setLayoutParams(llp);
        int shown = Math.min(cats.size(), 5);
        long rest = 0;
        for (int i = shown; i < cats.size(); i++) rest += cats.get(i).size;
        for (int i = 0; i < shown + (rest > 0 ? 1 : 0); i++) {
            boolean isRest = i == shown;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(3), 0, dp(3));
            row.addView(dot(isRest ? getColor(R.color.status_off) : cols[i], 8));
            TextView name = label(isRest ? "其余 " + (cats.size() - shown) + " 项" : cats.get(i).title, 12.5f, R.color.text_secondary, false);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, -2, 1);
            nlp.leftMargin = dp(8);
            row.addView(name, nlp);
            long v = isRest ? rest : cats.get(i).size;
            row.addView(label(total > 0 ? Math.round(v * 100.0 / total) + "%" : "0%", 12.5f, R.color.text_primary, true));
            legend.addView(row);
        }
        top.addView(legend);
        over.addView(top);

        // ---- device storage bar
        if (devTotal > 0) {
            View line = new View(this);
            line.setBackgroundColor(getColor(R.color.app_line));
            LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1) / 2 + 1));
            lineLp.topMargin = dp(16);
            lineLp.bottomMargin = dp(14);
            over.addView(line, lineLp);
            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            TextView ht = label("手机存储", 13, R.color.text_primary, true);
            head.addView(ht, new LinearLayout.LayoutParams(0, -2, 1));
            head.addView(label("剩余 " + Storage.human(devFree) + " / 共 " + Storage.human(devTotal), 12, R.color.text_muted, false));
            over.addView(head);
            long used = Math.max(0, devTotal - devFree);
            StorageViews.Bar bar = new StorageViews.Bar(this, 10);
            bar.set(new float[]{total / (float) devTotal, Math.max(0, used - total) / (float) devTotal},
                    new int[]{getColor(R.color.accent), getColor(R.color.switch_off)}, animate);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
            blp.topMargin = dp(10);
            over.addView(bar, blp);
            LinearLayout keys = new LinearLayout(this);
            keys.setOrientation(LinearLayout.HORIZONTAL);
            keys.setGravity(Gravity.CENTER_VERTICAL);
            keys.setPadding(0, dp(10), 0, 0);
            keys.addView(dot(getColor(R.color.accent), 8));
            TextView k1 = label("magpie " + Storage.human(total), 12, R.color.text_secondary, false);
            k1.setPadding(dp(6), 0, dp(16), 0);
            keys.addView(k1);
            keys.addView(dot(getColor(R.color.switch_off), 8));
            TextView k2 = label("其他应用 " + Storage.human(Math.max(0, used - total)), 12, R.color.text_secondary, false);
            k2.setPadding(dp(6), 0, 0, 0);
            keys.addView(k2);
            over.addView(keys);
        }
        items.addView(over);

        // ---- category list
        TextView sec = label("分类明细", 13, R.color.text_muted, true);
        sec.setPadding(dp(4), dp(18), 0, dp(2));
        items.addView(sec);
        LinearLayout list = card();
        list.setPadding(dp(4), dp(4), dp(4), dp(4));
        long max = cats.isEmpty() ? 1 : Math.max(1, cats.get(0).size);
        for (int i = 0; i < cats.size(); i++) {
            final Storage.Cat k = cats.get(i);
            if (i > 0) {
                View div = new View(this);
                div.setBackgroundColor(getColor(R.color.app_line));
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1) / 2 + 1));
                dlp.leftMargin = dp(32);
                dlp.rightMargin = dp(12);
                list.addView(div, dlp);
            }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackground(getDrawable(R.drawable.bg_sheet_item));
            row.setPadding(dp(12), dp(12), dp(12), dp(12));
            LinearLayout r1 = new LinearLayout(this);
            r1.setOrientation(LinearLayout.HORIZONTAL);
            r1.setGravity(Gravity.CENTER_VERTICAL);
            int col = StorageViews.color(this, k.key);
            r1.addView(dot(col, 10));
            TextView title = label(k.title, 15, R.color.text_primary, true);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1);
            tlp.leftMargin = dp(10);
            r1.addView(title, tlp);
            TextView pct = label(total > 0 ? (k.size * 100 < total && k.size > 0 ? "<1%" : Math.round(k.size * 100.0 / total) + "%") : "", 12, R.color.text_muted, false);
            pct.setPadding(0, 0, dp(8), 0);
            r1.addView(pct);
            r1.addView(label(Storage.human(k.size), 14, R.color.text_primary, true));
            row.addView(r1);
            StorageViews.Bar bar = new StorageViews.Bar(this, 6);
            bar.set(new float[]{k.size / (float) max}, new int[]{col}, animate);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
            blp.topMargin = dp(9);
            blp.leftMargin = dp(20);
            row.addView(bar, blp);
            LinearLayout r3 = new LinearLayout(this);
            r3.setOrientation(LinearLayout.HORIZONTAL);
            r3.setGravity(Gravity.CENTER_VERTICAL);
            r3.setPadding(dp(20), dp(8), 0, 0);
            TextView desc = label(k.desc, 12, R.color.text_secondary, false);
            desc.setLineSpacing(dp(1), 1f);
            r3.addView(desc, new LinearLayout.LayoutParams(0, -2, 1));
            TextView act;
            if (k.clearable) {
                act = label("清除", 12.5f, R.color.accent, true);
                act.setBackground(roundRect(getColor(R.color.accent_soft), 14));
                act.setPadding(dp(14), dp(6), dp(14), dp(6));
                act.setOnClickListener(v -> confirmClear(d, items, k));
            } else {
                act = label("系统必需", 11.5f, R.color.text_muted, false);
                act.setBackground(roundRect(getColor(R.color.app_surface_alt), 14));
                act.setPadding(dp(10), dp(5), dp(10), dp(5));
            }
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, -2);
            alp.leftMargin = dp(12);
            r3.addView(act, alp);
            row.addView(r3);
            row.setOnClickListener(v -> {
                if (k.clearable) confirmClear(d, items, k);
                else toast("这一项不能清除");
            });
            list.addView(row);
        }
        items.addView(list);

        // ---- buttons
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
        bl.topMargin = dp(16);
        btns.setLayoutParams(bl);
        TextView close = label(getString(R.string.close), 15, R.color.text_primary, true);
        close.setGravity(Gravity.CENTER);
        close.setBackground(getDrawable(R.drawable.bg_button_secondary));
        close.setOnClickListener(v -> d.dismiss());
        btns.addView(close, new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView clean = label("一键清理 " + Storage.human(safe), 15, R.color.text_on_accent, true);
        clean.setGravity(Gravity.CENTER);
        clean.setBackground(getDrawable(R.drawable.bg_button_primary));
        clean.setEnabled(safe > 0);
        clean.setAlpha(safe > 0 ? 1f : 0.45f);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, dp(48), 1.5f);
        clp.leftMargin = dp(10);
        btns.addView(clean, clp);
        clean.setOnClickListener(v -> new Ask()
                .setTitle("一键清理")
                .setMessage("清除日志、导出文件、临时文件和缓存，约 " + Storage.human(safe) + "。不影响配置和登录。")
                .setPositiveButton("清理", (x, y) -> {
                    java.util.List<Storage.Cat> todo = new java.util.ArrayList<>();
                    for (Storage.Cat c : cats) if (c.clearable && c.warn == null) todo.add(c);
                    clearCats(d, items, todo);
                })
                .setNegativeButton("取消", null)
                .show());
        items.addView(btns);
        TextView foot = label("一键清理包括：日志、导出文件、临时文件、magpie 缓存和网页缓存", 11.5f, R.color.text_muted, false);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(dp(8), dp(10), dp(8), 0);
        items.addView(foot);
    }

    private void confirmClear(Dialog d, LinearLayout items, Storage.Cat k) {
        new Ask()
                .setTitle("清除" + k.title + "？")
                .setMessage((k.warn == null ? "" : k.warn + "\n\n") + "将释放约 " + Storage.human(k.size) + "。")
                .setPositiveButton("清除", (x, y) -> clearCats(d, items, java.util.Collections.singletonList(k)))
                .setNegativeButton("取消", null)
                .show();
    }

    private void clearCats(final Dialog d, final LinearLayout items, final java.util.List<Storage.Cat> todo) {
        boolean webCache = false, webData = false;
        for (Storage.Cat k : todo) {
            if (k.key.equals(Storage.WEB_CACHE)) webCache = true;
            if (k.key.equals(Storage.WEB_DATA)) webData = true;
        }
        if (webCache || webData) {
            web.clearCache(true);
        }
        if (webData) {
            android.webkit.WebStorage.getInstance().deleteAllData();
            android.webkit.CookieManager.getInstance().removeAllCookies(null);
            android.webkit.CookieManager.getInstance().flush();
        }
        final boolean reloadPage = webData;
        toast("正在清除…");
        cli.execute(() -> {
            boolean restartGw = false;
            for (Storage.Cat k : todo) {
                switch (k.key) {
                    case Storage.CLOUDFLARED:
                        Tunnels.stopCloudflare(true);
                        MagpiePrefs.saveCfOn(this, false);
                        break;
                    case Storage.TAILSCALE:
                        Tunnels.stopTailscale(true);
                        MagpiePrefs.saveTsOn(this, false);
                        break;
                    case Storage.DATA:
                        MagpieGateway.stop();
                        restartGw = true;
                        break;
                    default:
                }
                if (!k.key.equals(Storage.WEB_DATA)) Storage.clearFiles(getApplicationContext(), k);
            }
            final boolean rs = restartGw;
            handler.post(() -> {
                toast("已清除");
                showAddress();
                if (rs) {
                    restart();
                } else if (reloadPage) {
                    reload();
                }
                if (d.isShowing()) fillStorage(d, items);
            });
        });
    }

    // ------------------------------------------------------------------ in-app dialogs

    interface AskButton { void on(Object dialog, int which); }
    interface AskCancel { void on(Object dialog); }

    /** Drop-in for AlertDialog.Builder that uses the app's own bottom-sheet style. */
    private final class Ask {
        private CharSequence title = "", message;
        private View view;
        private CharSequence pos, neg, neu;
        private AskButton posL, negL, neuL;
        private AskCancel cancelL;
        private boolean cancelable = true;

        Ask setTitle(CharSequence t) { title = t; return this; }
        Ask setMessage(CharSequence m) { message = m; return this; }
        Ask setView(View v) { view = v; return this; }
        Ask setPositiveButton(CharSequence t, AskButton l) { pos = t; posL = l; return this; }
        Ask setNegativeButton(CharSequence t, AskButton l) { neg = t; negL = l; return this; }
        Ask setNeutralButton(CharSequence t, AskButton l) { neu = t; neuL = l; return this; }
        Ask setOnCancelListener(AskCancel l) { cancelL = l; return this; }
        Ask setCancelable(boolean c) { cancelable = c; return this; }

        private TextView button(final Dialog d, CharSequence text, final AskButton l, final int which, boolean primary, LinearLayout row) {
            TextView b = new TextView(MainActivity.this);
            b.setText(text);
            b.setTextSize(15);
            b.setGravity(Gravity.CENTER);
            b.setSingleLine(true);
            b.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            b.setTextColor(getColor(primary ? R.color.text_on_accent : R.color.text_primary));
            b.setBackground(getDrawable(primary ? R.drawable.bg_button_primary : R.drawable.bg_button_secondary));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), primary ? 1.3f : 1f);
            if (row.getChildCount() > 0) lp.leftMargin = dp(10);
            row.addView(b, lp);
            b.setOnClickListener(v -> {
                d.dismiss();
                if (l != null) l.on(d, which);
            });
            return b;
        }

        Dialog show() {
            final Dialog d = plainSheet(title, message);
            TextView sub = d.findViewById(R.id.sheetSubtitle);
            sub.setTextSize(14);
            sub.setLineSpacing(dp(2), 1f);
            sub.setPadding(0, dp(4), 0, 0);
            LinearLayout items = d.findViewById(R.id.sheetItems);
            if (view != null) {
                if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
                items.addView(view);
            }
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
            rl.topMargin = dp(18);
            row.setLayoutParams(rl);
            if (neg != null) button(d, neg, negL, -2, false, row);
            if (neu != null) button(d, neu, neuL, -3, false, row);
            if (pos != null) button(d, pos, posL, -1, true, row);
            if (row.getChildCount() == 0) button(d, "好", null, -1, true, row);
            items.addView(row);
            d.setCancelable(cancelable);
            d.setCanceledOnTouchOutside(cancelable);
            if (cancelL != null) d.setOnCancelListener(x -> cancelL.on(x));
            d.show();
            return d;
        }
    }

    private int parsePort(EditText e) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private void requestLocalNetworkPermissionIfNeeded() {
        if (MagpiePrefs.lan(this) && checkSelfPermission(PERMISSION_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
            try {
                getPackageManager().getPermissionInfo(PERMISSION_LOCAL_NETWORK, 0);
                toast(getString(R.string.lan_permission_rationale));
                requestPermissions(new String[]{PERMISSION_LOCAL_NETWORK}, REQUEST_LOCAL_NETWORK);
            } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            }
        }
    }

    private void askForBatteryExemption() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
