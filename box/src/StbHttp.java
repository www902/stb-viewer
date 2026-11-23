// StbHttp: LAN viewer served directly from the STB (no PC relay).
// HTTPS(8443)+HTTP(8080): static page, /h264 scrcpy fan-out (abstract socket, no adb),
// /audio in-process REMOTE_SUBMIX loopback, /key /tap /swipe /text /launch /apps /info.
// Run on device: CLASSPATH=/data/local/tmp/stbhttp.dex app_process / StbHttp
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocketFactory;

public class StbHttp {
  static final int PORT = 8080, SPORT = 8443;
  static String picks(String... c) {
    for (int i = 0; i < c.length; i++) if (new java.io.File(c[i]).exists()) return c[i];
    return c[0];
  }
  static final String SYS = "/system/stbviewer/";
  static final String WEB = picks(SYS + "index.html", "/data/local/tmp/stbweb/index.html");
  static final String JAR = "/dev/scrcpy-server.jar";
  static final String JARBAK = picks(SYS + "scrcpy-server.jar", "/data/local/tmp/scrcpy-server.jar");
  static final String KEYDER = picks(SYS + "stb-key.der", "/data/local/tmp/stb-key.der");
  static final String CERTDER = picks(SYS + "stb-cert.der", "/data/local/tmp/stb-cert.der");
  static final int SR = 48000;

  // ---------------- video ----------------
  static final Object VL = new Object();
  static boolean vRunning = false;
  static volatile boolean vReady = false;
  static volatile String vCodec = null;
  static volatile int encW = 1280, encH = 720;
  static volatile byte[] sps = null, pps = null;
  static final CopyOnWriteArrayList<Sink> vClients = new CopyOnWriteArrayList<Sink>();

  static void startVideo() {
    synchronized (VL) {
      if (vRunning) return;
      vRunning = true;
      new Thread(new Runnable() { public void run() { videoCycle(); } }).start();
    }
  }

