// STB audio loopback: probe audio sources for live system audio, then stream
// PCM s16le 48kHz stereo over TCP back to the PC viewer server.
// Run on device as root: CLASSPATH=/data/local/tmp/rec.dex app_process / Rec <port> [forceSrc]
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;

public class Rec {
  static final int SR = 48000;

  static int maxAbs(byte[] buf, int got) {
    int m = 0;
    for (int i = 0; i + 1 < got; i += 2) {
      int v = (short) ((buf[i] & 0xff) | (buf[i + 1] << 8));
      if (v < 0) v = -v;
      if (v > m) m = v;
    }
    return m;
  }

  static AudioRecord makeRecord(int src, int bufSize) throws Exception {
    // app_process has no ActivityThread, so public ctor passes null opPackageName -> JNI strlen(NULL) crash.
    // Build the record manually via package-private ctor + native_setup with an explicit package name.
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
    } catch (Throwable t) {
      System.out.println("[rec] attrs build failed: " + t);
    }

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
      // The Java wrapper normally sets these fields after native_setup; do it manually.
      setField(r, "mState", 1); // STATE_INITIALIZED
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
    System.out.println("[rec] native_setup status=" + status + " session=" + session[0] + " state=" + r.getState());
    return r;
  }

  static void setField(Object o, String name, int value) {
    try {
      java.lang.reflect.Field f = AudioRecord.class.getDeclaredField(name);
      f.setAccessible(true);
      f.setInt(o, value);
    } catch (Throwable t) {
      System.out.println("[rec] setField " + name + " failed: " + t);
    }
  }

  public static void main(String[] a) throws Exception {
    int port = a.length > 0 ? Integer.parseInt(a[0]) : 2711;
    int force = a.length > 1 ? Integer.parseInt(a[1]) : -1;
    int[] order = force >= 0
        ? new int[] { force }
        : new int[] { MediaRecorder.AudioSource.REMOTE_SUBMIX, MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_CALL, MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.MIC };

    int chosen = -1;
    if (force >= 0) {
      chosen = force; // forced source: stream regardless of current signal level
    } else {
    for (int si = 0; si < order.length; si++) {
      int src = order[si];
      AudioRecord r = null;
      try {
        int min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        if (min < 0) { System.out.println("[rec] src " + src + " unsupported, minBuf=" + min); continue; }
        r = makeRecord(src, Math.max(min, 192000));
        if (r.getState() != AudioRecord.STATE_INITIALIZED) { System.out.println("[rec] src " + src + " init failed"); continue; }
        r.startRecording();
        byte[] buf = new byte[9600];
        int mx = 0;
        long bytes = 0;
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 2000) {
          int got = r.read(buf, 0, buf.length);
          if (got <= 0) break;
          bytes += got;
          int m = maxAbs(buf, got);
          if (m > mx) mx = m;
        }
        System.out.println("[rec] probe src=" + src + " bytes=" + bytes + " maxAbs=" + mx);
        if (bytes > 1000 && mx > 300) { chosen = src; }
      } catch (Throwable t) {
        System.out.println("[rec] probe src=" + src + " error " + t);
      } finally {
        if (r != null) try { r.release(); } catch (Throwable ig) {}
      }
      if (chosen >= 0) break;
    }
    }
    if (chosen < 0) { System.out.println("[rec] no live audio source found"); System.exit(2); }

    Socket s = null;
    for (int i = 0; i < 60 && s == null; i++) {
      try { s = new Socket("127.0.0.1", port); } catch (IOException e) { Thread.sleep(1000); }
    }
    if (s == null) { System.out.println("[rec] connect to port " + port + " failed"); System.exit(3); }

    AudioRecord r = makeRecord(chosen, 192000);
    if (r.getState() != AudioRecord.STATE_INITIALIZED) { System.out.println("[rec] reopen failed"); System.exit(4); }
    r.startRecording();
    System.out.println("[rec] streaming src=" + chosen + " s16le 48000 stereo -> 127.0.0.1:" + port);
    OutputStream os = new BufferedOutputStream(s.getOutputStream(), 32768);
    byte[] buf = new byte[4800]; // 2400 frames = 50 ms
    while (true) {
      int got = r.read(buf, 0, buf.length);
      if (got <= 0) break;
      os.write(buf, 0, got);
      os.flush();
    }
    try { r.stop(); } catch (Throwable ig) {}
    r.release();
    System.out.println("[rec] stream ended");
  }
}
