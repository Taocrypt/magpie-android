package com.magpie.android;

import android.content.Context;
import android.content.SharedPreferences;
import java.security.SecureRandom;

/* loaded from: classes.dex */
public final class MagpiePrefs {
    public static final int DEFAULT_GATEWAY_PORT = 3425;
    public static final int DEFAULT_WEB_PORT = 3430;
    private static final String FILE = "magpie";
    private static final String KEY_AUTOSTART = "autostart";
    private static final String KEY_GATEWAY_PORT = "gatewayPort";
    private static final String KEY_LAN = "lan";
    private static final String KEY_WEB_KEY = "webKey";
    private static final String KEY_WEB_PORT = "webPort";

    private static final String KEY_FLOAT_ALPHA = "floatAlpha";
    private static final String KEY_FLOAT_W = "floatW";
    private static final String KEY_FLOAT_H = "floatH";
    private static final String KEY_FLOAT_X = "floatX";
    private static final String KEY_FLOAT_Y = "floatY";
    private static final String KEY_FLOAT_MINI = "floatMini";
    private static final String KEY_FLOAT_LOCK = "floatLock";
    private static final String KEY_COMPAT_NET = "compatNet2";
    private static final String KEY_SETUP_VERSION = "setupVersion";

    /** opacity in percent, 20..100 */
    public static int floatAlpha(Context c) { return clamp(prefs(c).getInt(KEY_FLOAT_ALPHA, 90), 20, 100); }
    /** width / height as percent of the screen */
    public static int floatWidth(Context c) { return clamp(prefs(c).getInt(KEY_FLOAT_W, 90), 30, 100); }
    public static int floatHeight(Context c) { return clamp(prefs(c).getInt(KEY_FLOAT_H, 45), 20, 95); }
    public static int floatX(Context c) { return prefs(c).getInt(KEY_FLOAT_X, 40); }
    public static int floatY(Context c) { return prefs(c).getInt(KEY_FLOAT_Y, 220); }
    public static boolean floatMini(Context c) { return prefs(c).getBoolean(KEY_FLOAT_MINI, false); }
    /** touch-through: the window only shows, taps go to the app below */
    public static boolean floatLock(Context c) { return prefs(c).getBoolean(KEY_FLOAT_LOCK, false); }
    public static void saveFloatLook(Context c, int alpha, int w, int h) {
        prefs(c).edit().putInt(KEY_FLOAT_ALPHA, clamp(alpha, 20, 100)).putInt(KEY_FLOAT_W, clamp(w, 30, 100)).putInt(KEY_FLOAT_H, clamp(h, 20, 95)).apply();
    }
    public static void saveFloatPos(Context c, int x, int y) { prefs(c).edit().putInt(KEY_FLOAT_X, x).putInt(KEY_FLOAT_Y, y).apply(); }
    public static void saveFloatMini(Context c, boolean v) { prefs(c).edit().putBoolean(KEY_FLOAT_MINI, v).apply(); }
    public static void saveFloatLock(Context c, boolean v) { prefs(c).edit().putBoolean(KEY_FLOAT_LOCK, v).apply(); }
    /** route the gateway's outbound traffic through the shell's DNS proxy (needed by the linux build) */
    public static boolean compatNet(Context c) { return prefs(c).getBoolean(KEY_COMPAT_NET, true); }
    public static void saveCompatNet(Context c, boolean v) { prefs(c).edit().putBoolean(KEY_COMPAT_NET, v).apply(); }
    public static int setupVersion(Context c) { return prefs(c).getInt(KEY_SETUP_VERSION, 0); }
    public static void saveSetupVersion(Context c, int v) { prefs(c).edit().putInt(KEY_SETUP_VERSION, v).apply(); }
    public static long binStamp(Context c) { return prefs(c).getLong("binStamp", -1); }
    public static void saveBinStamp(Context c, long v) { prefs(c).edit().putLong("binStamp", v).apply(); }
    /** "every" (default) or "stable" */
    public static String updateChannel(Context c) { return prefs(c).getString("updateChannel", "every"); }
    public static void saveUpdateChannel(Context c, String v) { prefs(c).edit().putString("updateChannel", v).apply(); }
    /** a GitHub download mirror put before github.com URLs, e.g. https://ghfast.top/ ; empty for none */
    public static String mirror(Context c) { return prefs(c).getString("mirror", ""); }
    public static void saveMirror(Context c, String v) { prefs(c).edit().putString("mirror", v == null ? "" : v.trim()).apply(); }
    public static String installedVersion(Context c) { return prefs(c).getString("installedVersion", ""); }
    public static void saveInstalledVersion(Context c, String v) { prefs(c).edit().putString("installedVersion", v).apply(); }
    public static long lastUpdateCheck(Context c) { return prefs(c).getLong("lastUpdateCheck", 0); }
    public static void saveLastUpdateCheck(Context c, long v) { prefs(c).edit().putLong("lastUpdateCheck", v).apply(); }
    /** the minimised bubble: size in dp, opacity %, snap to the nearest side, half-hide at the edge when idle */
    public static int bubbleSize(Context c) { return clamp(prefs(c).getInt("bubbleSize", 36), 32, 96); }
    public static int bubbleAlpha(Context c) { return clamp(prefs(c).getInt("bubbleAlpha", 90), 20, 100); }
    public static boolean bubbleSnap(Context c) { return prefs(c).getBoolean("bubbleSnap", true); }
    public static boolean bubbleHide(Context c) { return prefs(c).getBoolean("bubbleHide", true); }
    /** seconds of no touch before the bubble tucks itself away */
    public static int bubbleHideDelay(Context c) { return clamp(prefs(c).getInt("bubbleHideDelay", 3), 1, 15); }
    public static int bubbleX(Context c) { return prefs(c).getInt("bubbleX", -1); }
    public static int bubbleY(Context c) { return prefs(c).getInt("bubbleY", -1); }
    public static void saveBubbleLook(Context c, int size, int alpha) { prefs(c).edit().putInt("bubbleSize", size).putInt("bubbleAlpha", alpha).apply(); }
    public static void saveBubbleSnap(Context c, boolean v) { prefs(c).edit().putBoolean("bubbleSnap", v).apply(); }
    public static void saveBubbleHide(Context c, boolean v) { prefs(c).edit().putBoolean("bubbleHide", v).apply(); }
    public static void saveBubbleHideDelay(Context c, int v) { prefs(c).edit().putInt("bubbleHideDelay", v).apply(); }
    public static void saveBubblePos(Context c, int x, int y) { prefs(c).edit().putInt("bubbleX", x).putInt("bubbleY", y).apply(); }
    public static String prevVersion(Context c) { return prefs(c).getString("prevVersion", ""); }
    public static void savePrevVersion(Context c, String v) { prefs(c).edit().putString("prevVersion", v).apply(); }
    /** a freshly installed magpie that has not yet proven it can serve the page */
    public static boolean verifyPending(Context c) { return prefs(c).getBoolean("verifyPending", false); }
    public static void saveVerifyPending(Context c, boolean v) { prefs(c).edit().putBoolean("verifyPending", v).commit(); }
    public static boolean cfOn(Context c) { return prefs(c).getBoolean("cfOn", false); }
    public static void saveCfOn(Context c, boolean v) { prefs(c).edit().putBoolean("cfOn", v).apply(); }
    public static boolean tsOn(Context c) { return prefs(c).getBoolean("tsOn", false); }
    public static void saveTsOn(Context c, boolean v) { prefs(c).edit().putBoolean("tsOn", v).apply(); }
    public static String tsAuthKey(Context c) { return prefs(c).getString("tsAuthKey", ""); }
    public static String tsHostname(Context c) { return prefs(c).getString("tsHostname", "magpie-android"); }
    public static void saveTs(Context c, String key, String host) { prefs(c).edit().putString("tsAuthKey", key).putString("tsHostname", host).apply(); }
    public static final int DEFAULT_PROXY_PORT = 3435;
    /** Fixed port for the shell's local DNS proxy, so a copy saved by magpie stays valid across restarts. */
    public static int proxyPort(Context c) { return portOr(prefs(c).getInt("proxyPort", DEFAULT_PROXY_PORT), DEFAULT_PROXY_PORT); }
    public static void saveProxyPort(Context c, int v) { prefs(c).edit().putInt("proxyPort", v).apply(); }
    /** Upstream proxy the shell chains to: "", http://[user:pass@]host:port or socks5://[user:pass@]host:port. */
    public static String upstreamProxy(Context c) { return prefs(c).getString("upstreamProxy", ""); }
    public static void saveUpstreamProxy(Context c, String v) { prefs(c).edit().putString("upstreamProxy", v == null ? "" : v.trim()).apply(); }
    public static boolean mdns(Context c) { return prefs(c).getBoolean("mdns", false); }
    public static void saveMdns(Context c, boolean v) { prefs(c).edit().putBoolean("mdns", v).apply(); }
    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    public static boolean validPort(int i) {
        return i >= 1024 && i <= 65535;
    }

