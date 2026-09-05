package cd.fool.adbport;

import android.content.Context;
import android.os.Build;
import android.util.Log;

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
 * ADB 协议栈：pair（配对握手）/ connect（连接+认证）/ switchToPort（tcpip 切换）。
 * 密钥持久于 filesDir。pair 返回 null=成功，否则为 PFAIL 的 d 值（三分类，见类头常量）。
 */
public class AdbHelper {
    private static final String TAG = "AdbPort";

    /** pair 失败三分类（d 值），与回执表一一对应。 */
    public static final String PAIR_ERR_PORT = "配对：端口不通";
    public static final String PAIR_ERR_HANDSHAKE = "配对：握手失败";
    public static final String PAIR_ERR_CODE = "配对：码错误";

    /**
     * 配对失败关键词表：命中 → 码错误，否则 → 握手失败。
     * NOTE: 排查期实测后按真实异常信息调优此表（当前为尽力而为的初版）。
     */
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
            Log.e(TAG, "Security providers setup failed", e);
        }
    }

    /**
     * 无线配对握手（pp=配对服务端口，与 adbd 端口无关）。
     * 返回 null=成功；否则为 PAIR_ERR_* 分类字符串（端口死活由调用方 rawTcp 前置判定，不经此处）。
     */
    public String pair(String host, int port, String code) {
        try (SimpleAdbManager manager = new SimpleAdbManager(context)) {
            Log.i(TAG, "pair " + host + ":" + port);
            manager.pair(host, port, code);
            Log.i(TAG, "pair ok");
            return null;
        } catch (Exception e) {
            Log.e(TAG, "pair failed", e);
            return classifyPairError(e);
        }
    }

    /** 码错误分类：关键词命中 → 码错误，其余 → 握手失败（尽力而为，实测后调优 CODE_HINTS）。 */
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

    /** 真连接 + ADB 认证。true = 握手与认证全部通过。 */
    public boolean connect(String host, int port) {
        try (SimpleAdbManager manager = new SimpleAdbManager(context)) {
            manager.connect(host, port);
            return true;
        } catch (Exception e) {
            Log.i(TAG, "connect/auth failed on " + host + ":" + port + " : " + e.getMessage());
            return false;
        }
    }

    /** 在已认证连接上发 tcpip:<targetPort>，等待 adbd 重启（内部睡 3s，F1 补足到 6s）。 */
    public boolean switchToPort(String host, int port, int targetPort) {
        SimpleAdbManager manager = null;
        try {
            manager = new SimpleAdbManager(context);
            manager.connect(host, port);
            Thread.sleep(200);
            Log.i(TAG, "sending tcpip:" + targetPort);
            try (io.github.muntashirakon.adb.AdbStream stream = manager.openStream("tcpip:" + targetPort);
                 java.io.InputStream is = stream.openInputStream()) {
                byte[] buf = new byte[1024];
                if (is.read(buf) > 0) { /* 吞掉响应即可 */ }
            } catch (Exception e) {
                Log.d(TAG, "switchToPort stream end: " + e.getMessage());
            }
            Thread.sleep(3000);
            Log.i(TAG, "switchToPort done");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "switchToPort failed", e);
            return false;
        } finally {
            if (manager != null) try { manager.close(); } catch (Exception ignored) {}
        }
    }

    // ======== 密钥/证书管理（SimpleAdbManager）========

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
                    Log.e(TAG, "load keys failed, regenerate", e);
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
