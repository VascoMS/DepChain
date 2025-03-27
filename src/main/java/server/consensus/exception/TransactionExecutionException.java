package server.consensus.exception;

public class TransactionExecutionException extends Exception {
    public TransactionExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
    public TransactionExecutionException(String message) {
        super(message);
    }
}
