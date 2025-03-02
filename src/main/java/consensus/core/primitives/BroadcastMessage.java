package consensus.core.primitives;

import consensus.core.model.Message;
import lombok.Getter;

import java.util.UUID;

@Getter
public class BroadcastMessage extends Message {
    private final String broadcastId;
    public BroadcastMessage(int senderId, int destinationId, Type type, String payload) {
        super(senderId, destinationId, type, payload);
        this.broadcastId = UUID.randomUUID().toString();
    }
}
