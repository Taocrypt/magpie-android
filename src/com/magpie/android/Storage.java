package com.magpie.android;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Measures what the app keeps on disk, by category, and clears the categories that can be cleared. */
final class Storage {
    static final String CLI = "cli", ROLLBACK = "rollback", CLOUDFLARED = "cloudflared", TAILSCALE = "tailscale",
            LOGS = "logs", EXPORTS = "exports", TMP = "tmp", CACHE = "cache", WEB_CACHE = "webcache",
            WEB_DATA = "webdata", DATA = "data", APK = "apk", OTHER = "other";

    static final class Cat {
        final String key, title, desc;
        final boolean clearable;
        /** null = clear after a plain confirm; otherwise the warning shown before clearing. */
        final String warn;
        final List<File> roots = new ArrayList<>();
        long size;

        Cat(String key, String title, String desc, boolean clearable, String warn) {
            this.key = key;
            this.title = title;
            this.desc = desc;
            this.clearable = clearable;
            this.warn = warn;
        }

        Cat add(File f) {
            roots.add(f);
            return this;
        }
    }

    private Storage() {}

    private static File webviewDir(Context c) {
        return new File(c.getDataDir(), "app_webview");
    }

    private static List<File> webCacheParts(Context c) {
        List<File> l = new ArrayList<>();
        l.add(new File(c.getCacheDir(), "WebView"));
        File def = new File(webviewDir(c), "Default");
        for (String n : new String[]{"Cache", "Code Cache", "GPUCache", "Service Worker/CacheStorage", "Service Worker/ScriptCache", "blob_storage"}) {
            l.add(new File(def, n));
        }
        l.add(new File(webviewDir(c), "GrShaderCache"));
        l.add(new File(webviewDir(c), "ShaderCache"));
        return l;
    }

    /** Builds every category with its size, largest first. Runs on a background thread. */
    static List<Cat> scan(Context c) {
        File bin = MagpieGateway.dir(c, "bin");
        List<Cat> cats = new ArrayList<>();
        cats.add(new Cat(CLI, "magpie 程序", "当前运行的 magpie 命令行，删掉就用不了，不能清除", false, null)
                .add(MagpieGateway.installedBinary(c)));
        Cat rb = new Cat(ROLLBACK, "旧版本备份", "更新时留下的上一版和下载残留；清除后不能「回退到上一版」", true,
                "清除后就不能回退到上一版 magpie 了。");
        rb.add(MagpieGateway.previousBinary(c)).add(new File(bin, "magpie.bad"));
        File[] bins = bin.listFiles();
        if (bins != null) {
            for (File f : bins) {
                String n = f.getName();
                if (n.endsWith(".download") || n.endsWith(".copying") || n.endsWith(".new") || n.endsWith(".tmp")) rb.add(f);
            }
        }
        cats.add(rb);
        cats.add(new Cat(CLOUDFLARED, "cloudflared", "Cloudflare 公网地址用的组件；清除后会关闭公网地址，下次开启时重新下载", true,
                "会关闭 Cloudflare 公网地址并删除 cloudflared，下次开启时重新下载（约 40 MB）。")
                .add(Tunnels.cloudflared(c)));
        cats.add(new Cat(TAILSCALE, "Tailscale", "tailscale 组件和这台设备的登录状态；清除后需要重新下载并登录", true,
                "会关闭 Tailscale、删除组件和登录状态。下次开启要重新下载（约 30 MB）并重新登录，tailnet 里会出现一台新设备。")
                .add(Tunnels.tailscaled(c)).add(Tunnels.tailscale(c)).add(MagpieGateway.dir(c, "tailscale")));
        cats.add(new Cat(LOGS, "日志", "网关、隧道日志和崩溃记录", true, null).add(MagpieGateway.dir(c, "logs")));
        cats.add(new Cat(EXPORTS, "导出文件", "「新版功能」里生成的性能分析、协程快照和工具输出（已存到下载的不受影响）", true, null)
                .add(MagpieGateway.exportDir(c)));
        cats.add(new Cat(TMP, "临时文件", "magpie 和组件下载用的临时目录", true, null).add(MagpieGateway.dir(c, "tmp")));
        Cat cache = new Cat(CACHE, "magpie 缓存", "magpie 的缓存目录和应用缓存，用到时会自动重建", true, null);
        cache.add(MagpieGateway.dir(c, "cache")).add(c.getCodeCacheDir());
        File[] cc = c.getCacheDir().listFiles();
        if (cc != null) for (File f : cc) if (!f.getName().equals("WebView")) cache.add(f);
        cats.add(cache);
        Cat wc = new Cat(WEB_CACHE, "网页缓存", "WebView 的图片、脚本等缓存，不影响登录和设置", true, null);
        for (File f : webCacheParts(c)) wc.add(f);
        cats.add(wc);
        cats.add(new Cat(WEB_DATA, "网页存储", "WebView 的 Cookie 和本地存储（不含 magpie 自己的配置）", true,
                "会清掉网页里的 Cookie 和本地存储，比如网页界面记住的选项。magpie 的服务商、账号和密钥不受影响。")
                .add(webviewDir(c)));
        Cat data = new Cat(DATA, "magpie 配置与数据", "服务商、账号、密钥、插件、会话和用量记录", true,
                "会删除 magpie 的全部配置和数据：服务商、账号、API 密钥、插件、会话和用量记录，相当于重新安装 magpie。网关会重启。此操作无法撤销。");
        for (String n : new String[]{"config", "data", "state", "home"}) data.add(MagpieGateway.dir(c, n));
        cats.add(data);
        Cat apk = new Cat(APK, "安装包与内置 Bun", "APK 本体和内置的 Bun 插件运行时，系统管理，不能清除", false, null);
        apk.add(new File(c.getApplicationInfo().sourceDir));
        String[] splits = c.getApplicationInfo().splitSourceDirs;
        if (splits != null) for (String s : splits) apk.add(new File(s));
        apk.add(new File(c.getApplicationInfo().nativeLibraryDir));
        cats.add(apk);

        long counted = 0;
        String dataRoot = c.getDataDir().getAbsolutePath() + File.separator;
        for (Cat k : cats) {
            if (k.key.equals(WEB_DATA)) continue;
            k.size = sizeOf(k.roots);
            for (File f : k.roots) if (f.getAbsolutePath().startsWith(dataRoot)) counted += size(f);
        }
        Cat web = find(cats, WEB_DATA);
        Cat webc = find(cats, WEB_CACHE);
        long webInDir = 0;
        for (File f : webc.roots) if (f.getAbsolutePath().startsWith(webviewDir(c).getAbsolutePath())) webInDir += size(f);
        web.size = Math.max(0, size(webviewDir(c)) - webInDir);
        counted += web.size;
        Cat other = new Cat(OTHER, "其他", "应用设置、数据库等系统文件，不能清除", false, null);
        other.size = Math.max(0, size(c.getDataDir()) - counted);
        cats.add(other);
        Collections.sort(cats, (a, b) -> Long.compare(b.size, a.size));
        return cats;
    }

