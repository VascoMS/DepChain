package server.consensus.core;

import com.google.gson.Gson;
import common.model.Transaction;
import common.model.Message;
import server.consensus.core.model.*;
import server.consensus.core.primitives.ConsensusBroker;
import common.primitives.Link;
import server.consensus.test.ConsensusByzantineMode;
import util.KeyService;
import util.Observer;
import util.Process;
import util.SecurityUtil;
import org.junit.jupiter.api.*;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;


public class ConsensusTest {

    private static Link aliceLink;
    private static Link bobLink;
    private static Link carlLink;
    private static Link jeffLink;
    private static final String privateKeyPrefix = "p";

    private static ConsensusBroker aliceBroker;
    private static ConsensusBroker bobBroker;
    private static ConsensusBroker carlBroker;
    private static ConsensusBroker jeffBroker;

    private static KeyService serverKeyService;
    private static KeyService clientKeyService;


    @BeforeAll
    public static void startLinks() throws Exception {
        // Assemble
        Process aliceProcess = new Process(0, "localhost", 1024);
        Process bobProcess = new Process(1, "localhost", 1025);
        Process carlProcess = new Process(2, "localhost", 1026);
        Process jeffProcess = new Process(3, "localhost", 1027);

        serverKeyService = new KeyService(SecurityUtil.SERVER_KEYSTORE_PATH, "mypass");
        clientKeyService = new KeyService(SecurityUtil.CLIENT_KEYSTORE_PATH, "mypass");

        aliceLink = new Link(
                aliceProcess,
                new Process[]{bobProcess, carlProcess, jeffProcess}, Link.Type.SERVER_TO_SERVER,
                100, privateKeyPrefix, privateKeyPrefix, SecurityUtil.SERVER_KEYSTORE_PATH
        );

        bobLink = new Link(
                bobProcess,
                new Process[]{aliceProcess, carlProcess, jeffProcess}, Link.Type.SERVER_TO_SERVER,
                100, privateKeyPrefix, privateKeyPrefix, SecurityUtil.SERVER_KEYSTORE_PATH
        );

        carlLink = new Link(
                carlProcess,
                new Process[]{bobProcess, aliceProcess, jeffProcess}, Link.Type.SERVER_TO_SERVER,
                100, privateKeyPrefix, privateKeyPrefix, SecurityUtil.SERVER_KEYSTORE_PATH
        );

        jeffLink = new Link(
                jeffProcess,
                new Process[]{bobProcess, carlProcess, aliceProcess}, Link.Type.SERVER_TO_SERVER,
                100, privateKeyPrefix, privateKeyPrefix, SecurityUtil.SERVER_KEYSTORE_PATH
        );

        aliceBroker = new ConsensusBroker(
                aliceProcess,
                new Process[]{bobProcess, carlProcess, jeffProcess},
                aliceLink,
                1,
                serverKeyService,
                new StringState()
        );

        bobBroker = new ConsensusBroker(
                bobProcess,
                new Process[]{aliceProcess, carlProcess, jeffProcess},
                bobLink,
                1,
                serverKeyService,
                new StringState()
        );

        carlBroker = new ConsensusBroker(
                carlProcess,
                new Process[]{bobProcess, aliceProcess, jeffProcess},
                carlLink,
                1,
                serverKeyService,
                new StringState()
        );

        jeffBroker = new ConsensusBroker(
                jeffProcess,
                new Process[]{bobProcess, carlProcess, aliceProcess},
                jeffLink,
                1,
                serverKeyService,
                new StringState()
        );
    }