  static void videoCycle() {
    while (true) {
      vReady = false; vCodec = null; sps = null; pps = null;
      try {
        sh("pkill -9 -f com.genymobile.scrcpy");
        Thread.sleep(300);
        if (!new File(JAR).exists() && new File(JARBAK).exists()) sh("cp " + JARBAK + " " + JAR);
        if (!new File(JAR).exists()) { log("[v] scrcpy jar missing at " + JAR); Thread.sleep(3000); continue; }
        log("[v] spawning scrcpy-server");
        shNoWait("CLASSPATH=" + JAR + " app_process / com.genymobile.scrcpy.Server 4.1 log_level=warn audio=false control=false tunnel_forward=true max_size=1280 max_fps=30 video_bit_rate=4000000 video_codec_options=i-frame-interval=1 >/dev/null 2>&1");
        LocalSocket ls = null;
        for (int i = 0; i < 25 && ls == null; i++) {
          try {
            ls = new LocalSocket();
            ls.connect(new LocalSocketAddress("scrcpy", LocalSocketAddress.Namespace.ABSTRACT));
          } catch (IOException e) {
            ls = null;
            Thread.sleep(400);
          }
        }
        if (ls == null) { log("[v] abstract socket never up"); sh("pkill -9 -f com.genymobile.scrcpy"); Thread.sleep(1500); if (vClients.isEmpty()) break; else continue; }
        try { ls.setSocketTimeout(4000); } catch (Throwable ig) {}
        DataInputStream in = new DataInputStream(new BufferedInputStream(ls.getInputStream(), 65536));
        in.read();                       // dummy byte
        byte[] name = new byte[64];
        in.readFully(name);
        byte[] p4 = new byte[4];
        in.readFully(p4);
        String tag = new String(p4, "US-ASCII");
        int hpos = 0;
        if (tag.equals("h264") || tag.equals("h265") || tag.equals("av01") || tag.equals("opus")) {
          log("[v] handshake codecID=" + tag);
        } else {
          log("[v] handshake no codecID");
          hpos = 4;
        }
        long lastFrame = System.currentTimeMillis();
        long lastWithClients = System.currentTimeMillis();
        byte[] hdr = new byte[12];
        if (hpos > 0) System.arraycopy(p4, 0, hdr, 0, 4);
        log("[v] streaming");
        while (true) {
          if (hpos < 12) in.readFully(hdr, hpos, 12 - hpos);
          hpos = 0;
          long q = be64(hdr, 0);
          if ((q & (1L << 63)) != 0) {  // session meta: flags|w|h (no payload)
            encW = be32(hdr, 4); encH = be32(hdr, 8);
            continue;
          }
          int size = be32(hdr, 8) & 0x7fffffff;
          byte[] pay = new byte[size];
          in.readFully(pay);
          lastFrame = System.currentTimeMillis();
          if ((q & (1L << 62)) != 0) scanNals(pay); // config frame
          byte[] frame = new byte[12 + size];
          System.arraycopy(hdr, 0, frame, 0, 12);
          System.arraycopy(pay, 0, frame, 12, size);
          for (Sink c : vClients) c.push(frame);
          reap(vClients);
          if (vClients.isEmpty()) {
            if (System.currentTimeMillis() - lastWithClients > 15000) { log("[v] idle -> stop"); break; }
          } else {
            lastWithClients = System.currentTimeMillis();
          }
          if (vCodec == null && sps != null && pps != null) {
            vCodec = "avc1." + hex2(sps[1]) + hex2(sps[2]) + hex2(sps[3]);
            vReady = true;
            log("[v] ready codec=" + vCodec + " " + encW + "x" + encH);
          }
        }
        try { ls.close(); } catch (Throwable ig) {}
      } catch (Throwable t) {
        log("[v] cycle err: " + t);
      }
      sh("pkill -9 -f com.genymobile.scrcpy");
      if (vClients.isEmpty()) {
        log("[v] no clients -> stop cycle");
        synchronized (VL) { vRunning = false; }
        return;
      }
      vReady = false; vCodec = null; sps = null; pps = null;
      try { Thread.sleep(1500); } catch (InterruptedException ig) {}
    }
    synchronized (VL) { vRunning = false; }
  }

  // ---------------- audio ----------------
  static final Object AL = new Object();
  static boolean aRunning = false;
  static final CopyOnWriteArrayList<Sink> aClients = new CopyOnWriteArrayList<Sink>();

  static void startAudio() {
    synchronized (AL) {
      if (aRunning) return;
      aRunning = true;
      new Thread(new Runnable() { public void run() { audioLoop(); } }).start();
    }
  }

  static void audioLoop() {
    byte[] buf = new byte[4800]; // 50 ms
    while (!aClients.isEmpty()) {
      AudioRecord r = null;
      try {
        r = makeRecord(8, 192000); // REMOTE_SUBMIX
        if (r == null || r.getState() != AudioRecord.STATE_INITIALIZED) {
          log("[a] record init failed");
          if (r != null) try { r.release(); } catch (Throwable ig) {}
          Thread.sleep(2000);
          continue;
        }
        r.startRecording();
        log("[a] recording submix");
        while (!aClients.isEmpty()) {
          int got = r.read(buf, 0, buf.length);
          if (got <= 0) break;
          byte[] cp = Arrays.copyOf(buf, got);
          for (Sink c : aClients) c.push(cp);
          reap(aClients);
        }
        try { r.stop(); } catch (Throwable ig) {}
        r.release();
      } catch (Throwable t) {
        log("[a] loop err: " + t);
        if (r != null) try { r.release(); } catch (Throwable ig) {}
        try { Thread.sleep(1500); } catch (InterruptedException ig) {}
      }
      try { Thread.sleep(500); } catch (InterruptedException ig) {}
    }
    log("[a] no clients -> stop recording");
    synchronized (AL) { aRunning = false; }
  }

