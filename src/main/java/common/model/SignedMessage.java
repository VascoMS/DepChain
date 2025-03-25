package common.model;

import lombok.Getter;
import util.SecurityUtil;

import javax.crypto.spec.SecretKeySpec;
import java.security.PrivateKey;

@Getter
public class SignedMessage extends Message {
    private final String integrity;

    public SignedMessage(String senderId, String destinationId, Type type, String payload, PrivateKey privateKey) throws Exception{
        super(senderId, destinationId, type, payload);
        this.integrity = SecurityUtil.signMessage(this, privateKey);
    }

    public SignedMessage(String senderId, String destinationId, int messageId, Type type, PrivateKey privateKey) throws Exception{
        super(senderId, destinationId, type);
        this.setMessageId(messageId);
        this.integrity = SecurityUtil.signMessage(this, privateKey);
    }

    public SignedMessage(Message message, PrivateKey privateKey) throws Exception {
        super(message.getSenderId(), message.getDestinationId(), message.getType(), message.getPayload());
        this.setMessageId(message.getMessageId()); // Copy the message ID
        this.integrity = SecurityUtil.signMessage(this, privateKey);
    }

    public SignedMessage(String senderId, String destinationId, Type type, String payload, SecretKeySpec secretKey) throws Exception{
        super(senderId, destinationId, type, payload);
        this.integrity = SecurityUtil.generateHMAC(this, secretKey);
    }

    public SignedMessage(String senderId, String destinationId, int messageId, Type type, SecretKeySpec secretKey) throws Exception{
        super(senderId, destinationId, type);
        this.setMessageId(messageId);
        this.integrity = SecurityUtil.generateHMAC(this, secretKey);
    }

    public SignedMessage(Message message, SecretKeySpec secretKey) throws Exception {
        super(message.getSenderId(), message.getDestinationId(), message.getType(), message.getPayload());
        this.setMessageId(message.getMessageId()); // Copy the message ID
        this.integrity = SecurityUtil.generateHMAC(this, secretKey
        );
    }

}