    @Test
    public void simpleConsensus() throws Exception {
        // Assemble
        ConcurrentLinkedQueue<AssertionError> failures = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Exception> errors = new ConcurrentLinkedQueue<>();

        CountDownLatch latch = new CountDownLatch(4);

        Transaction transaction = generateTransaction("hello.");
        aliceBroker.addClientRequest(transaction);

        Observer<ConsensusOutcomeDto> tester = outcome -> {
            try {
                assertEquals(outcome.decision().id(), transaction.id());
            } catch (Throwable e) {
                if (e instanceof AssertionError) {
                    failures.add((AssertionError) e);
                } else if (e instanceof Exception) {
                    errors.add((Exception) e);
                }
            } finally {
                latch.countDown();
            }
        };

        aliceBroker.addObserver(tester);
        bobBroker.addObserver(tester);
        carlBroker.addObserver(tester);
        jeffBroker.addObserver(tester);

        // Act
        aliceBroker.startConsensus();
        latch.await();

        if(!failures.isEmpty()) {
            throw failures.peek();
        }

        if(!errors.isEmpty()) {
            throw errors.peek();
        }

        aliceBroker.removeObserver(tester);
        bobBroker.removeObserver(tester);
        carlBroker.removeObserver(tester);
        jeffBroker.removeObserver(tester);
    }

    @Test
    public void consensusWithSeveralProposes() throws Exception {
        // Assemble
        ConcurrentLinkedQueue<AssertionError> failures = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Exception> errors = new ConcurrentLinkedQueue<>();

        CountDownLatch latch = new CountDownLatch(4);

        Transaction aliceTransaction = generateTransaction("hello.");
        Transaction bobTransaction = generateTransaction("hell-o");
        Transaction carlTransaction = generateTransaction("he-llo");

        aliceBroker.addClientRequest(aliceTransaction);
        bobBroker.addClientRequest(bobTransaction);
        carlBroker.addClientRequest(carlTransaction);

        Observer<ConsensusOutcomeDto> tester = outcome -> {
            try {
                assertEquals(outcome.decision().id(), aliceTransaction.id());
            } catch (Throwable e) {
                if (e instanceof AssertionError) {
                    failures.add((AssertionError) e);
                } else if (e instanceof Exception) {
                    errors.add((Exception) e);
                }
            } finally {
                latch.countDown();
            }
        };

        aliceBroker.addObserver(tester);
        bobBroker.addObserver(tester);
        carlBroker.addObserver(tester);
        jeffBroker.addObserver(tester);


        // Act
        aliceBroker.startConsensus();

        latch.await();

        if(!failures.isEmpty()) {
            throw failures.peek();
        }

        if(!errors.isEmpty()) {
            throw errors.peek();
        }

        aliceBroker.removeObserver(tester);
        bobBroker.removeObserver(tester);
        carlBroker.removeObserver(tester);
        jeffBroker.removeObserver(tester);
    }

    @Test
    public void quietNonLeaderConsensus() throws Exception {
        // Assemble
        ConcurrentLinkedQueue<AssertionError> failures = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Exception> errors = new ConcurrentLinkedQueue<>();

        CountDownLatch latch = new CountDownLatch(3);

        Transaction aliceTransaction = generateTransaction("hello.");
        Transaction bobTransaction = generateTransaction("hell-o");
        Transaction carlTransaction = generateTransaction("he-llo");

        aliceBroker.addClientRequest(aliceTransaction);
        bobBroker.addClientRequest(bobTransaction);
        carlBroker.addClientRequest(carlTransaction);

        Observer<ConsensusOutcomeDto> tester = outcome -> {
            try {
                assertEquals(outcome.decision().id(), aliceTransaction.id());
            } catch (Throwable e) {
                if (e instanceof AssertionError) {
                    failures.add((AssertionError) e);
                } else if (e instanceof Exception) {
                    errors.add((Exception) e);
                }
            } finally {
                latch.countDown();
            }
        };

        aliceBroker.addObserver(tester);
        bobBroker.addObserver(tester);
        carlBroker.addObserver(tester);

        // Jeff is the byzantine, he'll stop sending messages.
        jeffBroker.becomeByzantine(ConsensusByzantineMode.DROP_ALL);

        // Act
        aliceBroker.startConsensus();

        latch.await();

        if(!failures.isEmpty()) {
            throw failures.peek();
        }

        if(!errors.isEmpty()) {
            throw errors.peek();
        }

        aliceBroker.removeObserver(tester);
        bobBroker.removeObserver(tester);
        carlBroker.removeObserver(tester);
    }

