package util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.KeyGenerator;
import javax.crypto.spec.SecretKeySpec;
import java.io.FileInputStream;
import java.security.*;
import java.security.cert.Certificate;


public class KeyService {

    private static final Logger logger = LoggerFactory.getLogger(KeyService.class);
    private final KeyStore keystore;
    private final String password;


    public KeyService(String keystorePath, String keystorePassword) throws Exception {
        keystore = KeyStore.getInstance("PKCS12");
        password = keystorePassword;
        try (FileInputStream fis = new FileInputStream(keystorePath)) {
            keystore.load(fis, keystorePassword.toCharArray());
        }
    }

    public PrivateKey loadPrivateKey(String alias) throws UnrecoverableKeyException, KeyStoreException, NoSuchAlgorithmException {
        logger.info("Loading private key for alias: " + alias);
        return (PrivateKey) keystore.getKey(alias, password.toCharArray());
    }

    public PublicKey loadPublicKey(String alias) throws KeyStoreException {
        logger.info("Loading public key for alias: " + alias);
        Certificate certificate = keystore.getCertificate(alias);
        return certificate != null ? certificate.getPublicKey() : null;
    }

    public SecretKeySpec generateSecretKey() throws NoSuchAlgorithmException  {
        KeyGenerator keyGen = KeyGenerator.getInstance("HmacSHA256");
        return (SecretKeySpec) keyGen.generateKey();
    }

}
