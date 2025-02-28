package consensus.exception;

import lombok.Getter;

@Getter
public enum ErrorMessages {
    LinkClosedException("Link already closed"),
    LinkCreationError("Error creating link"),
    NoSuchNodeError("No such node"),
    SendingError("Error sending message"),
    ReceivingError("Error receiving message"),
    PrivateKeyError("Error reading private key"),
    SignatureError("Error signing message"),
    ;
    private final String message;

    ErrorMessages(String message) {
        this.message = message;
    }

}
