package consensus.core;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

@Getter
public class Message implements Serializable {
    private final int senderId;
    private int messageId;
    private Type type;

    public enum Type {
        ACK, WRITE, RECEIVE
    }

    public Message(int senderId, Type type) {
        this.senderId = senderId;
        this.type = type;
    }

    public int getSenderId() {
        return senderId;
    }

    public int getMessageId() {
        return messageId;
    }

    public void setMessageId(int messageId) {
        this.messageId = messageId;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

}
