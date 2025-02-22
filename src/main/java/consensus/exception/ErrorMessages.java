package consensus.exception;

public enum ErrorMessages {
    LinkCreationError("Error creating link"),
    NoSuchNodeError("No such node"),
    SendingError("Error sending message"),
    ;
    private final String message;

    ErrorMessages(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}
