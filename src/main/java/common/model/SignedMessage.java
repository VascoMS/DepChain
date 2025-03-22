package common.model;

import javax.crypto.SecretKey;
import util.SecurityUtil;
import lombok.Getter;

import java.security.PrivateKey;

@Getter
public class SignedMessage extends Message {
    private final String integrity;

    public SignedMessage(int senderId, int destinationId, Type type, String payload, PrivateKey privateKey) throws Exception{
        super(senderId, destinationId, type, payload);
        this.integrity = SecurityUtil.signMessage(this, privateKey);
    }

    public SignedMessage(int senderId, int destinationId, int messageId, Type type, PrivateKey privateKey) throws Exception{
        super(senderId, destinationId, type);
        this.setMessageId(messageId);
        this.integrity = SecurityUtil.signMessage(this, privateKey);
    }

    public SignedMessage(Message message, PrivateKey privateKey) throws Exception {
        super(message.getSenderId(), message.getDestinationId(), message.getType(), message.getPayload());
        this.setMessageId(message.getMessageId()); // Copy the message ID
        this.integrity = SecurityUtil.signMessage(this, privateKey);
    }

    public SignedMessage(int senderId, int destinationId, Type type, String payload, SecretKey secretKey) throws Exception{
        super(senderId, destinationId, type, payload);
        this.integrity = SecurityUtil.generateHMAC(this, secretKey);
    }

    public SignedMessage(int senderId, int destinationId, int messageId, Type type, SecretKey secretKey) throws Exception{
        super(senderId, destinationId, type);
        this.setMessageId(messageId);
        this.integrity = SecurityUtil.generateHMAC(this, secretKey);
    }

    public SignedMessage(Message message, SecretKey secretKey) throws Exception {
        super(message.getSenderId(), message.getDestinationId(), message.getType(), message.getPayload());
        this.setMessageId(message.getMessageId()); // Copy the message ID
        this.integrity = SecurityUtil.generateHMAC(this, secretKey);
    }
}
