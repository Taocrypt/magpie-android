package com.magpie.android;

import android.content.Context;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MagpieGateway {
    private static final String BINARY = "libmagpie.so";
    private static final String BUN = "libmagpiebun.so";
    private static final long LOG_MAX_BYTES = 2097152;
    private static final String LOG_NAME = "magpie.log";
    private static final String TAG = "MagpieGateway";
    private static Process process;
    private static int restarts;
    private static volatile long startedAt;
    /** True after the user stopped the gateway (menu, capsule, notification, tile); cleared on start. */
    static volatile boolean userStopped;
    private static final Object LOCK = new Object();
    private static final AtomicBoolean stopping = new AtomicBoolean(false);

    private MagpieGateway() {}

    private static long backoff(int i) {
        switch (i) {
            case 1: return 1000L;
            case 2: return 2000L;
            case 3: return 5000L;
            case 4: return 15000L;
            default: return 30000L;
        }
    }

    public static boolean isRunning() {
        synchronized (LOCK) {
            return process != null && process.isAlive();
        }
    }

    /** Blocking (downloads magpie the first time): call off the main thread. */
    public static void start(Context context) {
        final Context app = context.getApplicationContext();
        if (!Updater.installed(app)) {
            if (Updater.ensure(app, null) == null) {
                return;
            }
        }
        synchronized (LOCK) {
            if (process == null || !process.isAlive()) {
                stopping.set(false);
                spawn(app);
            }
        }
    }

    public static void stop() {
        Process p;
        synchronized (LOCK) {
            stopping.set(true);
            p = process;
            process = null;
        }
        if (p == null) {
            return;
        }
        p.destroy();
        try {
            if (!p.waitFor(3L, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static boolean portOpen(String host, int port, int timeout) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeout);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static String gatewayUrl(Context c) {
        return "http://127.0.0.1:" + MagpiePrefs.gatewayPort(c);
    }

    public static String pageUrl(Context c) {
        return "http://127.0.0.1:" + MagpiePrefs.webPort(c) + "/?k=" + MagpiePrefs.webKey(c);
    }

    public static File logFile(Context c) {
        return new File(dir(c, "logs"), LOG_NAME);
    }

    /** the copy magpie runs from: files/bin/magpie, writable, so magpie's own updater can replace it */
    public static File installedBinary(Context c) {
        return new File(dir(c, "bin"), "magpie");
    }

    /** the version that was running before the last update, kept for rollback */
    public static File previousBinary(Context c) {
        return new File(dir(c, "bin"), "magpie.prev");
    }

    public static boolean canRollback(Context c) {
        return previousBinary(c.getApplicationContext()).isFile();
    }

    /** Swaps back to the previous magpie. Blocking. Returns the version now installed, or null. */
    public static synchronized String rollback(Context context, String why) {
        Context c = context.getApplicationContext();
        File prev = previousBinary(c);
        if (!prev.isFile()) return null;
        stop();
        File cur = installedBinary(c);
        File bad = new File(cur.getParentFile(), "magpie.bad");
        bad.delete();
        cur.renameTo(bad);
        if (!prev.renameTo(cur)) {
            bad.renameTo(cur);
            return null;
        }
        bad.delete();
        cur.setExecutable(true, false);
        String v = versionOf(c, cur);
        if (v.isEmpty()) v = MagpiePrefs.prevVersion(c);
        MagpiePrefs.saveInstalledVersion(c, v);
        MagpiePrefs.savePrevVersion(c, "");
        MagpiePrefs.saveVerifyPending(c, false);
        logLine(c, "rolled back to magpie " + v + " (" + why + ")");
        MagpieService.notifyProblem(c, "magpie 已回退到 " + v, why);
        restarts = 0;
        start(c);
        return v;
    }

    /** The binary to run: the downloaded copy in files/bin (null until the first download). */
    public static File binary(Context context) {
        File inst = installedBinary(context.getApplicationContext());
        if (inst.isFile() && !inst.canExecute()) {
            inst.setExecutable(true, false);
        }
        return inst.isFile() ? inst : null;
    }

    private static boolean copyFile(File from, File to) {
        File tmp = new File(to.getParentFile(), to.getName() + ".copying");
        try (InputStream in = new java.io.FileInputStream(from); FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) >= 0) {
                out.write(b, 0, n);
            }
            out.getFD().sync();
        } catch (IOException e) {
            tmp.delete();
            return false;
        }
        tmp.setExecutable(true, false);
        return tmp.renameTo(to);
    }

    /** "magpie 0.1.2124" -> "0.1.2124", or "" */
    public static String versionOf(Context c, File bin) {
        try {
            ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath(), "version");
            pb.redirectErrorStream(true);
            pb.directory(dir(c, "home"));
            Map<String, String> env = pb.environment();
            env.put("HOME", dir(c, "home").getAbsolutePath());
            env.put("XDG_CONFIG_HOME", dir(c, "config").getAbsolutePath());
            env.put("TMPDIR", dir(c, "tmp").getAbsolutePath());
            Process p = pb.start();
            p.getOutputStream().close();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (InputStream in = p.getInputStream()) {
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) >= 0 && buf.size() < 16384) {
                    buf.write(b, 0, n);
                }
            }
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+\\.\\d+\\.\\d+)").matcher(buf.toString("UTF-8"));
            return m.find() ? m.group(1) : "";
        } catch (Exception e) {
            return "";
        }
    }

    static int compareVersions(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long p = i < x.length ? parse(x[i]) : 0, q = i < y.length ? parse(y[i]) : 0;
            if (p != q) {
                return p < q ? -1 : 1;
            }
        }
        return 0;
    }

    private static long parse(String s) {
        try {
            return Long.parseLong(s.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static File bundledBun(Context c) {
        File f = new File(c.getApplicationInfo().nativeLibraryDir, BUN);
        return f.isFile() ? f : null;
    }

    public static boolean hasBundledBun(Context c) {
        return bundledBun(c) != null;
    }

    public static void logLine(Context c, String line) {
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        try (FileOutputStream out = new FileOutputStream(logFile(c), true)) {
            out.write((ts + "  " + line + "\n").getBytes("UTF-8"));
        } catch (IOException e) {
            Log.w(TAG, "could not append to the log", e);
        }
    }

    /** The environment every magpie process (serve or a CLI call) gets. */
    static void environment(Context c, Map<String, String> env) {
        File home = dir(c, "home");
        env.put("HOME", home.getAbsolutePath());
        env.put("XDG_CONFIG_HOME", dir(c, "config").getAbsolutePath());
        env.put("XDG_CACHE_HOME", dir(c, "cache").getAbsolutePath());
        env.put("XDG_DATA_HOME", dir(c, "data").getAbsolutePath());
        env.put("XDG_STATE_HOME", dir(c, "state").getAbsolutePath());
        env.put("TMPDIR", dir(c, "tmp").getAbsolutePath());
        env.put("TMP", dir(c, "tmp").getAbsolutePath());
        env.put("PATH", c.getApplicationInfo().nativeLibraryDir + ":/system/bin:/system/xbin:/product/bin");
        env.put("LANG", "C.UTF-8");
        env.put("MAGPIE_ADDR", (MagpiePrefs.lan(c) ? "0.0.0.0:" : "127.0.0.1:") + MagpiePrefs.gatewayPort(c));
        env.put("MAGPIE_WEB_KEY", MagpiePrefs.webKey(c));
        // The linux/arm64 build has no Android trust store path of its own.
        env.put("SSL_CERT_DIR", "/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts");
        if (MagpiePrefs.compatNet(c)) {
            DnsProxy.setUpstream(MagpiePrefs.upstreamProxy(c));
            int port = DnsProxy.start(MagpiePrefs.proxyPort(c));
            if (port > 0) {
                String proxy = "http://127.0.0.1:" + port;
                env.put("HTTPS_PROXY", proxy);
                env.put("HTTP_PROXY", proxy);
                env.put("https_proxy", proxy);
                env.put("http_proxy", proxy);
                String no = "localhost,127.0.0.1,::1,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,169.254.0.0/16,.local";
                env.put("NO_PROXY", no);
                env.put("no_proxy", no);
            }
        }
        File bun = bundledBun(c);
        if (bun != null) {
            bun.setExecutable(true, false);
            env.put("MAGPIE_BUN", bun.getAbsolutePath());
            env.put("BUN_JSC_useJIT", "0");
            env.put("BUN_JSC_useConcurrentJIT", "0");
        }
        env.remove("LD_PRELOAD");
        env.remove("LD_LIBRARY_PATH");
    }

    public static final class CliResult {
        public final int code;
        public final String output;
        CliResult(int code, String output) { this.code = code; this.output = output; }
    }

    /** Runs `magpie <args>` with the gateway's environment; blocking, call off the main thread. */
    public static CliResult runCli(Context context, long timeoutSec, String... args) {
        Context c = context.getApplicationContext();
        File bin = binary(c);
        if (bin == null) {
            return new CliResult(-1, c.getString(R.string.dl_not_yet));
        }
        bin.setExecutable(true, false);
        List<String> cmd = new ArrayList<>();
        cmd.add(bin.getAbsolutePath());
        cmd.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir(c, "home"));
        pb.redirectErrorStream(true);
        environment(c, pb.environment());
        pb.environment().put("NO_COLOR", "1");
        pb.environment().put("TERM", "dumb");
        try {
            final Process p = pb.start();
            p.getOutputStream().close();
            final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                byte[] b = new byte[8192];
                try (InputStream in = p.getInputStream()) {
                    int n;
                    while ((n = in.read(b)) >= 0) {
                        synchronized (buf) {
                            if (buf.size() < 4 * 1024 * 1024) {
                                buf.write(b, 0, n);
                            }
                        }
                    }
                } catch (IOException ignored) {
                }
            }, "magpie-cli");
            reader.start();
            boolean done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
            }
            reader.join(2000);
            String out;
            synchronized (buf) {
                out = buf.toString("UTF-8");
            }
            out = out.replaceAll("\u001B\\[[0-9;?]*[ -/]*[@-~]", "");
            return new CliResult(done ? p.exitValue() : -2, done ? out : out + "\n(timed out)");
        } catch (IOException e) {
            return new CliResult(-1, String.valueOf(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CliResult(-1, "interrupted");
        }
    }

    public static File exportDir(Context c) {
        return dir(c, "exports");
    }

    private static void spawn(final Context c) {
        File bin = binary(c);
        if (bin == null) {
            logLine(c, "magpie has not been downloaded yet");
            return;
        }
        bin.setExecutable(true, false);
        if (!bin.canExecute()) {
            logLine(c, "the gateway binary cannot be executed: " + bin);
        }
        File logs = dir(c, "logs");
        File log = logFile(c);
        if (log.length() > LOG_MAX_BYTES) {
            File old = new File(logs, "magpie.log.1");
            old.delete();
            log.renameTo(old);
        }
        int gatewayPort = MagpiePrefs.gatewayPort(c);
        int webPort = MagpiePrefs.webPort(c);
        List<String> cmd = new ArrayList<>();
        cmd.add(bin.getAbsolutePath());
        cmd.add("serve");
        cmd.add("--web=127.0.0.1:" + webPort);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir(c, "home"));
        pb.redirectErrorStream(true);
        environment(c, pb.environment());
        if (pb.environment().containsKey("HTTPS_PROXY")) {
            logLine(c, "outbound DNS goes through the shell's proxy " + pb.environment().get("HTTPS_PROXY")
                    + (DnsProxy.hasUpstream() ? " (chained to an upstream proxy)" : ""));
        }
        if (pb.environment().containsKey("MAGPIE_BUN")) {
            logLine(c, "plugins run on the bundled Bun: " + pb.environment().get("MAGPIE_BUN"));
        }
        try {
            Process p = pb.start();
            synchronized (LOCK) {
                process = p;
            }
            startedAt = System.currentTimeMillis();
            if (MagpiePrefs.verifyPending(c)) verify(c, p, webPort);
            logLine(c, "started " + (MagpiePrefs.lan(c) ? "0.0.0.0:" : "127.0.0.1:") + gatewayPort + ", page on 127.0.0.1:" + webPort);
            pump(p, log);
            watch(c, p);
        } catch (IOException e) {
            logLine(c, "could not start the gateway: " + e);
            Log.w(TAG, "could not start the gateway", e);
        }
    }

    /** After an update: the page must come up within 60 s, or we go back to the previous version. */
    private static void verify(final Context c, final Process p, final int webPort) {
        Thread t = new Thread(() -> {
            long end = System.currentTimeMillis() + 60_000L;
            while (System.currentTimeMillis() < end) {
                if (portOpen("127.0.0.1", webPort, 500)) {
                    MagpiePrefs.saveVerifyPending(c, false);
                    logLine(c, "magpie " + MagpiePrefs.installedVersion(c) + " is serving; update confirmed");
                    return;
                }
                if (!MagpiePrefs.verifyPending(c) || stopping.get()) return;
                try { Thread.sleep(1000L); } catch (InterruptedException e) { return; }
            }
            if (MagpiePrefs.verifyPending(c) && !stopping.get() && canRollback(c)) {
                rollback(c, "新版本 60 秒内没能打开网页界面");
            }
        }, "magpie-verify");
        t.setDaemon(true);
        t.start();
    }

    private static void pump(final Process p, final File log) {
        Thread t = new Thread(() -> {
            try (FileOutputStream out = new FileOutputStream(log, true); InputStream in = p.getInputStream()) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) >= 0) {
                    out.write(b, 0, n);
                    out.flush();
                }
            } catch (IOException ignored) {
            }
        }, "magpie-log");
        t.setDaemon(true);
        t.start();
    }

    private static void watch(final Context c, final Process p) {
        Thread t = new Thread(() -> {
            try {
                int code = p.waitFor();
                synchronized (LOCK) {
                    if (process == p) {
                        process = null;
                    }
                }
                if (stopping.get()) {
                    logLine(c, "the gateway stopped");
                    return;
                }
                long lived = System.currentTimeMillis() - startedAt;
                if (lived > 60_000L) restarts = 0;
                restarts++;
                if (MagpiePrefs.verifyPending(c) && restarts >= 3 && canRollback(c)) {
                    rollback(c, "新版本启动后连续退出（code " + code + "）");
                    return;
                }
                if (restarts > 6) {
                    logLine(c, "the gateway keeps exiting (code " + code + "); giving up until it is restarted by hand");
                    MagpieService.notifyProblem(c, "magpie 网关多次启动失败", "退出码 " + code + "，点开查看日志");
                    restarts = 0;
                    return;
                }
                long wait = backoff(restarts);
                logLine(c, "the gateway exited (code " + code + "); starting it again in " + wait + " ms");
                Thread.sleep(wait);
                if (!stopping.get()) {
                    start(c);
                }
            } catch (InterruptedException ignored) {
            }
        }, "magpie-watchdog");
        t.setDaemon(true);
        t.start();
    }

    static File dir(Context c, String name) {
        File f = new File(c.getFilesDir(), name);
        if (!f.isDirectory() && !f.mkdirs()) {
            Log.w(TAG, "could not make " + f);
        }
        return f;
    }
}
