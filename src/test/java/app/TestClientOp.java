package app;

import client.app.ClientOperations;
import java.util.concurrent.ConcurrentLinkedQueue;

@FunctionalInterface
public interface TestClientOp {
    void doOperation(
            ClientOperations client,
            String transferRecipient,
            ConcurrentLinkedQueue<AssertionError> failures,
            ConcurrentLinkedQueue<Exception> errors
    );
}
