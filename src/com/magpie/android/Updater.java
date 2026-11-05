package com.magpie.android;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The APK carries no magpie binary. This fetches the official magpie-cli-android-arm64
 * release into files/bin/magpie (first run and every update), checks its SHA-256, and swaps
 * it in atomically. magpie's own updater can replace that file too, since the folder is ours.
 */
public final class Updater {
    public static final String ASSET = "magpie-cli-android-arm64";
    private static final String FEED = "https://usemagpie.ai/api/latest";
    private static final String GITHUB_API = "https://api.github.com/repos/yetone/magpie-releases/releases/latest";

    public interface Progress {
        void onProgress(String stage, int percent);
    }

    public static final class Release {
        public final String version, url, sha256, notes;
        public final long size;
        Release(String version, String url, String sha256, long size, String notes) {
            this.version = version;
            this.url = url;
            this.sha256 = sha256;
            this.size = size;
            this.notes = notes;
        }
    }

    // shared state so any screen can show what the download is doing
    public static volatile String stage = "";
    public static volatile int percent = -1;
    public static volatile boolean busy;
    private static final Object LOCK = new Object();

    private Updater() {}

    /** Asks usemagpie.ai (honours the stable/every channel), falling back to GitHub's API. */
    public static Release latest(Context c) throws IOException {
        IOException first = null;
        try {
            String feed = FEED + ("stable".equals(MagpiePrefs.updateChannel(c)) ? "?channel=stable" : "");
            JSONObject o = new JSONObject(get(feed));
            JSONObject a = o.getJSONObject("assets").getJSONObject(ASSET);
            return new Release(o.getString("version").replaceFirst("^v", ""), a.getString("url"),
                    a.optString("sha256", ""), a.optLong("size", -1), o.optString("notes", ""));
        } catch (Exception e) {
            first = e instanceof IOException ? (IOException) e : new IOException(e.toString());
        }
        try {
            JSONObject o = new JSONObject(get(GITHUB_API));
            String version = o.getString("tag_name").replaceFirst("^v", "");
            JSONArray assets = o.getJSONArray("assets");
            String url = null, sums = null;
            long size = -1;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (ASSET.equals(a.getString("name"))) {
                    url = a.getString("browser_download_url");
                    size = a.optLong("size", -1);
                } else if ("SHA256SUMS".equals(a.getString("name"))) {
                    sums = a.getString("browser_download_url");
                }
            }
            if (url == null) {
                throw new IOException("the release has no " + ASSET);
            }
            String sha = "";
            if (sums != null) {
                for (String line : get(mirrored(c, sums)).split("\n")) {
                    String[] p = line.trim().split("\\s+");
                    if (p.length == 2 && p[1].replace("*", "").equals(ASSET)) {
                        sha = p[0];
                    }
                }
            }
            return new Release(version, url, sha, size, o.optString("body", ""));
        } catch (Exception e) {
            throw new IOException("update check failed: " + first.getMessage() + " / " + e.getMessage());
        }
    }

    public static boolean installed(Context c) {
        File f = MagpieGateway.installedBinary(c);
        return f.isFile() && f.length() > 1_000_000 && f.canExecute();
    }

    /** Downloads and installs; returns the installed file. Blocking, never on the main thread. */
    public static File install(Context c, Release r, Progress p) throws IOException {
        synchronized (LOCK) {
            busy = true;
            try {
                File target = MagpieGateway.installedBinary(c);
                File tmp = new File(target.getParentFile(), "magpie.download");
                report(p, c.getString(R.string.dl_downloading, r.version), 0);
                String url = mirrored(c, r.url);
                MessageDigest md;
                try {
                    md = MessageDigest.getInstance("SHA-256");
                } catch (Exception e) {
                    throw new IOException(e);
                }
                HttpURLConnection conn = open(url);
                int code = conn.getResponseCode();
                if (code / 100 != 2) {
                    throw new IOException("HTTP " + code + " from " + url);
                }
                long total = conn.getContentLengthLong() > 0 ? conn.getContentLengthLong() : r.size;
                long got = 0;
                int last = -1;
                try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = in.read(b)) >= 0) {
                        out.write(b, 0, n);
                        md.update(b, 0, n);
                        got += n;
                        int pc = total > 0 ? (int) (got * 100 / total) : -1;
                        if (pc != last) {
                            last = pc;
                            report(p, c.getString(R.string.dl_downloading, r.version), pc);
                        }
                    }
                    out.getFD().sync();
                } finally {
                    conn.disconnect();
                }
                String sha = hex(md.digest());
                if (!r.sha256.isEmpty() && !r.sha256.equalsIgnoreCase(sha)) {
                    tmp.delete();
                    throw new IOException("SHA-256 mismatch: expected " + r.sha256 + ", got " + sha);
                }
                report(p, c.getString(R.string.dl_installing, r.version), 100);
                tmp.setExecutable(true, false);
                if (target.isFile() && MagpieGateway.versionOf(c, tmp).isEmpty()) {
                    tmp.delete();
                    throw new IOException("新版本在这台设备上无法运行，已保留当前版本");
                }
                File prev = MagpieGateway.previousBinary(c);
                boolean kept = false;
                if (target.isFile()) {
                    prev.delete();
                    kept = target.renameTo(prev);
                    if (kept) MagpiePrefs.savePrevVersion(c, MagpiePrefs.installedVersion(c));
                }
                if (!tmp.renameTo(target)) {
                    if (kept) prev.renameTo(target);
                    tmp.delete();
                    throw new IOException("could not move the download into place");
                }
                target.setExecutable(true, false);
                MagpiePrefs.saveInstalledVersion(c, r.version);
                MagpiePrefs.saveVerifyPending(c, kept);
                MagpieGateway.logLine(c, "installed magpie " + r.version + " (sha256 " + sha + ")");
                report(p, c.getString(R.string.dl_done, r.version), 100);
                return target;
            } finally {
                busy = false;
            }
        }
    }

    /** The first run: make sure there is a binary. Returns null when it could not get one. */
    public static File ensure(Context c, Progress p) {
        if (installed(c)) {
            return MagpieGateway.installedBinary(c);
        }
        try {
            report(p, c.getString(R.string.dl_checking), -1);
            Release r = latest(c);
            return install(c, r, p);
        } catch (IOException e) {
            MagpieGateway.logLine(c, "could not download magpie: " + e.getMessage());
            report(p, c.getString(R.string.dl_failed, String.valueOf(e.getMessage())), -2);
            return null;
        }
    }

    private static void report(Progress p, String s, int pc) {
        stage = s;
        percent = pc;
        if (p != null) {
            p.onProgress(s, pc);
        }
    }

    static String mirrored(Context c, String url) {
        String m = MagpiePrefs.mirror(c).trim();
        if (m.isEmpty() || !url.startsWith("https://github.com/")) {
            return url;
        }
        return m.endsWith("/") ? m + url : m + "/" + url;
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection(DnsProxy.javaProxy());
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "magpie-android-shell");
        return conn;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection conn = open(url);
        conn.setRequestProperty("Accept", "application/json");
        try {
            int code = conn.getResponseCode();
            if (code / 100 != 2) {
                throw new IOException("HTTP " + code + " from " + url);
            }
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) >= 0) {
                    out.write(b, 0, n);
                }
                return out.toString("UTF-8");
            }
        } finally {
            conn.disconnect();
        }
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte x : d) {
            sb.append(String.format(Locale.ROOT, "%02x", x));
        }
        return sb.toString();
    }
}
