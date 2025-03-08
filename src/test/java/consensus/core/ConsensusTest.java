package consensus.core;

import consensus.core.model.Transaction;
import consensus.core.model.WriteState;
import consensus.core.primitives.ConsensusBroker;
import consensus.core.primitives.Link;
import consensus.util.Process;
import consensus.util.SecurityUtil;
import org.junit.jupiter.api.*;

import java.util.UUID;
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
        Process aliceProcess = new Process(0, "localhost", 1024, 1024);
        Process bobProcess = new Process(1, "localhost", 1025, 1025);
        Process carlProcess = new Process(2, "localhost", 1026, 1026);
        Process jeffProcess = new Process(3, "localhost", 1027, 1027);

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
                keyService
        );

        bobBroker = new ConsensusBroker(
                bobProcess,
                new Process[]{aliceProcess, carlProcess, jeffProcess},
                bobLink,
                1,
                keyService
        );

        carlBroker = new ConsensusBroker(
                carlProcess,
                new Process[]{bobProcess, aliceProcess, jeffProcess},
                carlLink,
                1,
                keyService
        );

        jeffBroker = new ConsensusBroker(
                jeffProcess,
                new Process[]{bobProcess, carlProcess, aliceProcess},
                jeffLink,
                1,
                keyService
        );
    }

    @Test
    public void simpleConsensus() throws Exception {
        // Assemble
        ConcurrentLinkedQueue<AssertionError> failures = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Exception> errors = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Thread> threads = new ConcurrentLinkedQueue<>();

        CountDownLatch latch = new CountDownLatch(4);
        CountDownLatch finishBroadcastLatch = new CountDownLatch(1);

        Transaction transaction = generateTransaction("hello.");
        aliceBroker.addClientRequest(transaction);

        // Assert
        for(ConsensusBroker broker :
                new ConsensusBroker[]{aliceBroker, bobBroker, carlBroker, jeffBroker}) {
            threads.add(new Thread(() -> {
                try {
                    finishBroadcastLatch.await();
                    assertTrue(broker.getExecutedTransactions().contains(transaction.id()));
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
        aliceBroker.startConsensus().get();
        finishBroadcastLatch.countDown();
        latch.await();

        if(!failures.isEmpty()) {
            throw failures.peek();
        }

        if(!errors.isEmpty()) {
            throw errors.peek();
        }
    }

    @Test
    public void consensusWithSeveralProposes() throws Exception {
        // Assemble
        ConcurrentLinkedQueue<AssertionError> failures = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Exception> errors = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Thread> threads = new ConcurrentLinkedQueue<>();

        CountDownLatch latch = new CountDownLatch(4);
        CountDownLatch finishBroadcastLatch = new CountDownLatch(1);

        Transaction aliceTransaction = generateTransaction("hello.");
        Transaction bobTransaction = generateTransaction("hell-o");
        Transaction carlTransaction = generateTransaction("he-llo");

        aliceBroker.addClientRequest(aliceTransaction);
        bobBroker.addClientRequest(bobTransaction);
        carlBroker.addClientRequest(carlTransaction);

        // Assert
        for(ConsensusBroker broker :
                new ConsensusBroker[]{aliceBroker, bobBroker, carlBroker, jeffBroker}) {
            threads.add(new Thread(() -> {
                try {
                    finishBroadcastLatch.await();
                    assertTrue(broker.getExecutedTransactions().contains(aliceTransaction.id()));
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
        aliceBroker.startConsensus().get();
        finishBroadcastLatch.countDown();
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
    public void consensusWithSeveralEpochs() throws Exception {
        // Assemble
        ConcurrentLinkedQueue<AssertionError> failures = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Exception> errors = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Thread> threads = new ConcurrentLinkedQueue<>();

        CountDownLatch latch = new CountDownLatch(4);
        CountDownLatch finishBroadcastLatch = new CountDownLatch(1);

        Transaction aliceTransaction = generateTransaction("hello.");
        Transaction bobTransaction = generateTransaction("hell-o");
        Transaction carlTransaction = generateTransaction("he-llo");

        skipEpochs(2);

        WritePair aliceWritePair = new WritePair(0, aliceTransaction);
        WritePair bobWritePair = new WritePair(2, bobTransaction);
        WritePair carlWritePair = new WritePair(1, carlTransaction);

        aliceState.setLatestWrite(aliceWritePair);
        aliceState.addToWriteSet(aliceWritePair);
        aliceState.addToWriteSet(bobWritePair);

        bobState.setLatestWrite(bobWritePair);
        bobState.addToWriteSet(aliceWritePair);
        bobState.addToWriteSet(bobWritePair);

        carlState.setLatestWrite(carlWritePair);
        carlState.addToWriteSet(aliceWritePair);
        carlState.addToWriteSet(carlWritePair);

        jeffState.setLatestWrite(bobWritePair);
        jeffState.addToWriteSet(bobWritePair);

        // Assert
        for(ConsensusBroker broker :
                new ConsensusBroker[]{aliceBroker, bobBroker, carlBroker, jeffBroker}) {
            threads.add(new Thread(() -> {
                try {
                    finishBroadcastLatch.await();
                    assertTrue(broker.getExecutedTransactions().contains(bobTransaction.id()));
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
        carlBroker.startConsensus().get();
        finishBroadcastLatch.countDown();
        latch.await();

        if(!failures.isEmpty()) {
            throw failures.peek();
        }

        if(!errors.isEmpty()) {
            throw errors.peek();
        }
    }


    private void skipEpochs(int increment) {
        for(int i = 0; i < increment; i++) {
            aliceBroker.incrementEpoch();
            bobBroker.incrementEpoch();
            carlBroker.incrementEpoch();
            jeffBroker.incrementEpoch();
        }
    }

     */


    private Transaction generateTransaction(String content) throws Exception {
        String transactionId = UUID.randomUUID().toString();
        String clientId = "1";
        String signature = SecurityUtil.signTransaction(transactionId, clientId, content, keyService.loadPrivateKey("c" + clientId));
        return new Transaction(transactionId, clientId, content, signature);
    }

    @AfterEach
    public void clearStates() {
        aliceState.clear();
        bobState.clear();
        carlState.clear();
        jeffState.clear();

        aliceBroker.resetEpoch();
        bobBroker.resetEpoch();
        carlBroker.resetEpoch();
        jeffBroker.resetEpoch();
    }

    @AfterAll
    public static void stopLinks() {
        aliceLink.close();
        bobLink.close();
        carlLink.close();
        jeffLink.close();
    }
}