  // reflection-built AudioRecord (same trick proven by Rec.java on this firmware)
  static AudioRecord makeRecord(int src, int bufSize) throws Exception {
    java.lang.reflect.Constructor<?> ctor = AudioRecord.class.getDeclaredConstructor(long.class);
    ctor.setAccessible(true);
    AudioRecord r = (AudioRecord) ctor.newInstance(Long.valueOf(0L));
    Object attrs = null;
    try {
      Object b = Class.forName("android.media.AudioAttributes$Builder").newInstance();
      java.lang.reflect.Method m = b.getClass().getDeclaredMethod("setInternalCapturePreset", int.class);
      m.setAccessible(true);
      m.invoke(b, Integer.valueOf(src));
      attrs = b.getClass().getMethod("build").invoke(b);
    } catch (Throwable t) { log("[a] attrs build failed: " + t); }
    java.lang.reflect.Method setup = AudioRecord.class.getDeclaredMethod("native_setup",
        Object.class, Object.class, int[].class, int.class, int.class, int.class, int.class,
        int[].class, String.class, long.class);
    setup.setAccessible(true);
    int[] rates = new int[] { SR };
    int[] session = new int[] { 0 };
    Object st = setup.invoke(r, new java.lang.ref.WeakReference<AudioRecord>(r), attrs, rates,
        Integer.valueOf(AudioFormat.CHANNEL_IN_STEREO), Integer.valueOf(0),
        Integer.valueOf(AudioFormat.ENCODING_PCM_16BIT), Integer.valueOf(bufSize),
        session, "com.android.shell", Long.valueOf(0L));
    int status = ((Integer) st).intValue();
    if (status == 0) {
      setField(r, "mState", 1);
      setField(r, "mSessionId", session[0]);
      setField(r, "mSampleRate", rates[0]);
      if (attrs != null) {
        try {
          java.lang.reflect.Field f = AudioRecord.class.getDeclaredField("mAudioAttributes");
          f.setAccessible(true);
          f.set(r, attrs);
        } catch (Throwable ig) {}
      }
    }
    return r;
  }

  static void setField(Object o, String name, int value) {
    try {
      java.lang.reflect.Field f = AudioRecord.class.getDeclaredField(name);
      f.setAccessible(true);
      f.setInt(o, value);
    } catch (Throwable t) { log("[a] setField " + name + " failed: " + t); }
  }

  // ---------------- fan-out sink ----------------
  static class Sink {
    final OutputStream os;
    final java.util.ArrayDeque<byte[]> q = new java.util.ArrayDeque<byte[]>();
    final int maxq;
    volatile boolean alive = true;
    Sink(OutputStream os, int maxq) {
      this.os = os; this.maxq = maxq;
      Thread th = new Thread(new Runnable() { public void run() { pump(); } });
      th.setDaemon(true);
      th.start();
    }
    void push(byte[] b) {
      if (!alive) return;
      synchronized (q) {
        if (q.size() > maxq) return; // slow client: drop
        q.add(b);
        q.notifyAll();
      }
    }
    void pump() {
      try {
        while (alive) {
          byte[] b;
          synchronized (q) {
            while (q.isEmpty() && alive) q.wait();
            if (!alive) return;
            b = q.poll();
          }
          if (b != null) { os.write(b); os.flush(); }
        }
      } catch (Throwable t) { alive = false; }
    }
    void close() {
      alive = false;
      try { os.close(); } catch (Throwable ig) {}
    }
  }

  static void reap(CopyOnWriteArrayList<Sink> list) {
    for (Sink s : list) if (!s.alive) list.remove(s);
  }

  // ---------------- apps ----------------
  static long appsT = 0;
  static String appsJson = "[]";
  static final Object APPL = new Object();
  static final Pattern PKG = Pattern.compile("package:([^\\r\\n]+)");
  static final Pattern KW = Pattern.compile("launcher|home|tv|video|magi|wiki|cmhi|itv|pplive|cntv|youtube|mobile|cmcc|skworth|skyworth|cetc|aijia");

