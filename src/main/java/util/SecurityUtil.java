package util;

import common.model.Message;
import common.model.SignedMessage;
import common.model.Transaction;
import org.bouncycastle.util.encoders.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.consensus.core.model.ConsensusPayload;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.*;
import java.util.Base64;
import java.util.Objects;

public class SecurityUtil {

    private static final Logger logger = LoggerFactory.getLogger(SecurityUtil.class);
    public static final String SERVER_KEYSTORE_PATH = "src/main/java/server/consensus/keys/keystore.p12";
    public static final String CLIENT_KEYSTORE_PATH = "src/main/java/client/keys/keystore.p12";

    // Generic method to create signature
    private static String createSignature(Signature signer, byte[][] dataToSign) throws Exception {
        // Sign each piece of data
        for (byte[] data : dataToSign) {
            if (data != null) {
                signer.update(data);
            }
        }
        // Sign the data
        byte[] signature = signer.sign();
        // Encode the signature in Base64
        return Base64.getEncoder().encodeToString(signature);
    }

    // Generic method to verify signature
    private static boolean verifySignature(Signature verifier, byte[][] dataToVerify, String signature) throws Exception {
        // Decode Base64 signature
        byte[] decodedSignature = Base64.getDecoder().decode(signature);
        // Update with each piece of data
        for (byte[] data : dataToVerify) {
            if (data != null) {
                verifier.update(data);
            }
        }
        // Verify the signature
        return verifier.verify(decodedSignature);
    }

    // Generic method to create HMAC.
    private static String generateHMAC(Mac mac, byte[][] data) {
        // Update with each piece of data
        for(byte[] block: data) {
            if (block != null) {
                mac.update(block);
            }
        }
        // Generate the HMAC.
        return Base64.getEncoder().encodeToString(mac.doFinal());
    }

    // Generic method to verify HMAC.
    private static boolean verifyHMAC(Mac mac, byte[][] data, String receivedHMAC) {
        // Generate the HMAC from data received.
        String generatedHMAC = generateHMAC(mac, data);
        // Compare with received.
        logger.info("Comparing generated HMAC {} with received {}", generatedHMAC, receivedHMAC);
        return Objects.equals(generatedHMAC, receivedHMAC);
    }

