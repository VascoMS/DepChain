package server.evm;

import common.model.Transaction;

public interface State {
    void applyTransaction(Transaction transaction);
    String getCurrentState();
}
