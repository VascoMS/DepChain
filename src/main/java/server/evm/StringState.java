package server.evm;

import common.model.Transaction;

public class StringState implements State {
    private final StringBuilder appendOnlyString;

    public StringState() {
        this.appendOnlyString = new StringBuilder();
    }

    public void applyTransaction(Transaction transaction) {
        appendOnlyString.append(transaction.data());
    }

    public String getCurrentState() {
        return appendOnlyString.toString();
    }
}
