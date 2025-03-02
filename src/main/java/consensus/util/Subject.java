package consensus.util;

import consensus.core.model.Message;

public interface Subject {
    void addObserver(Observer observer);
    void removeObserver(Observer observer);
    void notifyObservers(Message message);
}