  static String getApps() {
    synchronized (APPL) {
      if (System.currentTimeMillis() - appsT < 300000 && !appsJson.equals("[]")) return appsJson;
      java.util.ArrayList<String> pkgs = new java.util.ArrayList<String>();
      collectPkgs("pm list packages -3", null, pkgs);
      collectPkgs("pm list packages -s", KW, pkgs);
      StringBuilder sb = new StringBuilder("[");
      for (String p : pkgs) {
        String out = shOut("cmd package resolve-activity --brief " + p);
        String[] lines = out.split("\n");
        String last = lines.length > 0 ? lines[lines.length - 1].trim() : "";
        if (last.contains("/") && !last.contains("null")) {
          if (sb.length() > 1) sb.append(',');
          sb.append("{\"pkg\":\"").append(p).append("\",\"act\":\"").append(last).append("\"}");
        }
      }
      sb.append(']');
      appsJson = sb.toString();
      appsT = System.currentTimeMillis();
      return appsJson;
    }
  }

  static void collectPkgs(String cmd, Pattern filt, java.util.ArrayList<String> out) {
    String s = shOut(cmd);
    Matcher m = PKG.matcher(s);
    while (m.find()) {
      String p = m.group(1);
      if (filt == null || filt.matcher(p).find()) out.add(p);
    }
  }

  // ---------------- shell helpers ----------------
  static int sh(String cmd) {
    try { return Runtime.getRuntime().exec(new String[] { "sh", "-c", cmd }).waitFor(); }
    catch (Throwable t) { return -1; }
  }

  // fire-and-forget (for long-running spawns: never waitFor)
  static void shNoWait(String cmd) {
    try { Runtime.getRuntime().exec(new String[] { "sh", "-c", cmd }); }
    catch (Throwable t) { log("[sh] fail: " + t); }
  }

  static String shOut(String cmd) {
    try {
      Process p = Runtime.getRuntime().exec(new String[] { "sh", "-c", cmd });
      ByteArrayOutputStream bo = new ByteArrayOutputStream();
      InputStream is = p.getInputStream();
      byte[] b = new byte[8192];
      int n;
      while ((n = is.read(b)) > 0) bo.write(b, 0, n);
      p.waitFor();
      return bo.toString("UTF-8");
    } catch (Throwable t) { return ""; }
  }

  static int keyCode(String k) {
    if (k == null) return 0;
    if (k.equals("back")) return 4;
    if (k.equals("home")) return 3;
    if (k.equals("up")) return 19;
    if (k.equals("down")) return 20;
    if (k.equals("left")) return 21;
    if (k.equals("right")) return 22;
    if (k.equals("ok")) return 23;
    if (k.equals("volup")) return 24;
    if (k.equals("voldn")) return 25;
    if (k.equals("menu")) return 82;
    if (k.equals("power")) return 26;
    if (k.equals("info")) return 165;
    if (k.equals("live")) return 172;
    return 0;
  }

  // ---------------- tiny json field extractors ----------------
  static String jstr(String body, String key) {
    Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(body);
    if (m.find()) return unesc(m.group(1));
    return null;
  }

