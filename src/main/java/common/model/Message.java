package common.model;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.Objects;

@Getter
public class Message implements Serializable {
    private final String senderId;
    @Setter
    private String destinationId;
    @Setter
    private int messageId;
    private final Type type;
    private final String payload;

    public enum Type {
        ACK, UNICAST, CONSENSUS, REQUEST_RESPONSE, KEY_EXCHANGE
    }

    public Message(String senderId, String destinationId, Type type, String payload) {
        this.senderId = senderId;
        this.destinationId = destinationId;
        this.type = type;
        this.payload = payload;
    }

    public Message(String senderId, String destinationId, Type type) {
        this.senderId = senderId;
        this.destinationId = destinationId;
        this.type = type;
        this.payload = null;
    }

    public Message(String senderId, Type type, String payload) {
        this.senderId = senderId;
        this.destinationId = "";
        this.type = type;
        this.payload = payload;
    }

    @Override
    public boolean equals(Object other) {
        if(!(other instanceof Message)) return false;
        return this.getMessageId() == ((Message) other).getMessageId() &&
                this.getSenderId().equals(((Message) other).getSenderId()) &&
                this.getDestinationId().equals(((Message) other).getDestinationId()) &&
                this.getType() == ((Message) other).getType() &&
                Objects.equals(this.getPayload(), ((Message) other).getPayload());
    }
}
