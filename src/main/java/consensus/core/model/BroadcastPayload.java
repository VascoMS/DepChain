package consensus.core.model;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;


@Getter
public class BroadcastPayload {

    @Setter
    private int senderId;
    private final String broadcastId;
    @Setter
    private BroadcastType bType;
    private final String content;

    public enum BroadcastType {
        SEND, ECHO, READY
    }

    public BroadcastPayload(int senderId, BroadcastType bType, String content) {
        this.senderId = senderId;
        this.broadcastId = UUID.randomUUID().toString();
        this.bType = bType;
        this.content = content;
    }
    public BroadcastPayload(int senderId, String broadcastId, BroadcastType bType, String content) {
        this.senderId = senderId;
        this.broadcastId = broadcastId;
        this.bType = bType;
        this.content = content;
    }
}
