package consensus.exception;

import lombok.Getter;

@Getter
public enum ErrorMessages {
    LinkCreationError("Error creating link"),
    NoSuchNodeError("No such node"),
    SendingError("Error sending message"),
    ReceivingError("Error receiving message"),
    ;
    private final String message;

    ErrorMessages(String message) {
        this.message = message;
    }

}
