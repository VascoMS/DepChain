package consensus.exception;

public class TransactionExecutionException extends Exception {
    public TransactionExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
