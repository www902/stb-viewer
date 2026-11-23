import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.Certificate;

// Export private key (PKCS#8 DER) and cert (X.509 DER) from the JKS for the box server.
public class ExportKeys {
  public static void main(String[] a) throws Exception {
    KeyStore ks = KeyStore.getInstance("JKS");
    FileInputStream fi = new FileInputStream(a[0]);
    ks.load(fi, a[1].toCharArray());
    fi.close();
    Key k = ks.getKey("stb", a[1].toCharArray());
    Certificate[] c = ks.getCertificateChain("stb");
    FileOutputStream fo = new FileOutputStream(a[2]);
    fo.write(k.getEncoded());
    fo.close();
    FileOutputStream g = new FileOutputStream(a[3]);
    g.write(c[0].getEncoded());
    g.close();
    System.out.println("key algo=" + k.getAlgorithm() + " format=" + k.getFormat()
        + " keylen=" + k.getEncoded().length + " certlen=" + c[0].getEncoded().length);
  }
}
