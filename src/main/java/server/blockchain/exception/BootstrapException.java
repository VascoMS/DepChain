package server.blockchain.exception;

public class BootstrapException extends Exception {
    public BootstrapException(String message) {
        super(message);
    }

    public BootstrapException(String message, Throwable cause) {
        super(message, cause);
    }

    public BootstrapException(Throwable cause) {
        super(cause);
    }
}
