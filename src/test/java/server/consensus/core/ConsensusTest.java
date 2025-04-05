package server.consensus.core;

import com.google.gson.Gson;
import common.model.Message;
import common.model.Transaction;
import common.primitives.AuthenticatedPerfectLink;
import common.primitives.LinkType;
import org.junit.jupiter.api.*;
import org.mockito.Mock;
import org.mockito.Mockito;
import server.blockchain.Blockchain;
import server.blockchain.model.Block;
import server.consensus.core.model.ConsensusOutcomeDto;
import server.consensus.core.model.ConsensusPayload;
import server.consensus.core.model.WritePair;
import server.consensus.core.model.WriteState;
import server.consensus.core.primitives.ConsensusBroker;
import server.consensus.test.ConsensusByzantineMode;
import util.KeyService;
import util.Observer;
import util.Process;
import util.SecurityUtil;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConsensusTest {

    private static Process aliceProcess;
    private static Process bobProcess;
    private static Process carlProcess;
    private static Process jeffProcess;

    private static AuthenticatedPerfectLink aliceLink;
    private static AuthenticatedPerfectLink bobLink;
    private static AuthenticatedPerfectLink carlLink;
    private static AuthenticatedPerfectLink jeffLink;

    @Mock
    private static Blockchain aliceBlockchain;
    @Mock
    private static Blockchain bobBlockchain;
    @Mock
    private static Blockchain carlBlockchain;
    @Mock
    private static Blockchain jeffBlockchain;

    private static ConsensusBroker aliceBroker;
    private static ConsensusBroker bobBroker;
    private static ConsensusBroker carlBroker;
    private static ConsensusBroker jeffBroker;

    private static final AtomicLong nonce = new AtomicLong(0);

    private static KeyService serverKeyService;
    private static KeyService clientKeyService;

    private static final int blockTime = 3000;

    @BeforeAll
    public static void startLinks() throws Exception {
        // Assemble
        aliceProcess = new Process("p0", "localhost", 1024);
        bobProcess = new Process("p1", "localhost", 1025);
        carlProcess = new Process("p2", "localhost", 1026);
        jeffProcess =  new Process("p3", "localhost", 1027);

        serverKeyService = new KeyService(SecurityUtil.SERVER_KEYSTORE_PATH, "mypass");
        clientKeyService = new KeyService(SecurityUtil.CLIENT_KEYSTORE_PATH, "mypass");

        aliceLink = new AuthenticatedPerfectLink(
                aliceProcess,
                new Process[]{bobProcess, carlProcess, jeffProcess}, LinkType.SERVER_TO_SERVER,
                100, SecurityUtil.SERVER_KEYSTORE_PATH
        );

        bobLink = new AuthenticatedPerfectLink(
                bobProcess,
                new Process[]{aliceProcess, carlProcess, jeffProcess}, LinkType.SERVER_TO_SERVER,
                100, SecurityUtil.SERVER_KEYSTORE_PATH
        );

        carlLink = new AuthenticatedPerfectLink(
                carlProcess,
                new Process[]{bobProcess, aliceProcess, jeffProcess}, LinkType.SERVER_TO_SERVER,
                100, SecurityUtil.SERVER_KEYSTORE_PATH
        );

        jeffLink = new AuthenticatedPerfectLink(
                jeffProcess,
                new Process[]{bobProcess, carlProcess, aliceProcess}, LinkType.SERVER_TO_SERVER,
                100, SecurityUtil.SERVER_KEYSTORE_PATH
        );

        aliceBlockchain = Mockito.mock(Blockchain.class);
        bobBlockchain = Mockito.mock(Blockchain.class);
        carlBlockchain = Mockito.mock(Blockchain.class);
        jeffBlockchain = Mockito.mock(Blockchain.class);

        for(Blockchain blockchain: new Blockchain[]{aliceBlockchain, bobBlockchain, carlBlockchain, jeffBlockchain}) {
            Mockito.when(blockchain.validateNextBlock(Mockito.any())).thenReturn(true);
            Mockito.when(blockchain.getLastBlock()).thenReturn(new Block("", List.of(), 0));
        }
    }

    @BeforeEach
    public void readyBrokers() {
        aliceBroker = new ConsensusBroker(
                aliceProcess,
                new Process[]{bobProcess, carlProcess, jeffProcess},
                aliceLink,
                1,
                serverKeyService,
                aliceBlockchain,
                blockTime,
                1
        );

        bobBroker = new ConsensusBroker(
                bobProcess,
                new Process[]{aliceProcess, carlProcess, jeffProcess},
                bobLink,
                1,
                serverKeyService,
                bobBlockchain,
                blockTime,
                1
        );

        carlBroker = new ConsensusBroker(
                carlProcess,
                new Process[]{bobProcess, aliceProcess, jeffProcess},
                carlLink,
                1,
                serverKeyService,
                carlBlockchain,
                blockTime,
                1
        );

        jeffBroker = new ConsensusBroker(
                jeffProcess,
                new Process[]{bobProcess, carlProcess, aliceProcess},
                jeffLink,
                1,
                serverKeyService,
                jeffBlockchain,
                blockTime,
                1
        );
    }

    @Test
    public void simpleConsensus() throws Exception {
        // Assemble
        ConcurrentLinkedQueue<AssertionError> failures = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Exception> errors = new ConcurrentLinkedQueue<>();

        CountDownLatch latch = new CountDownLatch(4);

        Transaction transaction = generateTransaction("hello.");
        Transaction transaction2 = generateTransaction("hello2.");

        aliceBroker.addClientRequest(transaction, transaction2);
        bobBroker.addClientRequest(transaction, transaction2);
        carlBroker.addClientRequest(transaction, transaction2);
        jeffBroker.addClientRequest(transaction, transaction2);

        Observer<ConsensusOutcomeDto> tester = outcome -> {
            try {
                assertTrue(outcome.decision().getTransactions().contains(transaction));
                assertTrue(outcome.decision().getTransactions().contains(transaction2));
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
        aliceBroker.start();
        bobBroker.start();
        carlBroker.start();
        jeffBroker.start();
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

        aliceBroker.addClientRequest(aliceTransaction, bobTransaction);
        bobBroker.addClientRequest(bobTransaction, carlTransaction);
        carlBroker.addClientRequest(carlTransaction, aliceTransaction);
        jeffBroker.addClientRequest(aliceTransaction, carlTransaction);

        Observer<ConsensusOutcomeDto> tester = outcome -> {
            try {
                assertTrue(outcome.decision().getTransactions().containsAll(List.of(
                        aliceTransaction, bobTransaction
                )));
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
        aliceBroker.start();
        bobBroker.start();
        carlBroker.start();
        jeffBroker.start();

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

        aliceBroker.addClientRequest(aliceTransaction, bobTransaction);
        bobBroker.addClientRequest(bobTransaction, carlTransaction);
        carlBroker.addClientRequest(carlTransaction, aliceTransaction);
        jeffBroker.addClientRequest(aliceTransaction, carlTransaction);

        Observer<ConsensusOutcomeDto> tester = outcome -> {
            try {
                assertTrue(outcome.decision().getTransactions().containsAll(List.of(
                        aliceTransaction, bobTransaction
                )));
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
        aliceBroker.start();
        bobBroker.start();
        carlBroker.start();
        jeffBroker.start();

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

        Transaction aliceTransaction = generateTransaction("hello.");
        Transaction bobTransaction = generateTransaction("hell-o");
        Transaction carlTransaction = generateTransaction("he-llo");

        jeffBroker.addClientRequest(aliceTransaction, carlTransaction);
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
        aliceBroker.start();
        bobBroker.start();
        carlBroker.start();
        jeffBroker.start();

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
    }

    @Test
    public void makingUpTransactionsTest() throws Exception {
        // Assemble
        ConcurrentLinkedQueue<AssertionError> failures = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Exception> errors = new ConcurrentLinkedQueue<>();

        CountDownLatch nullLatch = new CountDownLatch(3);

        Transaction aliceTransaction = generateTransaction("hello.");
        Transaction bobTransaction = generateTransaction("hell-o");
        Transaction carlTransaction = generateTransaction("he-llo");

        jeffBroker.addClientRequest(aliceTransaction, carlTransaction);
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

        // Alice is the byzantine, she'll make up transactions.
        aliceBroker.becomeByzantine(ConsensusByzantineMode.CLIENT_SPOOFING);

        // Act
        aliceBroker.start();
        bobBroker.start();
        carlBroker.start();
        jeffBroker.start();

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
    }

    @Test
    public void differentCollectedConsensus() throws Exception {
        // Assemble
        ConcurrentLinkedQueue<AssertionError> failures = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Exception> errors = new ConcurrentLinkedQueue<>();

        CountDownLatch nullLatch = new CountDownLatch(3);

        Transaction aliceTransaction = generateTransaction("hello.");
        Transaction bobTransaction = generateTransaction("hell-o");
        Transaction carlTransaction = generateTransaction("he-llo");

        jeffBroker.addClientRequest(aliceTransaction, carlTransaction);
        aliceBroker.addClientRequest(aliceTransaction, bobTransaction, carlTransaction);
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

        // Alice is the byzantine, she'll send different COLLECTED messages.
        aliceBroker.becomeByzantine(ConsensusByzantineMode.OMITTING_SOME);

        // Act
        aliceBroker.start();
        bobBroker.start();
        carlBroker.start();
        jeffBroker.start();

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
    }

    private Transaction generateTransaction(String content) throws Exception {
        String clientId = "deaddeaddeaddeaddeaddeaddeaddeaddeaddead";
        long nonce = ConsensusTest.nonce.incrementAndGet();
        String signature = SecurityUtil.signTransaction(clientId, clientId + 1, nonce, content, 0, clientKeyService.loadPrivateKey(clientId));
        return new Transaction(clientId, clientId + 1, nonce, content, 0, signature);
    }

    @AfterEach
    public void normalize() {
        for (ConsensusBroker broker: new ConsensusBroker[]{aliceBroker, bobBroker, carlBroker, jeffBroker}) {
            broker.returnToNormal();
            broker.resetEpoch();
            broker.clearClientQueue();
            broker.stop();
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
