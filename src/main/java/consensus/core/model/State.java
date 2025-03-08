package consensus.core.model;

public class State {
    private final StringBuilder appendOnlyString;

    public State() {
        this.appendOnlyString = new StringBuilder();
    }

    public void applyTransaction(Transaction transaction) {
        appendOnlyString.append(transaction.content());
    }

    public String getCurrentState() {
        return appendOnlyString.toString();
    }

}
