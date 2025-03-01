package consensus.core;

import consensus.exception.LinkException;
import consensus.util.Process;
import lombok.SneakyThrows;
import org.junit.jupiter.api.*;

import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.*;

public class ReliableBroadcastTest {

    private static Process aliceProcess;
    private static Process bobProcess;
    private static Process carlProcess;
    private static Process jeffProcess;

    private static Link aliceLink;
    private static Link bobLink;
    private static Link carlLink;
    private static Link jeffLink;

    private static ReliableBroadcast aliceBroadcast;
    private static ReliableBroadcast bobBroadcast;
    private static ReliableBroadcast carlBroadcast;
    private static ReliableBroadcast jeffBroadcast;

    @BeforeAll
    public static void startLinks() throws Exception {
        // Assemble
        aliceProcess = new Process(1, "localhost", 1024, 1024);
        bobProcess = new Process(2, "localhost", 1025, 1025);
        carlProcess = new Process(3, "localhost", 1026, 1026);
        jeffProcess = new Process(4, "localhost", 1027, 1027);

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
    }

    @BeforeEach
    public void startBroadcast() throws LinkException {
        aliceBroadcast = new ReliableBroadcast(
                aliceProcess,
                new Process[]{bobProcess, carlProcess, jeffProcess},
                aliceLink,
                1
        );

        bobBroadcast = new ReliableBroadcast(
                bobProcess,
                new Process[]{aliceProcess, carlProcess, jeffProcess},
                bobLink,
                1
        );

        carlBroadcast = new ReliableBroadcast(
                carlProcess,
                new Process[]{bobProcess, aliceProcess, jeffProcess},
                carlLink,
                1
        );

        jeffBroadcast = new ReliableBroadcast(
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
        for(ReliableBroadcast broadcast :
                new ReliableBroadcast[]{aliceBroadcast, bobBroadcast, carlBroadcast, jeffBroadcast}) {
            threads.add(new Thread(() -> {
                try {
                    assertEquals(aliceMessage, broadcast.collect());
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

        // Act
        // Only sending to Alice and Bob, Carl doesn't get sent the message.
        jeffLink.send(1, byzantineMessage);
        jeffLink.send(2, byzantineMessage);
        jeffLink.send(4, byzantineMessage);

        // Assert
        for(ReliableBroadcast broadcast :
                new ReliableBroadcast[]{aliceBroadcast, bobBroadcast, carlBroadcast, jeffBroadcast}) {
            threads.add(new Thread(() -> {
                try {
                    assertEquals(byzantineMessage, broadcast.collect());
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

        // Act
        // Sending normalMessage to Alice and Bob, but Carl gets sent another message.
        jeffLink.send(1, normalMessage);
        jeffLink.send(2, normalMessage);
        jeffLink.send(3, byzantineMessage);
        jeffLink.send(4, normalMessage);

        // Assert
        for(ReliableBroadcast broadcast :
                new ReliableBroadcast[]{aliceBroadcast, bobBroadcast, carlBroadcast, jeffBroadcast}) {
            threads.add(new Thread(() -> {
                try {
                    assertEquals(normalMessage, broadcast.collect());
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