    @Test
    public void quietLeaderConsensus() throws Exception {
        // Assemble
        ConcurrentLinkedQueue<AssertionError> failures = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Exception> errors = new ConcurrentLinkedQueue<>();

        CountDownLatch nullLatch = new CountDownLatch(3);
        CountDownLatch successLatch = new CountDownLatch(3);

        Transaction aliceTransaction = generateTransaction("hello.");
        Transaction bobTransaction = generateTransaction("hell-o");
        Transaction carlTransaction = generateTransaction("he-llo");

        aliceBroker.addClientRequest(aliceTransaction);
        bobBroker.addClientRequest(bobTransaction);
        carlBroker.addClientRequest(carlTransaction);

        Observer<ConsensusOutcomeDto> nullTester = outcome -> {
            try {
                assertNull(outcome.decision());
            } catch (Throwable e) {
                if (e instanceof AssertionError) {
                    failures.add((AssertionError) e);
                } else if (e instanceof Exception) {
                    errors.add((Exception) e);
                }
            } finally {
                nullLatch.countDown();
            }
        };

        bobBroker.addObserver(nullTester);
        carlBroker.addObserver(nullTester);
        jeffBroker.addObserver(nullTester);

        // Alice is the byzantine, she'll stop sending messages.
        aliceBroker.becomeByzantine(ConsensusByzantineMode.DROP_ALL);

        // Act
        aliceBroker.startConsensus();

        nullLatch.await();

        if(!failures.isEmpty()) {
            throw failures.peek();
        }

        if(!errors.isEmpty()) {
            throw errors.peek();
        }

        bobBroker.removeObserver(nullTester);
        carlBroker.removeObserver(nullTester);
        jeffBroker.removeObserver(nullTester);

        // Assert
        Observer<ConsensusOutcomeDto> successTester = outcome -> {
            try {
                assertEquals(outcome.decision(), bobTransaction);
            } catch (Throwable e) {
                if (e instanceof AssertionError) {
                    failures.add((AssertionError) e);
                } else if (e instanceof Exception) {
                    errors.add((Exception) e);
                }
            } finally {
                successLatch.countDown();
            }
        };

        bobBroker.addObserver(successTester);
        carlBroker.addObserver(successTester);
        jeffBroker.addObserver(successTester);

        // Act (Bob restarting consensus)
        bobBroker.startConsensus();

        successLatch.await();

        if(!failures.isEmpty()) {
            throw failures.peek();
        }

        if(!errors.isEmpty()) {
            throw errors.peek();
        }

        bobBroker.removeObserver(successTester);
        carlBroker.removeObserver(successTester);
        jeffBroker.removeObserver(successTester);

        // Making alice catch-up with the rest.
        aliceBroker.skipConsensusRound();
    }

