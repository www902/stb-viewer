// Debug helper: dump AudioRecord constructors/methods available on this device.
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public class Ctor {
  public static void main(String[] a) {
    Class<?> c = android.media.AudioRecord.class;
    System.out.println("== constructors ==");
    for (Constructor<?> k : c.getDeclaredConstructors()) {
      StringBuilder sb = new StringBuilder(k.toString());
      System.out.println(sb);
    }
    System.out.println("== methods (setup/start/read) ==");
    for (Method m : c.getDeclaredMethods()) {
      String n = m.getName();
      if (n.contains("setup") || n.contains("start") || n.contains("read") || n.contains("stop")) {
        System.out.println(m);
      }
    }
    System.out.flush();
  }
}