    static Cat find(List<Cat> cats, String key) {
        for (Cat k : cats) if (k.key.equals(key)) return k;
        return null;
    }

    static long total(List<Cat> cats) {
        long t = 0;
        for (Cat k : cats) t += k.size;
        return t;
    }

    /** Clears the files side of a category. Process and WebView state is the caller's job. */
    static void clearFiles(Context c, Cat k) {
        switch (k.key) {
            case LOGS:
                File[] logs = MagpieGateway.dir(c, "logs").listFiles();
                if (logs != null) {
                    for (File f : logs) {
                        if (f.getName().endsWith(".1") || f.getName().endsWith(".old") || f.isDirectory()) {
                            delete(f);
                        } else {
                            try (FileOutputStream o = new FileOutputStream(f, false)) {
                                o.flush();
                            } catch (IOException ignored) {
                            }
                        }
                    }
                }
                return;
            case EXPORTS:
            case TMP:
            case DATA:
                for (File r : k.roots) deleteChildren(r);
                return;
            case CACHE:
                for (File r : k.roots) {
                    if (r.equals(MagpieGateway.dir(c, "cache")) || r.equals(c.getCodeCacheDir())) deleteChildren(r);
                    else delete(r);
                }
                return;
            case ROLLBACK:
                for (File r : k.roots) delete(r);
                MagpiePrefs.savePrevVersion(c, "");
                return;
            default:
                if (k.clearable) for (File r : k.roots) delete(r);
        }
    }

    private static long sizeOf(List<File> roots) {
        long t = 0;
        for (File f : roots) t += size(f);
        return t;
    }

    static long size(File f) {
        if (f == null || !f.exists()) return 0;
        try {
            if (Files.isSymbolicLink(f.toPath())) return 0;
        } catch (RuntimeException ignored) {
        }
        if (f.isFile()) return f.length();
        long t = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) t += size(k);
        return t;
    }

    private static void deleteChildren(File dir) {
        File[] kids = dir.listFiles();
        if (kids != null) for (File k : kids) delete(k);
    }

    static void delete(File f) {
        if (f == null || !f.exists()) return;
        boolean link = false;
        try {
            link = Files.isSymbolicLink(f.toPath());
        } catch (RuntimeException ignored) {
        }
        if (f.isDirectory() && !link) deleteChildren(f);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    static String human(long b) {
        if (b < 1024) return b + " B";
        double k = b / 1024.0;
        if (k < 1024) return String.format(Locale.ROOT, "%.0f KB", k);
        double m = k / 1024.0;
        if (m < 1024) return String.format(Locale.ROOT, m < 10 ? "%.1f MB" : "%.0f MB", m);
        return String.format(Locale.ROOT, "%.2f GB", m / 1024.0);
    }
}
