package com.magpie.android;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * A tiny HTTP/HTTPS forward proxy on 127.0.0.1.
 *
 * The newest magpie is a static linux/arm64 Go build (CGO off). On Android it has no
 * /etc/resolv.conf, so Go's own resolver asks 127.0.0.1:53 and every hostname fails.
 * The gateway honours HTTPS_PROXY / HTTP_PROXY, so the shell points those at this proxy:
 * Go hands over the hostname in CONNECT (or an absolute http:// URL), and Java resolves
 * it with Android's own DNS (netd), then pipes the bytes. TLS stays end to end.
 */
public final class DnsProxy {
    private static ServerSocket server;
    private static Thread acceptor;

    private DnsProxy() {}

    // Optional upstream proxy everything is chained through (null type = direct).
    private static volatile String upType;   // "http" or "socks5"
    private static volatile String upHost;
    private static volatile int upPort;
    private static volatile String upUser = "";
    private static volatile String upPass = "";

    /**
     * Sets the upstream proxy from "http://[user:pass@]host:port" or "socks5://[user:pass@]host:port";
     * empty or null clears it. Returns an error message, or null when the value was accepted.
     */
    public static synchronized String setUpstream(String spec) {
        if (spec == null || spec.trim().isEmpty()) {
            upType = null;
            return null;
        }
        try {
            String s = spec.trim();
            if (!s.contains("://")) s = "http://" + s;
            URI u = URI.create(s);
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            String type;
            if (scheme.equals("http") || scheme.equals("https")) type = "http";
            else if (scheme.equals("socks5") || scheme.equals("socks5h") || scheme.equals("socks")) type = "socks5";
            else return "只支持 http:// 或 socks5://";
            String host = u.getHost();
            if (host == null || host.isEmpty()) return "缺少主机地址";
            int port = u.getPort() > 0 ? u.getPort() : (type.equals("http") ? 8080 : 1080);
            String user = "", pass = "";
            String info = u.getRawUserInfo();
            if (info != null && !info.isEmpty()) {
                int c = info.indexOf(':');
                user = java.net.URLDecoder.decode(c < 0 ? info : info.substring(0, c), "UTF-8");
                pass = c < 0 ? "" : java.net.URLDecoder.decode(info.substring(c + 1), "UTF-8");
            }
            upHost = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
            upPort = port;
            upUser = user;
            upPass = pass;
            upType = type;
            return null;
        } catch (Exception e) {
            return "格式不对：" + e.getMessage();
        }
    }

    public static boolean hasUpstream() {
        return upType != null;
    }

    /** A java.net.Proxy that sends the shell's own downloads through the same chain, or NO_PROXY. */
    public static java.net.Proxy javaProxy() {
        int p = port();
        if (upType == null || p <= 0) return java.net.Proxy.NO_PROXY;
        return new java.net.Proxy(java.net.Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", p));
    }

    /** Starts (once) on the preferred port (falls back to any free port) and returns it, or -1. */
    public static synchronized int start(int preferred) {
        if (server != null && !server.isClosed()) {
            return server.getLocalPort();
        }
        try {
            final ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            try {
                ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), preferred > 0 ? preferred : 0), 128);
            } catch (IOException busy) {
                ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 128);
            }
            server = ss;
            acceptor = new Thread(() -> {
                while (!ss.isClosed()) {
                    try {
                        final Socket c = ss.accept();
                        Thread t = new Thread(() -> handle(c), "magpie-proxy-conn");
                        t.setDaemon(true);
                        t.start();
                    } catch (IOException e) {
                        if (ss.isClosed()) {
                            return;
                        }
                    }
                }
            }, "magpie-proxy");
            acceptor.setDaemon(true);
            acceptor.start();
            return ss.getLocalPort();
        } catch (IOException e) {
            return -1;
        }
    }

    public static synchronized int start() {
        return start(0);
    }

    public static synchronized int port() {
        return server == null || server.isClosed() ? -1 : server.getLocalPort();
    }

    public static synchronized void stop() {
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
            }
            server = null;
        }
    }

    private static void handle(Socket client) {
        Socket upstream = null;
        try {
            client.setSoTimeout(30000);
            InputStream cin = client.getInputStream();
            OutputStream cout = client.getOutputStream();
            byte[] head = readHead(cin);
            if (head == null) {
                client.close();
                return;
            }
            String headStr = new String(head, StandardCharsets.ISO_8859_1);
            int eol = headStr.indexOf("\r\n");
            String first = eol < 0 ? headStr : headStr.substring(0, eol);
            String[] parts = first.split(" ");
            if (parts.length < 3) {
                reply(cout, "400 Bad Request");
                client.close();
                return;
            }
            String method = parts[0].toUpperCase(Locale.ROOT);
            if ("CONNECT".equals(method)) {
                String hp = parts[1];
                int colon = hp.lastIndexOf(':');
                String host = colon > 0 ? hp.substring(0, colon) : hp;
                int port = colon > 0 ? Integer.parseInt(hp.substring(colon + 1)) : 443;
                if (host.startsWith("[") && host.endsWith("]")) {
                    host = host.substring(1, host.length() - 1);
                }
                try {
                    upstream = open(host, port);
                } catch (IOException e) {
                    reply(cout, "502 Bad Gateway");
                    client.close();
                    return;
                }
                cout.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                cout.flush();
                client.setSoTimeout(0);
                pipe(client, upstream);
                return;
            }
            // plain http://host[:port]/path through the proxy
            URI uri = URI.create(parts[1]);
            String host = uri.getHost();
            if (host == null) {
                reply(cout, "400 Bad Request");
                client.close();
                return;
            }
            int port = uri.getPort() > 0 ? uri.getPort() : 80;
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            if (uri.getRawQuery() != null) {
                path += "?" + uri.getRawQuery();
            }
            StringBuilder sb = new StringBuilder();
            sb.append(parts[0]).append(' ').append(path).append(' ').append(parts[2]).append("\r\n");
            String[] lines = headStr.substring(eol + 2).split("\r\n");
            for (String l : lines) {
                if (l.isEmpty()) {
                    continue;
                }
                String lower = l.toLowerCase(Locale.ROOT);
                if (lower.startsWith("proxy-connection:") || lower.startsWith("proxy-authorization:") || lower.startsWith("connection:")) {
                    continue;
                }
                sb.append(l).append("\r\n");
            }
            sb.append("Connection: close\r\n\r\n");
            try {
                upstream = open(host, port);
            } catch (IOException e) {
                reply(cout, "502 Bad Gateway");
                client.close();
                return;
            }
            upstream.getOutputStream().write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
            upstream.getOutputStream().flush();
            client.setSoTimeout(0);
            pipe(client, upstream);
        } catch (Exception e) {
            closeQuietly(client);
            closeQuietly(upstream);
        }
    }

    /** Opens a raw byte stream to host:port, directly or through the upstream proxy. */
    static Socket open(String host, int port) throws IOException {
        String type = upType;
        if (type == null) {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, port), 15000);
            return s;
        }
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(upHost, upPort), 15000);
            s.setSoTimeout(15000);
            if (type.equals("http")) httpConnect(s, host, port);
            else socks5Connect(s, host, port);
            s.setSoTimeout(0);
            return s;
        } catch (IOException e) {
            closeQuietly(s);
            throw e;
        }
    }

    private static void httpConnect(Socket s, String host, int port) throws IOException {
        String hp = (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
        StringBuilder sb = new StringBuilder();
        sb.append("CONNECT ").append(hp).append(" HTTP/1.1\r\nHost: ").append(hp).append("\r\n");
        if (!upUser.isEmpty()) {
            String cred = java.util.Base64.getEncoder().encodeToString((upUser + ":" + upPass).getBytes(StandardCharsets.UTF_8));
            sb.append("Proxy-Authorization: Basic ").append(cred).append("\r\n");
        }
        sb.append("\r\n");
        OutputStream out = s.getOutputStream();
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        byte[] head = readHead(s.getInputStream());
        if (head == null) throw new IOException("upstream proxy closed");
        String first = new String(head, StandardCharsets.ISO_8859_1);
        int eol = first.indexOf("\r\n");
        if (eol > 0) first = first.substring(0, eol);
        String[] p = first.split(" ");
        if (p.length < 2 || !p[1].equals("200")) throw new IOException("upstream proxy: " + first);
    }

    private static void socks5Connect(Socket s, String host, int port) throws IOException {
        InputStream in = s.getInputStream();
        OutputStream out = s.getOutputStream();
        boolean auth = !upUser.isEmpty();
        out.write(auth ? new byte[]{5, 2, 0, 2} : new byte[]{5, 1, 0});
        out.flush();
        byte[] r = readN(in, 2);
        if (r[0] != 5) throw new IOException("not a socks5 proxy");
        if ((r[1] & 0xff) == 2) {
            byte[] u = upUser.getBytes(StandardCharsets.UTF_8), pw = upPass.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            b.write(1); b.write(u.length); b.write(u, 0, u.length); b.write(pw.length); b.write(pw, 0, pw.length);
            out.write(b.toByteArray());
            out.flush();
            byte[] a = readN(in, 2);
            if (a[1] != 0) throw new IOException("socks5 auth failed");
        } else if ((r[1] & 0xff) != 0) {
            throw new IOException("socks5 proxy refused auth method");
        }
        ByteArrayOutputStream req = new ByteArrayOutputStream();
        req.write(5); req.write(1); req.write(0);
        byte[] lit = literal(host);
        if (lit != null) {
            req.write(lit.length == 4 ? 1 : 4);
            req.write(lit, 0, lit.length);
        } else {
            byte[] h = host.getBytes(StandardCharsets.UTF_8);
            req.write(3); req.write(h.length); req.write(h, 0, h.length);
        }
        req.write((port >> 8) & 0xff); req.write(port & 0xff);
        out.write(req.toByteArray());
        out.flush();
        byte[] rep = readN(in, 4);
        if (rep[1] != 0) throw new IOException("socks5 connect failed: " + (rep[1] & 0xff));
        int skip;
        switch (rep[3]) {
            case 1: skip = 4; break;
            case 4: skip = 16; break;
            case 3: skip = readN(in, 1)[0] & 0xff; break;
            default: throw new IOException("socks5 bad reply");
        }
        readN(in, skip + 2);
    }

    /** IP literal bytes, or null for a hostname (never does a DNS lookup). */
    private static byte[] literal(String host) {
        if (host.matches("\\d{1,3}(\\.\\d{1,3}){3}") || host.indexOf(':') >= 0) {
            try {
                return InetAddress.getByName(host).getAddress();
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int k = in.read(b, off, n - off);
            if (k < 0) throw new IOException("upstream proxy closed");
            off += k;
        }
        return b;
    }

    private static void reply(OutputStream out, String status) throws IOException {
        out.write(("HTTP/1.1 " + status + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private static byte[] readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(1024);
        int state = 0;
        while (buf.size() < 65536) {
            int b = in.read();
            if (b < 0) {
                return null;
            }
            buf.write(b);
            if ((state == 0 || state == 2) && b == '\r') {
                state++;
            } else if ((state == 1 || state == 3) && b == '\n') {
                state++;
                if (state == 4) {
                    return buf.toByteArray();
                }
            } else {
                state = b == '\r' ? 1 : 0;
            }
        }
        return null;
    }

    private static void pipe(final Socket a, final Socket b) {
        final java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
        Thread t = new Thread(() -> copy(b, a, done), "magpie-proxy-down");
        t.setDaemon(true);
        t.start();
        copy(a, b, done);
    }

    private static void copy(Socket from, Socket to, java.util.concurrent.atomic.AtomicInteger done) {
        byte[] buf = new byte[16384];
        boolean failed = false;
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException e) {
            failed = true;
        }
        try {
            to.shutdownOutput();
        } catch (IOException ignored) {
        }
        if (failed || done.incrementAndGet() >= 2) {
            closeQuietly(from);
            closeQuietly(to);
        }
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** For a quick desktop test: java DnsProxy */
    public static void main(String[] args) throws Exception {
        if (args.length > 1) System.out.println("UP " + setUpstream(args[1]));
        int p = start(args.length > 0 ? Integer.parseInt(args[0]) : 0);
        System.out.println("PORT " + p);
        System.out.flush();
        Thread.sleep(Long.MAX_VALUE);
    }
}
