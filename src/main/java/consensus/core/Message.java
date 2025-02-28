package consensus.core;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

@Getter
public class Message implements Serializable {
    private final int senderId;
    private final int destinationId;
    @Setter
    private int messageId;
    private final Type type;
    private final String payload;

    public enum Type {
        ACK, WRITE, RECEIVE, SEND, ECHO, READY
    }

    public Message(int senderId, int destinationId, Type type, String payload) {
        this.senderId = senderId;
        this.destinationId = destinationId;
        this.type = type;
        this.payload = payload;
    }

    public Message(int senderId, int destinationId, Type type) {
        this.senderId = senderId;
        this.destinationId = destinationId;
        this.type = type;
    }
}
