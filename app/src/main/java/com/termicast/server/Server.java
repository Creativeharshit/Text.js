package com.termicast.server;

import java.io.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/** Zero-dependency HTTP/1.1 + SSE server. Pure Java 8 API (Android safe). */
public class Server {
    public interface Host {
        InputStream asset(String path) throws IOException;
        List<Media> scan();
        String deviceJson();
        default InputStream open(Media media, long offset) throws IOException {
            FileInputStream in = new FileInputStream(media.path);
            try {
                in.getChannel().position(offset);
                return in;
            } catch (IOException e) { in.close(); throw e; }
        }
        default boolean delete(Media media) { return new File(media.path).delete(); }
        default String dlnaScan() { return "[]"; }
        default boolean dlnaPlay(String renderer, String mediaUrl, String mime, String title) { return false; }
        default boolean dlnaControl(String renderer, String command, String position) { return false; }
        default String dlnaState(String renderer) { return "{\"ok\":false}"; }
    }

    public static class Media { public String path, name, type; public long size, dur; }

    private final Host host;
    private ServerSocket ss;
    // Bound concurrent requests: a LAN client must not be able to create an
    // unbounded number of worker threads (SSE connections included).
    private final ExecutorService pool = new ThreadPoolExecutor(2, 8, 30L,
            TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(32),
            new ThreadPoolExecutor.AbortPolicy());
    private final Map<String, Media> media = new ConcurrentHashMap<String, Media>();
    private final Map<OutputStream, String> sse = new ConcurrentHashMap<OutputStream, String>();
    private final Set<String> tokens = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final long started = System.currentTimeMillis();
    private final SecureRandom rnd = new SecureRandom();
    private volatile long bytes;
    private int bad;
    private long lockUntil;
    public final String pin;
    public volatile int port;
    public volatile boolean running;

    public Server(Host host) {
        this.host = host;
        this.pin = String.format(Locale.US, "%06d", rnd.nextInt(1000000));
    }

    public void start(int base) throws IOException {
        IOException last = null;
        ss = null;
        for (int p = base; p < base + 10 && ss == null; p++) {
            ServerSocket t = null;
            try {
                t = new ServerSocket();
                t.setReuseAddress(true);
                t.bind(new InetSocketAddress(p), 64);
                ss = t;
                port = p;
            } catch (IOException e) {
                last = e;
                try { if (t != null) t.close(); } catch (IOException x) { }
            }
        }
        if (ss == null) throw last != null ? last : new IOException("no port");
        running = true;
        Thread th = new Thread(new Runnable() {
            public void run() {
                while (running) {
                    try {
                        final Socket s = ss.accept();
                        try {
                            pool.execute(new Runnable() { public void run() { handle(s); } });
                        } catch (RejectedExecutionException full) {
                            try { s.close(); } catch (IOException ignored) { }
                        }
                    } catch (IOException e) {
                        if (!running) break;
                    }
                }
            }
        }, "tc-accept");
        th.setDaemon(true);
        th.start();
    }

    public void stop() {
        running = false;
        try { if (ss != null) ss.close(); } catch (IOException e) { }
        pool.shutdownNow();
    }

