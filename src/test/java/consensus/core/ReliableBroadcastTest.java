package consensus.core;

import consensus.core.model.Message;
import consensus.core.primitives.BroadcastBroker;
import consensus.core.primitives.Link;
import consensus.util.Process;
import org.junit.jupiter.api.*;

import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.*;

public class ReliableBroadcastTest {

    private static Link aliceLink;
    private static Link bobLink;
    private static Link carlLink;
    private static Link jeffLink;

    private static BroadcastBroker aliceBroadcast;
    private static BroadcastBroker bobBroadcast;
    private static BroadcastBroker carlBroadcast;
    private static BroadcastBroker jeffBroadcast;

    @BeforeAll
    public static void startLinks() throws Exception {
        // Assemble
        Process aliceProcess = new Process(1, "localhost", 1024, 1024);
        Process bobProcess = new Process(2, "localhost", 1025, 1025);
        Process carlProcess = new Process(3, "localhost", 1026, 1026);
        Process jeffProcess = new Process(4, "localhost", 1027, 1027);

        aliceLink = new Link(
                aliceProcess,
                new Process[]{bobProcess, carlProcess, jeffProcess},
                200
        );

        bobLink = new Link(
                bobProcess,
                new Process[]{aliceProcess, carlProcess, jeffProcess},
                200
        );

        carlLink = new Link(
                carlProcess,
                new Process[]{bobProcess, aliceProcess, jeffProcess},
                200
        );

        jeffLink = new Link(
                jeffProcess,
                new Process[]{bobProcess, carlProcess, aliceProcess},
                200
        );

        aliceBroadcast = new BroadcastBroker(
                aliceProcess,
                new Process[]{bobProcess, carlProcess, jeffProcess},
                aliceLink,
                1
        );

        bobBroadcast = new BroadcastBroker(
                bobProcess,
                new Process[]{aliceProcess, carlProcess, jeffProcess},
                bobLink,
                1
        );

        carlBroadcast = new BroadcastBroker(
                carlProcess,
                new Process[]{bobProcess, aliceProcess, jeffProcess},
                carlLink,
                1
        );

        jeffBroadcast = new BroadcastBroker(
                jeffProcess,
                new Process[]{bobProcess, carlProcess, aliceProcess},
                jeffLink,
                1
        );
    }

    @Test
    public void simpleBroadcast() throws Exception {
        // Assemble
        ConcurrentLinkedQueue<AssertionError> failures = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Exception> errors = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Thread> threads = new ConcurrentLinkedQueue<>();

        Message aliceMessage = new Message(1, Message.Type.WRITE, "hello.");

        // Assert
        for(BroadcastBroker broadcast :
                new BroadcastBroker[]{aliceBroadcast, bobBroadcast, carlBroadcast, jeffBroadcast}) {
            threads.add(new Thread(() -> {
                try {
                    assertEquals(aliceMessage, broadcast.receiveMessage());
                } catch (Throwable e) {
                    if(e instanceof AssertionError) {
                        failures.add((AssertionError) e);
                    } else if(e instanceof Exception) {
                        errors.add((Exception) e);
                    }
                }
            }));
        }
        threads.forEach(Thread::start);

        // Act
        aliceBroadcast.broadcast(aliceMessage);

        threads.forEach(thread -> {
            try {
                thread.join();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        if(!failures.isEmpty()) {
            throw failures.peek();
        }

        if(!errors.isEmpty()) {
            throw errors.peek();
        }
    }

    @Test
    public void excludedOneByzantineBroadcast() throws Exception {
        // Assemble
        ConcurrentLinkedQueue<AssertionError> failures = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Exception> errors = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Thread> threads = new ConcurrentLinkedQueue<>();

        Message byzantineMessage = new Message(4, Message.Type.SEND, "ha. ha.");

        for(BroadcastBroker broadcast :
                new BroadcastBroker[]{aliceBroadcast, bobBroadcast, carlBroadcast, jeffBroadcast}) {
            threads.add(new Thread(() -> {
                try {
                    // Assert
                    assertEquals(byzantineMessage, broadcast.receiveMessage());
                } catch (Throwable e) {
                    if(e instanceof AssertionError) {
                        failures.add((AssertionError) e);
                    } else if(e instanceof Exception) {
                        errors.add((Exception) e);
                    }
                }
            }));
        }
        threads.forEach(Thread::start);

        // Act
        // Only sending to Alice and Bob, Carl doesn't get sent the message.
        jeffLink.send(1, byzantineMessage);
        jeffLink.send(2, byzantineMessage);
        jeffLink.send(4, byzantineMessage);

        threads.forEach(thread -> {
            try {
                thread.join();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        if(!failures.isEmpty()) {
            throw failures.peek();
        }

        if(!errors.isEmpty()) {
            throw errors.peek();
        }
    }

    @Test
    public void differentMessagesByzantineBroadcast() throws Exception {
        // Assemble
        ConcurrentLinkedQueue<AssertionError> failures = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Exception> errors = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Thread> threads = new ConcurrentLinkedQueue<>();

        Message normalMessage = new Message(4, Message.Type.SEND, "smiley face.");
        Message byzantineMessage = new Message(4, Message.Type.SEND, "ha. ha.");

        for(BroadcastBroker broadcast :
                new BroadcastBroker[]{aliceBroadcast, bobBroadcast, carlBroadcast, jeffBroadcast}) {
            threads.add(new Thread(() -> {
                try {
                    // Assert
                    assertEquals(normalMessage, broadcast.receiveMessage());
                } catch (Throwable e) {
                    if(e instanceof AssertionError) {
                        failures.add((AssertionError) e);
                    } else if(e instanceof Exception) {
                        errors.add((Exception) e);
                    }
                }
            }));
        }
        threads.forEach(Thread::start);

        // Act
        // Sending normalMessage to Alice and Bob, but Carl gets sent another message.
        jeffLink.send(1, normalMessage);
        jeffLink.send(2, normalMessage);
        jeffLink.send(3, byzantineMessage);
        jeffLink.send(4, normalMessage);

        threads.forEach(thread -> {
            try {
                thread.join();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        if(!failures.isEmpty()) {
            throw failures.peek();
        }

        if(!errors.isEmpty()) {
            throw errors.peek();
        }
    }

    @AfterAll
    public static void stopLinks() {
        aliceLink.close();
        bobLink.close();
        carlLink.close();
        jeffLink.close();
    }
}
