package consensus.core;

import consensus.core.model.Transaction;
import consensus.core.model.WritePair;
import consensus.core.model.WriteState;
import consensus.core.primitives.ConsensusBroker;
import consensus.core.primitives.Link;
import consensus.util.Process;
import consensus.util.SecurityUtil;
import org.junit.jupiter.api.*;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

public class ConsensusTest {

    private static Link aliceLink;
    private static Link bobLink;
    private static Link carlLink;
    private static Link jeffLink;

    private static ConsensusBroker aliceBroker;
    private static ConsensusBroker bobBroker;
    private static ConsensusBroker carlBroker;
    private static ConsensusBroker jeffBroker;

    private static WriteState aliceState;
    private static WriteState bobState;
    private static WriteState carlState;
    private static WriteState jeffState;

    private static KeyService keyService;

    @BeforeAll
    public static void startLinks() throws Exception {
        // Assemble
        Process aliceProcess = new Process(1, "localhost", 1024, 1024);
        Process bobProcess = new Process(2, "localhost", 1025, 1025);
        Process carlProcess = new Process(3, "localhost", 1026, 1026);
        Process jeffProcess = new Process(4, "localhost", 1027, 1027);

        aliceState = new WriteState();
        bobState = new WriteState();
        carlState = new WriteState();
        jeffState = new WriteState();

        keyService = new KeyService(SecurityUtil.KEYSTORE_PATH, "mypass");

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

        aliceBroker = new ConsensusBroker(
                aliceProcess,
                new Process[]{bobProcess, carlProcess, jeffProcess},
                aliceLink,
                1,
                keyService,
                aliceState
        );

        bobBroker = new ConsensusBroker(
                bobProcess,
                new Process[]{aliceProcess, carlProcess, jeffProcess},
                bobLink,
                1,
                keyService,
                bobState
        );

        carlBroker = new ConsensusBroker(
                carlProcess,
                new Process[]{bobProcess, aliceProcess, jeffProcess},
                carlLink,
                1,
                keyService,
                carlState
        );

        jeffBroker = new ConsensusBroker(
                jeffProcess,
                new Process[]{bobProcess, carlProcess, aliceProcess},
                jeffLink,
                1,
                keyService,
                jeffState
        );
    }

    @Test
    public void simpleConsensus() throws Exception {
        // Assemble
        ConcurrentLinkedQueue<AssertionError> failures = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Exception> errors = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Thread> threads = new ConcurrentLinkedQueue<>();

        CountDownLatch latch = new CountDownLatch(4);

        Transaction transaction = new Transaction("0", "0", "hello.", null);

        // Assert
        for(ConsensusBroker consensus :
                new ConsensusBroker[]{aliceBroker, bobBroker, carlBroker, jeffBroker}) {
            threads.add(new Thread(() -> {
                try {
                    assertEquals("hello.", consensus.receiveMessage().content());
                } catch (Throwable e) {
                    if(e instanceof AssertionError) {
                        failures.add((AssertionError) e);
                    } else if(e instanceof Exception) {
                        errors.add((Exception) e);
                    }
                } finally {
                    latch.countDown();
                }
            }));
        }
        threads.forEach(Thread::start);

        // Act
        aliceState.setLastestWrite(new WritePair(0, transaction));
        aliceBroker.startConsensus().get();

        latch.await();

        if(!failures.isEmpty()) {
            throw failures.peek();
        }

        if(!errors.isEmpty()) {
            throw errors.peek();
        }
    }
 /*
    @Test
    public void excludedOneByzantineBroadcast() throws Exception {
        // Assemble
        ConcurrentLinkedQueue<AssertionError> failures = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Exception> errors = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Thread> threads = new ConcurrentLinkedQueue<>();

        CountDownLatch latch = new CountDownLatch(4);

        BroadcastPayload payload = new BroadcastPayload(
                4,
                BroadcastPayload.BroadcastType.SEND,
                "ha ha."
        );
        Message message = new Message(
                4, Message.Type.BROADCAST, new Gson().toJson(payload)
        );

        for(ConsensusBroker broadcast :
                new ConsensusBroker[]{aliceBroker, bobBroker, carlBroker, jeffBroker}) {
            threads.add(new Thread(() -> {
                try {
                    // Assert
                    assertEquals("ha ha.", broadcast.receiveMessage());
                } catch (Throwable e) {
                    if(e instanceof AssertionError) {
                        failures.add((AssertionError) e);
                    } else if(e instanceof Exception) {
                        errors.add((Exception) e);
                    }
                } finally {
                    latch.countDown();
                }
            }));
        }
        threads.forEach(Thread::start);

        // Act
        // Only sending to Alice and Bob, Carl doesn't get sent the message.
        jeffLink.send(1, message);
        jeffLink.send(2, message);
        jeffLink.send(4, message);

        latch.await();

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

        CountDownLatch latch = new CountDownLatch(4);

        BroadcastPayload normalPayload = new BroadcastPayload(
                4,
                BroadcastPayload.BroadcastType.SEND,
                "hello."
        );
        Message normalMessage = new Message(
                4, Message.Type.BROADCAST, new Gson().toJson(normalPayload)
        );

        BroadcastPayload byzantinePayload = new BroadcastPayload(
                4,
                normalPayload.getBroadcastId(),
                BroadcastPayload.BroadcastType.SEND,
                "hell-o."
        );
        Message byzantineMessage = new Message(
                4, Message.Type.BROADCAST, new Gson().toJson(byzantinePayload)
        );

        for(ConsensusBroker broadcast :
                new ConsensusBroker[]{aliceBroker, bobBroker, carlBroker, jeffBroker}) {
            threads.add(new Thread(() -> {
                try {
                    // Assert
                    assertEquals("hello.", broadcast.receiveMessage());
                } catch (Throwable e) {
                    if(e instanceof AssertionError) {
                        failures.add((AssertionError) e);
                    } else if(e instanceof Exception) {
                        errors.add((Exception) e);
                    }
                } finally {
                    latch.countDown();
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

        latch.await();

        if(!failures.isEmpty()) {
            throw failures.peek();
        }

        if(!errors.isEmpty()) {
            throw errors.peek();
        }
    }
  */

    @AfterAll
    public static void stopLinks() {
        aliceLink.close();
        bobLink.close();
        carlLink.close();
        jeffLink.close();
    }
}
