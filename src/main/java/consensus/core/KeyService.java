package consensus.core;

import java.io.FileInputStream;
import java.security.*;


public class KeyService {
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
        return (PrivateKey)keystore.getKey(alias, password.toCharArray());
    }

    public PublicKey loadPublicKey(String alias) throws KeyStoreException {
        return keystore.getCertificate(alias).getPublicKey();
    }
}