    private MagpiePrefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, 0);
    }

    public static int gatewayPort(Context context) {
        return portOr(prefs(context).getInt(KEY_GATEWAY_PORT, DEFAULT_GATEWAY_PORT), DEFAULT_GATEWAY_PORT);
    }

    public static int webPort(Context context) {
        return portOr(prefs(context).getInt(KEY_WEB_PORT, DEFAULT_WEB_PORT), DEFAULT_WEB_PORT);
    }

    public static boolean lan(Context context) {
        return prefs(context).getBoolean(KEY_LAN, false);
    }

    public static boolean autostart(Context context) {
        return prefs(context).getBoolean(KEY_AUTOSTART, false);
    }

    public static String webKey(Context context) {
        SharedPreferences prefs = prefs(context);
        String string = prefs.getString(KEY_WEB_KEY, null);
        if (string != null && string.length() >= 16) {
            return string;
        }
        String randomKey = randomKey();
        prefs.edit().putString(KEY_WEB_KEY, randomKey).apply();
        return randomKey;
    }

    public static void save(Context context, int i, int i2, boolean z, boolean z2) {
        prefs(context).edit().putInt(KEY_GATEWAY_PORT, i).putInt(KEY_WEB_PORT, i2).putBoolean(KEY_LAN, z).putBoolean(KEY_AUTOSTART, z2).apply();
    }

    private static int portOr(int i, int i2) {
        return validPort(i) ? i : i2;
    }

    private static String randomKey() {
        SecureRandom secureRandom = new SecureRandom();
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".charAt(secureRandom.nextInt("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".length())));
        }
        return sb.toString();
    }
}
