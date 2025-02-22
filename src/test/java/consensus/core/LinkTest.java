package consensus.core;

import consensus.exception.LinkException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import consensus.util.Process;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class LinkTest {

    private static Process aliceProcess;
    private static Process bobProcess;

    private static Link aliceLink;
    private static Link bobLink;

    @BeforeAll
    public static void startLinks() throws LinkException {
        // Assemble
        aliceProcess = new Process(1, "localhost", 1024, 1024);
        bobProcess = new Process(2, "localhost", 1025, 1025);

        aliceLink = new Link(aliceProcess, new Process[]{bobProcess}, 100);
        bobLink = new Link(bobProcess, new Process[]{aliceProcess}, 100);
    }

    @Test
    public void simpleSendAndReceive() throws LinkException {

        // Act
        aliceLink.send(2, new Message(1, Message.Type.RECEIVE));

        // Assert
        Message bobMessage = bobLink.receive();
        assertEquals(1, bobMessage.getSenderId());
        assertEquals(Message.Type.RECEIVE, bobMessage.getType());

        Message aliceMessage = aliceLink.receive();
        assertEquals(2, aliceMessage.getSenderId());
        assertEquals(Message.Type.ACK, aliceMessage.getType());

        assertEquals(bobMessage.getMessageId(), aliceMessage.getMessageId());
    }

    @Test
    public void sendToSelf() throws LinkException, InterruptedException {
        // Assemble
        AtomicBoolean fail = new AtomicBoolean(false);

        // Act
        aliceLink.send(1, new Message(1, Message.Type.RECEIVE));

        // Assert
        Message aliceMessage = aliceLink.receive();
        assertEquals(1, aliceMessage.getSenderId());
        assertEquals(Message.Type.RECEIVE, aliceMessage.getType());

        // Act
        Thread listeningThread = new Thread(() -> {
            try {
                aliceLink.receive();
                fail.set(true);
            } catch (LinkException e) {
                throw new RuntimeException(e);
            }
        });

        listeningThread.start();
        Thread.sleep(3000);
        listeningThread.interrupt();

        // Assert
        if (fail.get()) {
            fail();
        }
    }

}