    // Initialize a HMAC object
    private static Mac initHMAC(SecretKeySpec secretKey) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(secretKey);
        return mac;
    }

    // Initialize a signature object for signing
    private static Signature initSigner(PrivateKey privateKey) throws Exception {
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        return signer;
    }

    // Initialize a signature object for verification
    private static Signature initVerifier(PublicKey publicKey) throws Exception {
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publicKey);
        return verifier;
    }

    // Initialize a cipher object for key wrapping
    private static Cipher initEncrypter(PublicKey publicKey) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);
        return cipher;
    }

    // Initialize a cipher object for key unwrapping
    private static Cipher initDecrypter(PrivateKey privateKey) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        return cipher;
    }

    public static String signMessage(Message message, PrivateKey privateKey) throws Exception {
        logger.info("Signing data...");
        Signature signer = initSigner(privateKey);

        byte[][] dataToSign = {
                intToBytes(message.getMessageId()),
                message.getSenderId().getBytes(),
                message.getDestinationId().getBytes(),
                message.getType().name().getBytes(),
                message.getPayload() != null ? message.getPayload().getBytes() : null
        };

        return createSignature(signer, dataToSign);
    }

    public static boolean verifySignature(String signature, String data, PublicKey publicKey) throws Exception {
        logger.info("Verifying signature...");
        Signature verifier = initVerifier(publicKey);
        verifier.update(data.getBytes());
        byte[] decodedSignature = Base64.getDecoder().decode(signature);
        return verifier.verify(decodedSignature);
    }

    public static boolean verifySignature(SignedMessage message, PublicKey publicKey) throws Exception {
        logger.info("Verifying signature...");
        Signature verifier = initVerifier(publicKey);

        byte[][] dataToVerify = {
                intToBytes(message.getMessageId()),
                message.getSenderId().getBytes(),
                message.getDestinationId().getBytes(),
                message.getType().name().getBytes(),
                message.getPayload() != null ? message.getPayload().getBytes() : null
        };

        return verifySignature(verifier, dataToVerify, message.getIntegrity());
    }

    public static String signConsensusPayload(
            String senderId,
            int consensusId,
            ConsensusPayload.ConsensusType cType,
            String content,
            PrivateKey privateKey
    ) throws Exception {
        logger.info("Signing server.consensus data...");
        Signature signer = initSigner(privateKey);

        byte[][] dataToSign = {
                senderId.getBytes(),
                Integer.toString(consensusId).getBytes(),
                cType.name().getBytes(),
                content != null ? content.getBytes() : null
        };

        return createSignature(signer, dataToSign);
    }

    public static boolean verifySignature(
            String senderId,
            int consensusId,
            ConsensusPayload.ConsensusType cType,
            String content,
            String signature,
            PublicKey publicKey
    ) throws Exception {
        logger.info("Verifying server.consensus data signature...");
        Signature verifier = initVerifier(publicKey);

        byte[][] dataToVerify = {
                senderId.getBytes(),
                Integer.toString(consensusId).getBytes(),
                cType.name().getBytes(),
                content != null ? content.getBytes() : null
        };

        return verifySignature(verifier, dataToVerify, signature);
    }

    public static boolean verifySignature(Transaction transaction, PublicKey publicKey) throws Exception {
        logger.info("Verifying transaction signature...");
        Signature verifier = initVerifier(publicKey);

        byte[][] dataToVerify = {
                transaction.id().getBytes(),
                transaction.from().getBytes(),
                transaction.to() != null ? transaction.to().getBytes() : null,
                transaction.data() != null ? transaction.data().getBytes() : null,
                intToBytes(transaction.value())
        };

        return verifySignature(verifier, dataToVerify, transaction.signature());
    }

    public static String signTransaction(String transactionId, String clientId, String receiver, String calldata, int value, PrivateKey privateKey) throws Exception {
        logger.info("Signing transaction...");
        Signature signer = initSigner(privateKey);

        byte[][] dataToSign = {
                transactionId.getBytes(),
                clientId.getBytes(),
                receiver != null ? receiver.getBytes() : null,
                calldata != null ? calldata.getBytes() : null,
                intToBytes(value)
        };

        return createSignature(signer, dataToSign);
    }

    public static String signString(String content, PrivateKey privateKey) throws Exception {
        logger.info("Signing string...");
        Signature signer = initSigner(privateKey);
        signer.update(content.getBytes());
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    public static String generateHMAC(Message message, SecretKeySpec secretKey) throws Exception {
        logger.info("Generating HMAC...");
        Mac mac = initHMAC(secretKey);
        byte[][] data = {
                intToBytes(message.getMessageId()),
                message.getSenderId().getBytes(),
                message.getDestinationId().getBytes(),
                message.getType().name().getBytes(),
                message.getPayload() != null ? message.getPayload().getBytes() : null
        };
        return generateHMAC(mac, data);
    }

    public static boolean verifyHMAC(SignedMessage message, SecretKeySpec secretKey) throws Exception {
        logger.info("Verifying HMAC...");
        Mac mac = initHMAC(secretKey);
        byte[][] data = {
                intToBytes(message.getMessageId()),
                message.getSenderId().getBytes(),
                message.getDestinationId().getBytes(),
                message.getType().name().getBytes(),
                message.getPayload() != null ? message.getPayload().getBytes() : null
        };
        return verifyHMAC(mac, data, message.getIntegrity());
    }

    public static String cipherSecretKey(SecretKeySpec secretKey, PublicKey peerPublicKey) throws Exception {
        logger.info("Ciphering key...");
        Cipher cipher = initEncrypter(peerPublicKey);
        return Base64.getEncoder().encodeToString(cipher.doFinal(secretKey.getEncoded()));
    }

    public static SecretKeySpec decipherSecretKey(String cipheredKey, PrivateKey myPrivateKey) throws Exception {
        logger.info("Deciphering key...");
        Cipher cipher = initDecrypter(myPrivateKey);
        return new SecretKeySpec(cipher.doFinal(Base64.getDecoder().decode(cipheredKey)), "HmacSHA256");
    }

    public static String generateHash(byte[][] data) {
        logger.info("Generating hash...");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] d : data) {
                if (d != null)
                    digest.update(d);
            }
            byte[] hash = digest.digest();
            return Hex.toHexString(hash);
        } catch (NoSuchAlgorithmException e) {
            logger.error("Error generating hash: " + e.getMessage());
            return null;
        }
    }

    public static byte[] intToBytes(int value) {
        return ByteBuffer.allocate(4).putInt(value).array();
    }
    public static byte[] longToBytes(long value) {
        return ByteBuffer.allocate(8).putLong(value).array();
    }
}