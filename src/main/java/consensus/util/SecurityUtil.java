package consensus.util;

import consensus.core.model.ConsensusPayload;
import consensus.core.model.Message;
import consensus.core.model.SignedMessage;
import consensus.core.model.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.security.*;
import java.util.Base64;

public class SecurityUtil {

    private static final Logger logger = LoggerFactory.getLogger(SecurityUtil.class);
    public static final String KEYSTORE_PATH = "src/main/java/consensus/util/keys/keystore.p12";

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

    public static String signMessage(Message message, PrivateKey privateKey) throws Exception {
        logger.info("Signing data...");
        Signature signer = initSigner(privateKey);

        byte[][] dataToSign = {
                intToBytes(message.getMessageId()),
                intToBytes(message.getSenderId()),
                intToBytes(message.getDestinationId()),
                message.getType().name().getBytes(),
                message.getPayload() != null ? message.getPayload().getBytes() : null
        };

        return createSignature(signer, dataToSign);
    }

    public static boolean verifySignature(SignedMessage message, PublicKey publicKey) throws Exception {
        logger.info("Verifying signature...");
        Signature verifier = initVerifier(publicKey);

        byte[][] dataToVerify = {
                intToBytes(message.getMessageId()),
                intToBytes(message.getSenderId()),
                intToBytes(message.getDestinationId()),
                message.getType().name().getBytes(),
                message.getPayload() != null ? message.getPayload().getBytes() : null
        };

        return verifySignature(verifier, dataToVerify, message.getSignature());
    }

    public static String signConsensusPayload(
            int senderId,
            String consensusId,
            ConsensusPayload.ConsensusType cType,
            String content,
            PrivateKey privateKey
    ) throws Exception {
        logger.info("Signing consensus data...");
        Signature signer = initSigner(privateKey);

        byte[][] dataToSign = {
                intToBytes(senderId),
                consensusId.getBytes(),
                cType.name().getBytes(),
                content != null ? content.getBytes() : null
        };

        return createSignature(signer, dataToSign);
    }

    public static boolean verifySignature(
            int senderId,
            String consensusId,
            ConsensusPayload.ConsensusType cType,
            String content,
            String signature,
            PublicKey publicKey
    ) throws Exception {
        logger.info("Verifying consensus data signature...");
        Signature verifier = initVerifier(publicKey);

        byte[][] dataToVerify = {
                intToBytes(senderId),
                consensusId.getBytes(),
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
                transaction.clientId().getBytes(),
                transaction.content() != null ? transaction.content().getBytes() : null
        };

        return verifySignature(verifier, dataToVerify, transaction.signature());
    }

    private static byte[] intToBytes(int value) {
        return ByteBuffer.allocate(4).putInt(value).array();
    }
}