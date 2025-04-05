package server.consensus.core;

import common.model.Message;
import common.primitives.AuthenticatedPerfectLink;
import common.primitives.LinkType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.consensus.exception.ErrorMessages;
import server.consensus.exception.LinkException;
import util.Observer;
import util.Process;
import util.SecurityUtil;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class LinkTest {

    private static Process aliceProcess;
    private static Process bobProcess;

    private static AuthenticatedPerfectLink aliceLink;
    private static AuthenticatedPerfectLink bobLink;

    @BeforeAll
    public static void startLinks() throws Exception {
        // Assemble

        aliceProcess = new Process("p1", "localhost", 1024);
        bobProcess = new Process("p2", "localhost", 1025);

        aliceLink = new AuthenticatedPerfectLink(aliceProcess, new Process[]{bobProcess}, LinkType.SERVER_TO_SERVER,100, SecurityUtil.SERVER_KEYSTORE_PATH);
        bobLink = new AuthenticatedPerfectLink(bobProcess, new Process[]{aliceProcess}, LinkType.SERVER_TO_SERVER,  100, SecurityUtil.SERVER_KEYSTORE_PATH);

        aliceLink.start();
        bobLink.start();
    }

    @Test
    public void simpleSendAndReceive() throws Exception {

        CountDownLatch latch = new CountDownLatch(1);
        ConcurrentLinkedQueue<AssertionError> failures = new ConcurrentLinkedQueue<>();

        Message aliceMessage = new Message("p1", "p2", Message.Type.UNICAST, "hello");

        // Assert
        Observer<Message> bobObserver = message -> {
            try {
                System.out.println("Received message.");
                // Assert
                assertEquals(message, aliceMessage);
                latch.countDown();
            } catch(AssertionError failure) {
                failures.add(failure);
            }

        };

        bobLink.addObserver(bobObserver);
        aliceLink.send("p2", aliceMessage);
        latch.await();
        bobLink.removeObserver(bobObserver);

        if(!failures.isEmpty()) {
            throw failures.peek();
        }

    }

    @Test
    public void sendToSelf() throws Exception {

        CountDownLatch latch = new CountDownLatch(1);
        ConcurrentLinkedQueue<AssertionError> failures = new ConcurrentLinkedQueue<>();

        Message aliceMessage = new Message("p1", "p1", Message.Type.UNICAST, "hello");

        // Assert
        Observer<Message> aliceObserver = message -> {
            try {
                System.out.println("Received message.");
                // Assert
                assertEquals(message, aliceMessage);
                latch.countDown();
            } catch(AssertionError failure) {
                failures.add(failure);
            }
        };

        aliceLink.addObserver(aliceObserver);
        aliceLink.send("p1", aliceMessage);
        latch.await();
        aliceLink.removeObserver(aliceObserver);

        if(!failures.isEmpty()) {
            throw failures.peek();
        }

    }

    @Test
    public void sendingToUnknownPeer() {
        assertThrows(
                LinkException.class,
                () -> aliceLink.send("P100", new Message(
                        "P1",
                        "P100",
                        Message.Type.UNICAST,
                        "hello"
                )),
                ErrorMessages.NoSuchNodeError.getMessage()
        );
    }

    @Test
    public void noSendOrReceiveAfterClose() throws Exception {
        Process process = new Process("P999", "localhost", 1030);
        AuthenticatedPerfectLink link = new AuthenticatedPerfectLink(process, new Process[] {aliceProcess, bobProcess}, LinkType.SERVER_TO_SERVER, 200, SecurityUtil.SERVER_KEYSTORE_PATH);
        link.close();
        assertThrows(
                LinkException.class,
                () -> link.send("P1", new Message(
                        "P999",
                        "P1",
                        Message.Type.UNICAST,
                        "hello"
                )),
                ErrorMessages.LinkClosedException.getMessage()
        );
    }

    @AfterAll
    public static void stopLinks() {
        aliceLink.close();
        bobLink.close();
    }

}