  static int jint(String body, String key, int def) {
    Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(body);
    if (m.find()) try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException e) { return def; }
    return def;
  }

  static String unesc(String s) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\\' && i + 1 < s.length()) {
        char n = s.charAt(++i);
        if (n == 'n') sb.append('\n');
        else if (n == 'r') sb.append('\r');
        else if (n == 't') sb.append('\t');
        else if (n == 'u' && i + 4 < s.length()) {
          sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
          i += 4;
        } else sb.append(n);
      } else sb.append(c);
    }
    return sb.toString();
  }

  // ---------------- http ----------------
  public static void main(String[] args) throws Exception {
    log("[http] starting, web=" + WEB);
    new Thread(new Runnable() { public void run() { acceptPlain(); } }).start();
    new Thread(new Runnable() { public void run() { acceptSecure(); } }).start();
    // keep main alive
    Thread.currentThread().join();
  }

  static void acceptPlain() {
    try {
      java.net.ServerSocket ss = new java.net.ServerSocket(PORT);
      log("[http] plain on :" + PORT);
      while (true) {
        final java.net.Socket s = ss.accept();
        new Thread(new Runnable() { public void run() { handle(s); } }).start();
      }
    } catch (Throwable t) { log("[http] plain fail: " + t); }
  }

  static void acceptSecure() {
    try {
      byte[] kb = readAll(KEYDER);
      byte[] cb = readAll(CERTDER);
      java.security.PrivateKey pk = java.security.KeyFactory.getInstance("RSA")
          .generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(kb));
      java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
      java.security.cert.X509Certificate cert = (java.security.cert.X509Certificate)
          cf.generateCertificate(new java.io.ByteArrayInputStream(cb));
      java.security.cert.X509Certificate[] chain = new java.security.cert.X509Certificate[] { cert };
      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(new javax.net.ssl.KeyManager[] { new KM(pk, chain) }, null, null);
      SSLServerSocket ss = (SSLServerSocket) ctx.getServerSocketFactory().createServerSocket(SPORT);
      log("[http] https on :" + SPORT + " cert=" + cert.getSubjectDN());
      while (true) {
        final SSLSocket s;
        try { s = (SSLSocket) ss.accept(); }
        catch (Throwable hand) { continue; } // botched handshake: ignore
        new Thread(new Runnable() { public void run() { handle(s); } }).start();
      }
    } catch (Throwable t) { log("[http] https fail: " + t); }
  }

  // key manager backed by raw key material (no KeyStore in this stripped framework)
  static class KM implements javax.net.ssl.X509KeyManager {
    final java.security.PrivateKey key;
    final java.security.cert.X509Certificate[] chain;
    KM(java.security.PrivateKey k, java.security.cert.X509Certificate[] c) { key = k; chain = c; }
    public String chooseClientAlias(String[] kt, java.security.Principal[] iss, java.net.Socket s) { return "stb"; }
    public String chooseServerAlias(String kt, java.security.Principal[] iss, java.net.Socket s) { return "stb"; }
    public String[] getClientAliases(String kt, java.security.Principal[] iss) { return new String[] { "stb" }; }
    public String[] getServerAliases(String kt, java.security.Principal[] iss) { return new String[] { "stb" }; }
    public java.security.Principal[] getServerIssuers(boolean b) { return null; }
    public java.security.cert.X509Certificate[] getCertificateChain(String a) { return chain; }
    public java.security.PrivateKey getPrivateKey(String a) { return key; }
  }

  static byte[] readAll(String path) throws IOException {
    File f = new File(path);
    byte[] b = new byte[(int) f.length()];
    FileInputStream fi = new FileInputStream(f);
    int off = 0;
    while (off < b.length) { int r = fi.read(b, off, b.length - off); if (r <= 0) break; off += r; }
    fi.close();
    return b;
  }

  static void handle(java.net.Socket s) {
    try {
      InputStream in = s.getInputStream();
      String line = readLine(in);
      if (line == null) { close(s); return; }
      String[] parts = line.split(" ");
      if (parts.length < 2) { close(s); return; }
      String method = parts[0];
      String path = parts[1];
      int qm = path.indexOf('?');
      if (qm >= 0) path = path.substring(0, qm);
      int clen = 0;
      String h;
      while ((h = readLine(in)) != null && !h.isEmpty()) {
        int c = h.indexOf(':');
        if (c > 0 && h.substring(0, c).trim().equalsIgnoreCase("Content-Length")) {
          try { clen = Integer.parseInt(h.substring(c + 1).trim()); } catch (Exception ig) {}
        }
      }
      String body = "";
      if (clen > 0) {
        byte[] bb = new byte[clen];
        int off = 0;
        while (off < clen) { int r = in.read(bb, off, clen - off); if (r <= 0) break; off += r; }
        body = new String(bb, 0, off, "UTF-8");
      }
      OutputStream os = new BufferedOutputStream(s.getOutputStream(), 65536);
      log("[req] " + method + " " + path);

      if ((path.equals("/") || path.equals("/index.html")) && method.equals("GET")) { sendFile(os, WEB, "text/html; charset=utf-8"); close(s); return; }
      if (path.equals("/info") && method.equals("GET")) {
        if (vClients.isEmpty() && !vRunning) { } // info alone should not spin scrcpy forever without frames; still start it like PC does
        startVideo();
        json(os, "{\"ready\":" + vReady + ",\"codec\":" + (vCodec == null ? "null" : "\"" + vCodec + "\"") + ",\"w\":" + encW + ",\"h\":" + encH + "}");
        close(s); return;
      }
      if (path.equals("/apps") && method.equals("GET")) { json(os, getApps()); close(s); return; }
      if (path.equals("/h264") && method.equals("GET")) {
        headStream(os);
        Sink sink = new Sink(os, 300);
        vClients.add(sink);
        startVideo();
        byte[] sp = sps, pp = pps;
        log("[v] client join n=" + vClients.size() + " cfg=" + (sp != null && pp != null));
        if (vRunning) shNoWait("pkill -9 -f com.genymobile.scrcpy"); // force fresh session: immediate IDR
        if (sp != null && pp != null) {
          byte[] pay = new byte[4 + sp.length + 4 + pp.length];
          pay[3] = 1;
          System.arraycopy(sp, 0, pay, 4, sp.length);
          pay[4 + sp.length + 3] = 1;
          System.arraycopy(pp, 0, pay, 4 + sp.length + 4, pp.length);
          byte[] frame = new byte[12 + pay.length];
          long fl = 1L << 62;
          for (int i = 0; i < 8; i++) frame[i] = (byte) (fl >>> (56 - 8 * i));
          frame[8] = (byte) (pay.length >>> 24);
          frame[9] = (byte) (pay.length >>> 16);
          frame[10] = (byte) (pay.length >>> 8);
          frame[11] = (byte) pay.length;
          sink.push(frame);
        }
        while (sink.alive) Thread.sleep(500);
        vClients.remove(sink);
        sink.close();
        return; // socket closed by sink
      }
      if (path.equals("/audio") && method.equals("GET")) {
        headStream(os);
        Sink sink = new Sink(os, 400);
        aClients.add(sink);
        startAudio();
        while (sink.alive) Thread.sleep(500);
        aClients.remove(sink);
        sink.close();
        return;
      }
      if (path.equals("/key") && method.equals("POST")) {
        int code = keyCode(jstr(body, "key"));
        if (code == 0) code = jint(body, "code", 0);
        if (code != 0) { sh("input keyevent " + code); ok(os); } else { plain(os, 400, "bad key"); }
        close(s); return;
      }
      if (path.equals("/tap") && method.equals("POST")) {
        int x = clamp(jint(body, "x", 0), 0, 1920), y = clamp(jint(body, "y", 0), 0, 1080);
        sh("input tap " + x + " " + y);
        ok(os); close(s); return;
      }
      if (path.equals("/swipe") && method.equals("POST")) {
        int x1 = jint(body, "x1", 0), y1 = jint(body, "y1", 0), x2 = jint(body, "x2", 0), y2 = jint(body, "y2", 0), du = jint(body, "dur", 300);
        sh("input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + du);
        ok(os); close(s); return;
      }
      if (path.equals("/text") && method.equals("POST")) {
        String t = jstr(body, "text");
        if (t != null) {
          t = t.replaceAll("[^A-Za-z0-9 .:@/_\\-]", "").replace(" ", "%20");
          if (!t.isEmpty()) sh("input text '" + t + "'");
        }
        ok(os); close(s); return;
      }
      if (path.equals("/launch") && method.equals("POST")) {
        String act = jstr(body, "act");
        if (act != null) {
          act = act.replaceAll("[^A-Za-z0-9._/]", "");
          if (!act.isEmpty()) sh("am start -n " + act);
        }
        ok(os); close(s); return;
      }
      plain(os, 404, "not found");
      close(s);
    } catch (Throwable t) {
      // client aborted mid-request: fine
      close(s);
    }
  }

  static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

  static String readLine(InputStream in) throws IOException {
    StringBuilder sb = new StringBuilder();
    int c;
    while ((c = in.read()) != -1) {
      if (c == '\n') break;
      if (c != '\r') sb.append((char) c);
    }
    if (c == -1 && sb.length() == 0) return null;
    return sb.toString();
  }

  static void sendFile(OutputStream os, String path, String ctype) throws IOException {
    File f = new File(path);
    if (!f.exists()) { plain(os, 404, "missing " + path); return; }
    byte[] b = new byte[(int) f.length()];
    FileInputStream fi = new FileInputStream(f);
    int off = 0;
    while (off < b.length) { int r = fi.read(b, off, b.length - off); if (r <= 0) break; off += r; }
    fi.close();
    head(os, 200, ctype, off);
    os.write(b, 0, off);
    os.flush();
  }

  static void json(OutputStream os, String body) throws IOException {
    byte[] b = body.getBytes("UTF-8");
    head(os, 200, "application/json; charset=utf-8", b.length);
    os.write(b);
    os.flush();
  }

  static void ok(OutputStream os) throws IOException { json(os, "\"ok\""); }

  static void plain(OutputStream os, int code, String msg) throws IOException {
    byte[] b = msg.getBytes("UTF-8");
    head(os, code, "text/plain; charset=utf-8", b.length);
    os.write(b);
    os.flush();
  }

  static void head(OutputStream os, int code, String ctype, int len) throws IOException {
    StringBuilder sb = new StringBuilder();
    sb.append("HTTP/1.1 ").append(code).append(code == 200 ? " OK" : " ERR").append("\r\n");
    sb.append("Content-Type: ").append(ctype).append("\r\n");
    sb.append("Content-Length: ").append(len).append("\r\nConnection: close\r\n\r\n");
    os.write(sb.toString().getBytes("US-ASCII"));
    os.flush();
  }

  static void headStream(OutputStream os) throws IOException {
    os.write(("HTTP/1.1 200 OK\r\n"
        + "Content-Type: application/octet-stream\r\n"
        + "Cache-Control: no-cache\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
    os.flush();
  }

  static void scanNals(byte[] pay) {
    int i = 0;
    while (i < pay.length - 3) {
      int sc = 0;
      if (pay[i] == 0 && pay[i + 1] == 0 && pay[i + 2] == 0 && pay[i + 3] == 1) sc = 4;
      else if (pay[i] == 0 && pay[i + 1] == 0 && pay[i + 2] == 1) sc = 3;
      if (sc == 0) { i++; continue; }
      int st = i + sc;
      int j = st + 1;
      while (j + 3 <= pay.length) {
        if (pay[j] == 0 && pay[j + 1] == 0 && (pay[j + 2] == 1 || (j + 3 < pay.length && pay[j + 2] == 0 && pay[j + 3] == 1))) break;
        j++;
      }
      if (j > pay.length) j = pay.length;
      byte[] nal = Arrays.copyOfRange(pay, st, j);
      if (nal.length > 0) {
        int t = nal[0] & 31;
        if (t == 7) sps = nal;
        else if (t == 8) pps = nal;
      }
      i = j;
    }
  }

  static long be64(byte[] b, int o) {
    long v = 0;
    for (int i = 0; i < 8; i++) v = (v << 8) | (b[o + i] & 0xffL);
    return v;
  }

  static int be32(byte[] b, int o) {
    return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16) | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
  }

  static String hex2(byte x) {
    int v = x & 0xff;
    String h = Integer.toHexString(v);
    return h.length() < 2 ? "0" + h : h;
  }

  static void close(java.io.Closeable c) { try { c.close(); } catch (Throwable ig) {} }

  static void log(String s) { System.out.println(s); }
}
