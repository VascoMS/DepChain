package consensus.core.model;

import com.google.gson.Gson;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.Objects;

@Getter
public class Message implements Serializable {
    private final int senderId;
    @Setter
    private final int destinationId;
    @Setter
    private int messageId;
    private final Type type;
    private final String payload;

    public enum Type {
        ACK, UNICAST, BROADCAST, CONSENSUS
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
        this.payload = null;
    }

    public Message(int senderId, Type type, String payload) {
        this.senderId = senderId;
        this.destinationId = -1;
        this.type = type;
        this.payload = payload;
    }

    @Override
    public boolean equals(Object other) {
        if(!(other instanceof Message)) return false;
        return this.getMessageId() == ((Message) other).getMessageId() &&
                this.getSenderId() == ((Message) other).getSenderId() &&
                this.getDestinationId() == ((Message) other).getDestinationId() &&
                this.getType() == ((Message) other).getType() &&
                Objects.equals(this.getPayload(), ((Message) other).getPayload());
    }
}