    @Test
    public void differentCollectedConsensus() throws Exception {
        // Assemble
        ConcurrentLinkedQueue<AssertionError> failures = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Exception> errors = new ConcurrentLinkedQueue<>();

        CountDownLatch latch = new CountDownLatch(4);

        Transaction aliceTransaction = generateTransaction("hello.");
        Transaction aliceByzantineTransaction = generateTransaction("hell-o");
        
        WritePair aliceWritePair = new WritePair(0, aliceTransaction);
        WritePair aliceByzantineWritePair = new WritePair(0, aliceByzantineTransaction);

        WriteState aliceState = new WriteState(aliceWritePair, List.of(aliceWritePair));
        WriteState aliceByzantineState = new WriteState(aliceByzantineWritePair, List.of());

        ConsensusPayload aliceStatePayload = new ConsensusPayload(
                0, 999, ConsensusPayload.ConsensusType.STATE, new Gson().toJson(aliceState), serverKeyService
        );
        ConsensusPayload aliceByzantineStatePayload = new ConsensusPayload(
                0, aliceStatePayload.getConsensusId(), ConsensusPayload.ConsensusType.STATE, new Gson().toJson(aliceByzantineState), serverKeyService
        );
        ConsensusPayload bobStatePayload = new ConsensusPayload(
                1, aliceStatePayload.getConsensusId(), ConsensusPayload.ConsensusType.STATE, new Gson().toJson(new WriteState()), serverKeyService
        );
        ConsensusPayload carlStatePayload = new ConsensusPayload(
                2, aliceStatePayload.getConsensusId(), ConsensusPayload.ConsensusType.STATE,new Gson().toJson(new WriteState()), serverKeyService
        );
        ConsensusPayload jeffStatePayload = new ConsensusPayload(
                3, aliceStatePayload.getConsensusId(), ConsensusPayload.ConsensusType.STATE, new Gson().toJson(new WriteState()), serverKeyService
        );

        HashMap<Integer, ConsensusPayload> normalStates = new HashMap<>();
        normalStates.put(0, aliceStatePayload);
        normalStates.put(2, carlStatePayload);
        normalStates.put(3, jeffStatePayload);

        ConsensusPayload normalCollectedPayload = new ConsensusPayload(
                0, aliceStatePayload.getConsensusId(), ConsensusPayload.ConsensusType.COLLECTED, new Gson().toJson(normalStates), serverKeyService
        );
        HashMap<Integer, ConsensusPayload> byzantineStates = new HashMap<>();
        byzantineStates.put(0, aliceByzantineStatePayload);
        byzantineStates.put(1, bobStatePayload);
        byzantineStates.put(3, jeffStatePayload);

        ConsensusPayload byzantineCollectedPayload = new ConsensusPayload(
                0, aliceStatePayload.getConsensusId(), ConsensusPayload.ConsensusType.COLLECTED, new Gson().toJson(byzantineStates), serverKeyService
        );

        Message normalMessage = new Message(0, Message.Type.CONSENSUS, new Gson().toJson(normalCollectedPayload));
        Message byzantineMessage = new Message(0, Message.Type.CONSENSUS, new Gson().toJson(byzantineCollectedPayload));

        Observer<ConsensusOutcomeDto> tester = outcome -> {
            try {
                assertNull(outcome.decision());
            } catch (Throwable e) {
                if (e instanceof AssertionError) {
                    failures.add((AssertionError) e);
                } else if (e instanceof Exception) {
                    errors.add((Exception) e);
                }
            } finally {
                latch.countDown();
            }
        };

        aliceBroker.addObserver(tester);
        bobBroker.addObserver(tester);
        carlBroker.addObserver(tester);
        jeffBroker.addObserver(tester);

        // Act
        aliceLink.send(0, normalMessage);
        aliceLink.send(1, byzantineMessage);
        aliceLink.send(2, byzantineMessage);
        aliceLink.send(3, normalMessage);

        latch.await();

        if(!failures.isEmpty()) {
            throw failures.peek();
        }

        if(!errors.isEmpty()) {
            throw errors.peek();
        }

        aliceBroker.removeObserver(tester);
        bobBroker.removeObserver(tester);
        carlBroker.removeObserver(tester);
        jeffBroker.removeObserver(tester);
    }

    private Transaction generateTransaction(String content) throws Exception {
        String transactionId = UUID.randomUUID().toString();
        int clientId = 1;
        String signature = SecurityUtil.signTransaction(transactionId, clientId, content, clientKeyService.loadPrivateKey("c" + clientId));
        return new Transaction(transactionId, clientId, clientId + 1, content, signature);
    }

    @AfterEach
    public void normalize() {
        for (ConsensusBroker broker: new ConsensusBroker[]{aliceBroker, bobBroker, carlBroker, jeffBroker}) {
            broker.returnToNormal();
            broker.resetEpoch();
            broker.clearClientQueue();
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
