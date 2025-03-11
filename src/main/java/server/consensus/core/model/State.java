package server.consensus.core.model;

import common.model.Transaction;

public interface State {
    void applyTransaction(Transaction transaction);
    String getCurrentState();
}
