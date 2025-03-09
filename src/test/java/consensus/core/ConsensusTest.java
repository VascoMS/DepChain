package consensus.core;

import com.google.gson.Gson;
import consensus.core.model.*;
import consensus.core.primitives.ConsensusBroker;
import consensus.core.primitives.Link;
import consensus.util.Observer;
import consensus.util.Process;
import consensus.util.SecurityUtil;
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
        Process aliceProcess = new Process(0, "localhost", 1024, 1124);
        Process bobProcess = new Process(1, "localhost", 1025, 1125);
        Process carlProcess = new Process(2, "localhost", 1026, 1126);
        Process jeffProcess = new Process(3, "localhost", 1027, 1127);

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
                0, 999, ConsensusPayload.ConsensusType.STATE, new Gson().toJson(aliceState), keyService
        );
        ConsensusPayload aliceByzantineStatePayload = new ConsensusPayload(
                0, aliceStatePayload.getConsensusId(), ConsensusPayload.ConsensusType.STATE, new Gson().toJson(aliceByzantineState), keyService
        );
        ConsensusPayload bobStatePayload = new ConsensusPayload(
                1, aliceStatePayload.getConsensusId(), ConsensusPayload.ConsensusType.STATE, new Gson().toJson(new WriteState()), keyService
        );
        ConsensusPayload carlStatePayload = new ConsensusPayload(
                2, aliceStatePayload.getConsensusId(), ConsensusPayload.ConsensusType.STATE,new Gson().toJson(new WriteState()), keyService
        );
        ConsensusPayload jeffStatePayload = new ConsensusPayload(
                3, aliceStatePayload.getConsensusId(), ConsensusPayload.ConsensusType.STATE, new Gson().toJson(new WriteState()), keyService
        );

        HashMap<Integer, ConsensusPayload> normalStates = new HashMap<>();
        normalStates.put(0, aliceStatePayload);
        normalStates.put(2, carlStatePayload);
        normalStates.put(3, jeffStatePayload);

        ConsensusPayload normalCollectedPayload = new ConsensusPayload(
                0, aliceStatePayload.getConsensusId(), ConsensusPayload.ConsensusType.COLLECTED, new Gson().toJson(normalStates), keyService
        );
        HashMap<Integer, ConsensusPayload> byzantineStates = new HashMap<>();
        byzantineStates.put(0, aliceByzantineStatePayload);
        byzantineStates.put(1, bobStatePayload);
        byzantineStates.put(3, jeffStatePayload);

        ConsensusPayload byzantineCollectedPayload = new ConsensusPayload(
                0, aliceStatePayload.getConsensusId(), ConsensusPayload.ConsensusType.COLLECTED, new Gson().toJson(byzantineStates), keyService
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
