package android.media;
// Compile-time stub only. Signatures match real Android framework.
public class AudioRecord {
  public static final int STATE_INITIALIZED = 1;
  public static native int getMinBufferSize(int sampleRateInHz, int channelConfig, int audioFormat);
  public AudioRecord(int audioSource, int sampleRateInHz, int channelConfig, int audioFormat, int bufferSizeInBytes) {}
  public AudioRecord(int audioSource, int sampleRateInHz, int channelConfig, int audioFormat, int bufferSizeInBytes, String packageName) {}
  public native int getState();
  public native void startRecording();
  public native int read(byte[] audioData, int offsetInBytes, int sizeInBytes);
  public native void stop();
  public native void release();
}
