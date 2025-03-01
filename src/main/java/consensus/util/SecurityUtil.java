package consensus.util;

import consensus.core.Message;
import consensus.core.SignedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.ByteBuffer;
import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

public class SecurityUtil {

    private static final Logger logger = LoggerFactory.getLogger(SecurityUtil.class);
    public static final String KEYSTORE_PATH = "src/main/java/consensus/util/keys/keystore.p12";

    public static String signMessage(Message message, PrivateKey privateKey) throws Exception {
        logger.info("Signing data...");
        // Get a signature object
        Signature signer = Signature.getInstance("SHA256withRSA");
        // Initialize the signature object with the private key
        signer.initSign(privateKey);
        // Update the signature object with every parameter in Message
        updateSignature(signer, message.getMessageId(), message.getSenderId(), message.getDestinationId(), message.getType(), message.getPayload());
        // Sign the data
        byte[] signature = signer.sign();
        // Encode the signature in Base64
        return Base64.getEncoder().encodeToString(signature);
    }

    public static boolean verifySignature(SignedMessage message, PublicKey publicKey) throws Exception {
        logger.info("Verifying signature...");
        // Get a signature object
        Signature verifier = Signature.getInstance("SHA256withRSA");
        // Initialize the signature object with the public key
        verifier.initVerify(publicKey);
        // Decode Base64 signature
        byte[] decodedSignature = Base64.getDecoder().decode(message.getSignature());
        // Update the signature object with every parameter in Message
        updateSignature(verifier, message.getMessageId(), message.getSenderId(), message.getDestinationId(), message.getType(), message.getPayload());
        // Verify the signature
        return verifier.verify(decodedSignature);
    }

    private static void updateSignature(Signature verifier, int messageId, int senderId, int destinationId, Message.Type type, String payload) throws SignatureException {
        verifier.update(intToBytes(messageId));
        verifier.update(intToBytes(senderId));
        verifier.update(intToBytes(destinationId));
        verifier.update(type.name().getBytes());
        if(payload != null)
            verifier.update(payload.getBytes());
    }

    private static byte[] intToBytes(int value) {
        return ByteBuffer.allocate(4).putInt(value).array();
    }
}
