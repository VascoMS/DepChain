package consensus.core;

import consensus.util.SecurityUtil;

import java.security.PrivateKey;
import java.security.PublicKey;

public class SignedMessage extends Message {
    private final byte[] signature;

    public SignedMessage(int senderId, int destinationId, Type type, String payload, PrivateKey privateKey) throws Exception{
        super(senderId, destinationId, type, payload);
        this.signature = SecurityUtil.signMessage(this, privateKey);
    }

    public SignedMessage(Message message, PrivateKey privateKey) throws Exception {
        super(message.getSenderId(), message.getDestinationId(), message.getType(), message.getPayload());
        this.setMessageId(message.getMessageId()); // Copy the message ID
        this.signature = SecurityUtil.signMessage(this, privateKey);
    }


    public byte[] getSignature() {
        return signature;
    }
}
