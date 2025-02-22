package consensus.exception;

public class LinkException extends Exception{
    public LinkException(ErrorMessages message) {
        super(message.getMessage());
    }
    public LinkException(ErrorMessages message, Throwable cause) {
        super(message.getMessage(), cause);
    }
}
