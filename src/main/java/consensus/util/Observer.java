package consensus.util;

import consensus.core.model.Message;

public interface Observer {
    void update(Message message);
}