    public static String localIp() {
        String best = null;
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface ni = en.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (Enumeration<InetAddress> ea = ni.getInetAddresses(); ea.hasMoreElements();) {
                    InetAddress a = ea.nextElement();
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        String n = ni.getName();
                        if (n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("eth")) return a.getHostAddress();
                        if (best == null) best = a.getHostAddress();
                    }
                }
            }
        } catch (Exception e) { }
        return best != null ? best : "127.0.0.1";
    }

    // ---------------------------------------------------------------- core
    private void handle(Socket s) {
        try {
            s.setSoTimeout(30000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            String line = readLine(in);
            if (line == null || line.length() == 0) return;
            String[] rl = line.split(" ");
            if (rl.length < 2) return;
            Map<String, String> h = new HashMap<String, String>();
            String l;
            int n = 0;
            while ((l = readLine(in)) != null && l.length() > 0 && n++ < 100) {
                int i = l.indexOf(':');
                if (i > 0) h.put(l.substring(0, i).trim().toLowerCase(Locale.US), l.substring(i + 1).trim());
            }
            byte[] body = new byte[0];
            String cl = h.get("content-length");
            if (cl != null) {
                int len = Math.max(0, Math.min(Integer.parseInt(cl.trim()), 1 << 20));
                body = new byte[len];
                int off = 0;
                while (off < len) {
                    int r = in.read(body, off, len - off);
                    if (r < 0) break;
                    off += r;
                }
            }
            route(s, out, rl[0], rl[1], h, new String(body, "UTF-8"));
        } catch (Exception e) {
            // client gone / bad request
        } finally {
            try { s.close(); } catch (IOException e) { }
        }
    }

    private void route(Socket s, OutputStream out, String method, String target, Map<String, String> h, String body) throws Exception {
        String path = target, qs = "";
        int qi = target.indexOf('?');
        if (qi >= 0) { path = target.substring(0, qi); qs = target.substring(qi + 1); }
        path = urlDecode(path);
        Map<String, String> q = parseQuery(qs);
        boolean local = s.getInetAddress().isLoopbackAddress();
        String ip = s.getInetAddress().getHostAddress();

        if (method.equals("OPTIONS")) { send(out, 204, "text/plain", new byte[0]); return; }
        if (path.indexOf("..") >= 0) { json(out, 403, "{\"error\":\"forbidden\"}"); return; }

        boolean authed = local || has(h.get("x-token")) || has(q.get("k"));

        if (path.equals("/") || path.equals("/index.html")) { asset(out, "web/index.html"); return; }
        if (path.equals("/tv")) { asset(out, "web/tv.html"); return; }
        if (path.startsWith("/web/")) { asset(out, path.substring(1)); return; }

        if (path.equals("/api/ping")) {
            json(out, 200, "{\"ok\":true,\"app\":\"TermiCast\",\"local\":" + local + "}");
            return;
        }
        if (path.equals("/api/pin")) {
            if (!local) { json(out, 403, "{\"error\":\"local only\"}"); return; }
            json(out, 200, "{\"pin\":\"" + pin + "\"}");
            return;
        }
        if (path.equals("/api/pair")) {
            synchronized (this) {
                long now = System.currentTimeMillis();
                if (now < lockUntil) { json(out, 429, "{\"error\":\"locked\",\"wait\":" + ((lockUntil - now) / 1000 + 1) + "}"); return; }
                if (pin.equals(q.get("pin"))) {
                    bad = 0;
                    String t = token();
                    tokens.add(t);
                    json(out, 200, "{\"token\":\"" + t + "\"}");
                } else {
                    if (++bad >= 5) { bad = 0; lockUntil = now + 60000; }
                    json(out, 401, "{\"error\":\"bad pin\"}");
                }
            }
            return;
        }
        if (path.equals("/events")) { events(out, q.get("role"), ip); return; }
        if (path.equals("/api/state") && method.equals("POST")) {
            if (body.startsWith("{")) broadcast("{\"t\":\"state\",\"d\":" + body + "}");
            json(out, 200, "{\"ok\":true}");
            return;
        }
        if (path.startsWith("/m/")) { stream(out, path, h); return; }
        if (path.startsWith("/sub/")) { subtitle(out, path); return; }

        // ---- protected API
        if (path.startsWith("/api/")) {
            if (!authed) { json(out, 401, "{\"error\":\"auth\"}"); return; }
            if (path.equals("/api/info")) {
                json(out, 200, "{\"ip\":" + js(localIp()) + ",\"port\":" + port + ",\"uptime\":" + (System.currentTimeMillis() - started) / 1000
                        + ",\"bytes\":" + bytes + ",\"device\":" + host.deviceJson() + "}");
                return;
            }
            if (path.equals("/api/dlna/scan")) { json(out, 200, host.dlnaScan()); return; }
            if (path.equals("/api/dlna/play") && method.equals("POST")) {
                String id = q.get("media");
                Media m = media.get(id);
                if (m == null) { json(out, 404, "{\"ok\":false,\"error\":\"media not scanned\"}"); return; }
                String url = "http://" + localIp() + ":" + port + "/m/" + id;
                boolean ok = host.dlnaPlay(q.get("renderer"), url, mime(m.name), m.name);
                json(out, ok ? 200 : 502, "{\"ok\":" + ok + "}"); return;
            }
            if (path.equals("/api/dlna/control") && method.equals("POST")) {
                boolean ok = host.dlnaControl(q.get("renderer"), q.get("action"), q.get("position"));
                json(out, ok ? 200 : 502, "{\"ok\":" + ok + "}"); return;
            }
            if (path.equals("/api/dlna/state")) { json(out, 200, host.dlnaState(q.get("renderer"))); return; }
            if (path.equals("/api/scan")) { scan(out); return; }
            if (path.equals("/api/clients")) {
                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                for (String v : sse.values()) {
                    int i = v.indexOf('|');
                    if (!first) sb.append(',');
                    first = false;
                    sb.append("{\"role\":").append(js(v.substring(0, i))).append(",\"ip\":").append(js(v.substring(i + 1))).append('}');
                }
                json(out, 200, sb.append(']').toString());
                return;
            }
            if (path.equals("/api/send") && method.equals("POST")) {
                if (body.startsWith("{")) broadcast("{\"t\":\"cmd\"," + body.substring(1));
                else {
                    StringBuilder sb = new StringBuilder("{\"t\":\"cmd\"");
                    for (Map.Entry<String, String> e : q.entrySet())
                        if (!e.getKey().equals("k")) sb.append(',').append(js(e.getKey())).append(':').append(js(e.getValue()));
                    broadcast(sb.append('}').toString());
                }
                json(out, 200, "{\"ok\":true}");
                return;
            }
            if (path.equals("/api/delete") && method.equals("POST")) {
                Media m = media.get(q.get("id"));
                if (m == null) { json(out, 403, "{\"error\":\"not a scanned file\"}"); return; }
                boolean ok = host.delete(m);
                if (ok) media.remove(q.get("id"));
                System.out.println("[delete] " + m.path + " -> " + ok);
                json(out, 200, "{\"ok\":" + ok + "}");
                return;
            }
        }
        json(out, 404, "{\"error\":\"not found\"}");
    }

    // ---------------------------------------------------------------- handlers
    private void scan(OutputStream out) throws IOException {
        List<Media> list = host.scan();
        StringBuilder sb = new StringBuilder("[");
        int n = 0;
        for (Media m : list) {
            if (n >= 5000) break;
            String id = b64url(m.path);
            media.put(id, m);
            if (n++ > 0) sb.append(',');
            sb.append("{\"id\":").append(js(id)).append(",\"name\":").append(js(m.name)).append(",\"type\":").append(js(m.type))
              .append(",\"size\":").append(m.size).append(",\"dur\":").append(m.dur).append('}');
        }
        json(out, 200, sb.append(']').toString());
    }

    private void events(OutputStream out, String role, String ip) throws Exception {
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nAccess-Control-Allow-Origin: *\r\nConnection: keep-alive\r\n\r\n: hi\n\n").getBytes("UTF-8"));
        out.flush();
        sse.put(out, (role == null ? "ctl" : role) + "|" + ip);
        broadcast("{\"t\":\"clients\"}");
        try {
            while (running) {
                Thread.sleep(15000);
                synchronized (out) { out.write(": ka\n\n".getBytes("UTF-8")); out.flush(); }
            }
        } catch (Exception e) {
        } finally {
            sse.remove(out);
            broadcast("{\"t\":\"clients\"}");
        }
    }

    private void broadcast(String json) {
        byte[] b = ("data: " + json + "\n\n").getBytes();
        for (OutputStream o : sse.keySet()) {
            try { synchronized (o) { o.write(b); o.flush(); } } catch (Exception e) { sse.remove(o); }
        }
    }

    private void subtitle(OutputStream out, String path) throws IOException {
        String[] p = path.split("/");
        Media m = p.length > 2 ? media.get(p[2]) : null;
        if (m == null || !"subtitle".equals(m.type)) { json(out, 404, "{\"error\":\"subtitle not registered\"}"); return; }
        if (m.size > 2 * 1024 * 1024) { json(out, 413, "{\"error\":\"subtitle too large\"}"); return; }
        InputStream in = host.open(m, 0);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try { byte[] buf = new byte[4096]; int n; while ((n = in.read(buf)) > 0) { if (b.size() + n > 2 * 1024 * 1024) { json(out, 413, "{\"error\":\"subtitle too large\"}"); return; } b.write(buf, 0, n); } }
        finally { in.close(); }
        String text = new String(b.toByteArray(), "UTF-8").replace("\r", "");
        if (m.name.toLowerCase(Locale.US).endsWith(".srt")) text = "WEBVTT\n\n" + text.replace(',', '.');
        if (!text.startsWith("WEBVTT")) text = "WEBVTT\n\n" + text;
        send(out, 200, "text/vtt; charset=utf-8", text.getBytes("UTF-8"));
    }

    private void stream(OutputStream out, String path, Map<String, String> h) throws IOException {
        String[] p = path.split("/");
        Media m = p.length > 2 ? media.get(p[2]) : null;
        if (m == null) { json(out, 403, "{\"error\":\"not registered\"}"); return; }
        long len = m.size, start = 0, end = len - 1;
        int code = 200;
        String r = h.get("range");
        if (r != null && r.startsWith("bytes=")) {
            try {
                String[] x = r.substring(6).split("-", 2);
                if (x.length != 2 || r.indexOf(',') >= 0) throw new IllegalArgumentException("unsupported range");
                if (x[0].trim().length() > 0) {
                    start = Long.parseLong(x[0].trim());
                    if (x.length > 1 && x[1].trim().length() > 0) end = Math.min(end, Long.parseLong(x[1].trim()));
                } else {
                    long suffix = Long.parseLong(x[1].trim());
                    if (suffix <= 0) throw new IllegalArgumentException("invalid suffix range");
                    start = Math.max(0, len - suffix);
                }
                if (start < 0 || end < start) throw new IllegalArgumentException("invalid range");
                code = 206;
            } catch (Exception e) {
                out.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + len
                        + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                return;
            }
        }
        if (start > end || start >= len) {
            out.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + len + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes());
            return;
        }
        String head = "HTTP/1.1 " + code + (code == 206 ? " Partial Content" : " OK") + "\r\nContent-Type: " + mime(m.name)
                + "\r\nAccept-Ranges: bytes\r\nContent-Length: " + (end - start + 1)
                + (code == 206 ? "\r\nContent-Range: bytes " + start + "-" + end + "/" + len : "")
                + "\r\nAccess-Control-Allow-Origin: *\r\ntransferMode.dlna.org: Streaming\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        InputStream source = host.open(m, start);
        try {
            byte[] buf = new byte[65536];
            long left = end - start + 1;
            while (left > 0) {
                int n = source.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) break;
                out.write(buf, 0, n);
                left -= n;
                bytes += n;
            }
            out.flush();
        } catch (IOException e) {
            // seek/abort by player - normal
        } finally {
            source.close();
        }
    }

    private void asset(OutputStream out, String name) throws IOException {
        InputStream in = host.asset(name);
        if (in == null) { json(out, 404, "{\"error\":\"no asset\"}"); return; }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        send(out, 200, mime(name), bo.toByteArray());
    }

    // ---------------------------------------------------------------- utils
    private boolean has(String t) { return t != null && tokens.contains(t); }

    private String token() {
        byte[] b = new byte[16];
        rnd.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    private void json(OutputStream o, int code, String s) throws IOException { send(o, code, "application/json; charset=utf-8", s.getBytes("UTF-8")); }

    private void send(OutputStream o, int code, String ct, byte[] b) throws IOException {
        String head = "HTTP/1.1 " + code + " " + reason(code) + "\r\nContent-Type: " + ct + "\r\nContent-Length: " + b.length
                + "\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: *\r\nAccess-Control-Allow-Methods: GET,POST,OPTIONS\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        o.write(head.getBytes("UTF-8"));
        o.write(b);
        o.flush();
    }

    private static String reason(int c) {
        switch (c) {
            case 200: return "OK"; case 204: return "No Content"; case 206: return "Partial Content";
            case 401: return "Unauthorized"; case 403: return "Forbidden"; case 404: return "Not Found";
            case 413: return "Payload Too Large"; case 429: return "Too Many Requests"; default: return "Error";
        }
    }

    static String mime(String n) {
        String s = n.toLowerCase(Locale.US);
        if (s.endsWith(".html")) return "text/html; charset=utf-8";
        if (s.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (s.endsWith(".css")) return "text/css; charset=utf-8";
        if (s.endsWith(".json")) return "application/json";
        if (s.endsWith(".mp4") || s.endsWith(".m4v")) return "video/mp4";
        if (s.endsWith(".mkv")) return "video/x-matroska";
        if (s.endsWith(".webm")) return "video/webm";
        if (s.endsWith(".avi")) return "video/x-msvideo";
        if (s.endsWith(".mov")) return "video/quicktime";
        if (s.endsWith(".mp3")) return "audio/mpeg";
        if (s.endsWith(".m4a") || s.endsWith(".aac")) return "audio/mp4";
        if (s.endsWith(".flac")) return "audio/flac";
        if (s.endsWith(".ogg") || s.endsWith(".opus")) return "audio/ogg";
        if (s.endsWith(".wav")) return "audio/wav";
        if (s.endsWith(".vtt")) return "text/vtt";
        return "application/octet-stream";
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r' && sb.length() < 8192) sb.append((char) c);
        }
        if (c < 0 && sb.length() == 0) return null;
        return sb.toString();
    }

    private static Map<String, String> parseQuery(String qs) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        if (qs == null || qs.length() == 0) return m;
        for (String kv : qs.split("&")) {
            int i = kv.indexOf('=');
            if (i < 0) m.put(urlDecode(kv), "");
            else m.put(urlDecode(kv.substring(0, i)), urlDecode(kv.substring(i + 1)));
        }
        return m;
    }

    private static String urlDecode(String s) {
        try { return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8"); } catch (Exception e) { return s; }
    }

    static String js(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    static String b64url(String s) {
        final String A = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        byte[] b;
        try { b = s.getBytes("UTF-8"); } catch (Exception e) { b = s.getBytes(); }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i += 3) {
            int v = (b[i] & 0xff) << 16 | (i + 1 < b.length ? (b[i + 1] & 0xff) << 8 : 0) | (i + 2 < b.length ? (b[i + 2] & 0xff) : 0);
            sb.append(A.charAt(v >> 18 & 63)).append(A.charAt(v >> 12 & 63));
            if (i + 1 < b.length) sb.append(A.charAt(v >> 6 & 63));
            if (i + 2 < b.length) sb.append(A.charAt(v & 63));
        }
        return sb.toString();
    }
}
