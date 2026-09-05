package cd.fool.adbport;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.conscrypt.Conscrypt;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Date;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;

/**
 * ADB 协议栈：pair / connect / switchToPort。密钥持久于 filesDir。
 * pair 返回 null=成功，否则为 PFAIL 的 d 值（三分类）。
 * 失败堆栈全部经 Logger 落盘（排查期关键证据）。
 */
public class AdbHelper {
    private static final String TAG = "AdbPort";

    public static final String PAIR_ERR_PORT = "配对：端口不通";
    public static final String PAIR_ERR_HANDSHAKE = "配对：握手失败";
    public static final String PAIR_ERR_CODE = "配对：码错误";

    /** 码错误关键词表（初版，实测后按真实异常调优）。 */
    private static final String[] CODE_HINTS = {
            "password", "code", "passcode", "wrong", "invalid", "mismatch", "incorrect", "denied"
    };

    private final Context context;

    public AdbHelper(Context context) {
        this.context = context;
        try {
            Security.removeProvider("BC");
            Security.insertProviderAt(new BouncyCastleProvider(), 1);
            Security.insertProviderAt(Conscrypt.newProvider(), 2);
        } catch (Exception e) {
            Logger.e(TAG, "Security providers setup failed", e);
        }
    }

    public String pair(String host, int port, String code) {
        try (SimpleAdbManager manager = new SimpleAdbManager(context)) {
            Logger.i(TAG, "pair " + host + ":" + port);
            manager.pair(host, port, code);
            Logger.i(TAG, "pair ok (no exception from lib)");
            return null;
        } catch (Exception e) {
            Logger.e(TAG, "pair failed " + host + ":" + port, e);   // 完整堆栈落盘
            return classifyPairError(e);
        }
    }

    private static String classifyPairError(Exception e) {
        StringBuilder sb = new StringBuilder();
        Throwable t = e;
        while (t != null) {
            sb.append(t.getMessage() == null ? "" : t.getMessage()).append(' ');
            t = t.getCause();
        }
        String msg = sb.toString().toLowerCase();
        for (String h : CODE_HINTS) {
            if (msg.contains(h)) return PAIR_ERR_CODE;
        }
        return PAIR_ERR_HANDSHAKE;
    }

    public boolean connect(String host, int port) {
        try (SimpleAdbManager manager = new SimpleAdbManager(context)) {
            manager.connect(host, port);
            Logger.i(TAG, "connect/auth ok on " + host + ":" + port);
            return true;
        } catch (Exception e) {
            Logger.e(TAG, "connect/auth failed on " + host + ":" + port, e);   // 堆栈落盘
            return false;
        }
    }

    public boolean switchToPort(String host, int port, int targetPort) {
        SimpleAdbManager manager = null;
        try {
            manager = new SimpleAdbManager(context);
            manager.connect(host, port);
            Thread.sleep(200);
            Logger.i(TAG, "sending tcpip:" + targetPort);
            try (io.github.muntashirakon.adb.AdbStream stream = manager.openStream("tcpip:" + targetPort);
                 java.io.InputStream is = stream.openInputStream()) {
                byte[] buf = new byte[1024];
                if (is.read(buf) > 0) { /* 吞掉响应即可 */ }
            } catch (Exception e) {
                Logger.i(TAG, "switchToPort stream end: " + e.getMessage());
            }
            Thread.sleep(3000);
            Logger.i(TAG, "switchToPort done");
            return true;
        } catch (Exception e) {
            Logger.e(TAG, "switchToPort failed", e);
            return false;
        } finally {
            if (manager != null) try { manager.close(); } catch (Exception ignored) {}
        }
    }

    // ======== 密钥/证书管理（SimpleAdbManager，与上版一致）========

    private static class SimpleAdbManager extends AbsAdbConnectionManager {
        private PrivateKey privateKey;
        private PublicKey publicKey;
        private X509Certificate certificate;
        private final File keyFile;
        private final File pubKeyFile;
        private final File certFile;

        SimpleAdbManager(Context context) throws Exception {
            setApi(Build.VERSION.SDK_INT);
            keyFile = new File(context.getFilesDir(), "adb_key");
            pubKeyFile = new File(context.getFilesDir(), "adb_key.pub");
            certFile = new File(context.getFilesDir(), "adb_cert");
            loadOrGenerateKeyPair();
        }

        private void loadOrGenerateKeyPair() throws Exception {
            if (keyFile.exists() && pubKeyFile.exists() && certFile.exists()) {
                try {
                    KeyFactory kf = KeyFactory.getInstance("RSA");
                    privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(readFileBytes(keyFile)));
                    publicKey = kf.generatePublic(new X509EncodedKeySpec(readFileBytes(pubKeyFile)));
                    CertificateFactory cf = CertificateFactory.getInstance("X.509");
                    certificate = (X509Certificate) cf.generateCertificate(
                            new ByteArrayInputStream(readFileBytes(certFile)));
                    return;
                } catch (Exception e) {
                    Logger.e(TAG, "load keys failed, regenerate", e);
                }
            }
            generateNewKeyPairAndCert();
        }

        private byte[] readFileBytes(File f) throws IOException {
            byte[] data = new byte[(int) f.length()];
            try (FileInputStream fis = new FileInputStream(f)) {
                int read = 0;
                while (read < data.length) {
                    int n = fis.read(data, read, data.length - read);
                    if (n < 0) throw new IOException("short read");
                    read += n;
                }
            }
            return data;
        }

        private void writeFileBytes(File f, byte[] data) throws IOException {
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(data);
            }
        }

        private void generateNewKeyPairAndCert() throws Exception {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(2048, new SecureRandom());
            KeyPair keyPair = keyGen.generateKeyPair();
            privateKey = keyPair.getPrivate();
            publicKey = keyPair.getPublic();
            certificate = generateSelfSignedCertificate(keyPair);
            writeFileBytes(keyFile, privateKey.getEncoded());
            writeFileBytes(pubKeyFile, publicKey.getEncoded());
            writeFileBytes(certFile, certificate.getEncoded());
        }

        private X509Certificate generateSelfSignedCertificate(KeyPair keyPair) throws Exception {
            X500Name issuer = new X500Name("CN=adbport");
            BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
            Date notBefore = new Date(System.currentTimeMillis() - 24L * 60 * 60 * 1000);
            Date notAfter = new Date(System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000);
            SubjectPublicKeyInfo spi = SubjectPublicKeyInfo.getInstance(keyPair.getPublic().getEncoded());
            X509v3CertificateBuilder builder = new X509v3CertificateBuilder(issuer, serial, notBefore, notAfter, issuer, spi);
            ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                    .setProvider("BC").build(keyPair.getPrivate());
            X509CertificateHolder holder = builder.build(signer);
            return new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder);
        }

        @Override @NonNull protected PrivateKey getPrivateKey() { return privateKey; }
        @Override @NonNull protected Certificate getCertificate() { return certificate; }
        @Override @NonNull protected String getDeviceName() { return "adbport"; }
    }
}
