package consensus.core.model;

import consensus.util.SecurityUtil;
import lombok.Getter;

import java.security.PrivateKey;

@Getter
public class SignedMessage extends Message {
    private final String signature;

    public SignedMessage(int senderId, int destinationId, Type type, String payload, PrivateKey privateKey) throws Exception{
        super(senderId, destinationId, type, payload);
        this.signature = SecurityUtil.signMessage(this, privateKey);
    }

    public SignedMessage(int senderId, int destinationId, int messageId, Type type, PrivateKey privateKey) throws Exception{
        super(senderId, destinationId, type);
        this.setMessageId(messageId);
        this.signature = SecurityUtil.signMessage(this, privateKey);
    }

    public SignedMessage(Message message, PrivateKey privateKey) throws Exception {
        super(message.getSenderId(), message.getDestinationId(), message.getType(), message.getPayload());
        this.setMessageId(message.getMessageId()); // Copy the message ID
        this.signature = SecurityUtil.signMessage(this, privateKey);
    }
}
