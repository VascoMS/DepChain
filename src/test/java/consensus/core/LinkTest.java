package consensus.core;

import consensus.core.model.Message;
import consensus.core.primitives.Link;
import consensus.exception.ErrorMessages;
import consensus.exception.LinkException;
import consensus.util.Observer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import consensus.util.Process;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;

import static org.junit.jupiter.api.Assertions.*;

public class LinkTest {

    private static Process aliceProcess;
    private static Process bobProcess;

    private static Link aliceLink;
    private static Link bobLink;

    @BeforeAll
    public static void startLinks() throws Exception {
        // Assemble
        aliceProcess = new Process(1, "localhost", 1024, 1024);
        bobProcess = new Process(2, "localhost", 1025, 1025);

        aliceLink = new Link(aliceProcess, new Process[]{bobProcess}, 100);
        bobLink = new Link(bobProcess, new Process[]{aliceProcess}, 100);
    }

    @Test
    public void simpleSendAndReceive() throws Exception {

        CountDownLatch latch = new CountDownLatch(1);
        ConcurrentLinkedQueue<AssertionError> failures = new ConcurrentLinkedQueue<>();

        Message aliceMessage = new Message(1, 2, Message.Type.RECEIVE, "hello");

        // Assert
        Observer bobObserver = new Observer() {
            @Override
            public void update(Message message) {
                try {
                    System.out.println("Received message.");
                    // Assert
                    assertEquals(message, aliceMessage);
                    latch.countDown();
                } catch(AssertionError failure) {
                    failures.add(failure);
                }

            }
        };

        bobLink.addObserver(bobObserver);
        aliceLink.send(2, aliceMessage);
        latch.await();

        if(!failures.isEmpty()) {
            throw failures.peek();
        }

    }

    @Test
    public void sendingToUnknownPeer() {
        assertThrows(
                LinkException.class,
                () -> { aliceLink.send(100, new Message(
                        1,
                        100,
                        Message.Type.RECEIVE,
                        "hello"
                ));},
                ErrorMessages.NoSuchNodeError.getMessage()
        );
    }

    @Test
    public void noSendOrReceiveAfterClose() throws Exception {
        Process process = new Process(999, "localhost", 1030, 1030);
        Link link = new Link(process, new Process[] {aliceProcess, bobProcess}, 200);
        link.close();
        assertThrows(
                LinkException.class,
                () -> { link.send(1, new Message(
                        999,
                        1,
                        Message.Type.RECEIVE,
                        "hello"
                ));},
                ErrorMessages.LinkClosedException.getMessage()
        );
    }

    @AfterAll
    public static void stopLinks() {
        aliceLink.close();
        bobLink.close();
    }

}
