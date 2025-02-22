package consensus.core;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

@Getter
public class Message implements Serializable {
    private final int senderId;
    @Setter
    private int messageId;
    private final Type type;

    public enum Type {
        ACK, WRITE, RECEIVE
    }

    public Message(int senderId, Type type) {
        this.senderId = senderId;
        this.type = type;
    }
}
