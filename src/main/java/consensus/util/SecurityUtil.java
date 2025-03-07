package consensus.util;

import consensus.core.model.ConsensusPayload;
import consensus.core.model.Message;
import consensus.core.model.SignedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.security.*;
import java.util.Base64;

//TODO: Maybe refactor
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
        signer.update(intToBytes(message.getMessageId()));
        signer.update(intToBytes(message.getSenderId()));
        signer.update(intToBytes(message.getDestinationId()));
        signer.update(message.getType().name().getBytes());
        if(message.getPayload() != null)
            signer.update(message.getPayload().getBytes());
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
        verifier.update(intToBytes(message.getMessageId()));
        verifier.update(intToBytes(message.getSenderId()));
        verifier.update(intToBytes(message.getDestinationId()));
        verifier.update(message.getType().name().getBytes());
        if(message.getPayload() != null)
            verifier.update(message.getPayload().getBytes());
        // Verify the signature
        return verifier.verify(decodedSignature);
    }

    public static String signConsensusPayload(
            int senderId,
            String consensusId,
            ConsensusPayload.ConsensusType cType,
            String content,
            PrivateKey privateKey
    ) throws Exception {
        logger.info("Signing consensus data...");
        // Get a signature object
        Signature signer = Signature.getInstance("SHA256withRSA");
        // Initialize the signature object with the private key
        signer.initSign(privateKey);
        // Update the signature object with every parameter in Message
        signer.update(intToBytes(senderId));
        signer.update(consensusId.getBytes());
        signer.update(cType.name().getBytes());
        if(content != null)
            signer.update(content.getBytes());
        // Sign the data
        byte[] signature = signer.sign();
        // Encode the signature in Base64
        return Base64.getEncoder().encodeToString(signature);
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
        // Get a signature object
        Signature verifier = Signature.getInstance("SHA256withRSA");
        // Initialize the signature object with the public key
        verifier.initVerify(publicKey);
        // Decode Base64 signature
        byte[] decodedSignature = Base64.getDecoder().decode(signature);
        // Update the signature object with every parameter in Message
        verifier.update(intToBytes(senderId));
        verifier.update(consensusId.getBytes());
        verifier.update(cType.name().getBytes());
        if(content != null)
            verifier.update(content.getBytes());
        // Verify the signature
        return verifier.verify(decodedSignature);
    }

    private static byte[] intToBytes(int value) {
        return ByteBuffer.allocate(4).putInt(value).array();
    }
}
