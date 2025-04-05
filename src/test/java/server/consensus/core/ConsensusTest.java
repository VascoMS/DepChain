package server.consensus.core;

import common.model.Transaction;
import common.primitives.AuthenticatedPerfectLink;
import common.primitives.LinkType;
import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Base64;
import org.junit.jupiter.api.*;
import org.mockito.Mock;
import org.mockito.Mockito;
import server.app.Node;
import server.blockchain.Blockchain;
import server.blockchain.BlockchainImpl;
import server.blockchain.exception.BootstrapException;
import server.consensus.core.model.ConsensusOutcomeDto;
import server.consensus.core.primitives.ConsensusBroker;
import server.consensus.test.ConsensusByzantineMode;
import server.evm.core.ExecutionEngine;
import server.evm.model.TransactionResult;
import util.KeyService;
import util.Observer;
import util.Process;
import util.SecurityUtil;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConsensusTest {

    private static AuthenticatedPerfectLink aliceLink;
    private static AuthenticatedPerfectLink bobLink;
    private static AuthenticatedPerfectLink carlLink;
    private static AuthenticatedPerfectLink jeffLink;

    @Mock
    private static ExecutionEngine mockEngine;

    private static ConsensusBroker aliceBroker;
    private static ConsensusBroker bobBroker;
    private static ConsensusBroker carlBroker;
    private static ConsensusBroker jeffBroker;

    private static final AtomicLong nonce = new AtomicLong(0);

    private static KeyService clientKeyService;

    private static final int blockTime = 6000;

    @BeforeAll
    public static void startLinks() throws Exception {
        // Assemble
        Process aliceProcess = new Process("p0", "localhost", 1024);
        Process bobProcess = new Process("p1", "localhost", 1025);
        Process carlProcess = new Process("p2", "localhost", 1026);
        Process jeffProcess = new Process("p3", "localhost", 1027);

        KeyService serverKeyService = new KeyService(SecurityUtil.SERVER_KEYSTORE_PATH, "mypass");
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

        mockEngine = Mockito.mock(ExecutionEngine.class);
        Mockito.when(mockEngine.validateTransactionNonce(Mockito.any())).thenReturn(true);
        Mockito.when(mockEngine.getTransactionResult(Mockito.any(), Mockito.anyLong())).thenReturn(
                TransactionResult.success()
        );
        Mockito.when(mockEngine.performOffChainOperation(Mockito.any())).thenReturn(TransactionResult.success());

        File genesisBlockFile = new File(Node.GENESIS_BLOCK_PATH);
        String genesisBlock = Files.readString(genesisBlockFile.toPath());

        File tmpJsonPath = File.createTempFile("test", ".json");

        try (FileWriter writer = new FileWriter(tmpJsonPath)) {
            writer.write("[");
            writer.write(genesisBlock);
            writer.write("]");
        }

        tmpJsonPath.deleteOnExit();

        Blockchain aliceBlockchain = new BlockchainImpl(serverKeyService, mockEngine, 1, tmpJsonPath.getAbsolutePath());
        Blockchain bobBlockchain = new BlockchainImpl(serverKeyService, mockEngine, 1, tmpJsonPath.getAbsolutePath());
        Blockchain carlBlockchain = new BlockchainImpl(serverKeyService, mockEngine, 1, tmpJsonPath.getAbsolutePath());
        Blockchain jeffBlockchain = new BlockchainImpl(serverKeyService, mockEngine, 1, tmpJsonPath.getAbsolutePath());

        List.of(aliceBlockchain, bobBlockchain, carlBlockchain, jeffBlockchain).forEach (
                (blockchain -> {
                    try {
                        blockchain.bootstrap(Node.GENESIS_BLOCK_PATH);
                    } catch (BootstrapException e) {
                        throw new RuntimeException(e);
                    }
                })
        );

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

    @BeforeEach
    public void readyBrokers() {

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
        aliceBroker.addClientRequest(aliceTransaction, bobTransaction);
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
        MessageDigest digest = MessageDigest.getInstance("SHA256");
        String hashContent = Base64.getEncoder().encodeToString(digest.digest(content.getBytes()));
        long nonce = ConsensusTest.nonce.incrementAndGet();
        String signature = SecurityUtil.signTransaction(clientId, clientId + 1, nonce, hashContent, 0, clientKeyService.loadPrivateKey(clientId));
        return new Transaction(clientId, clientId + 1, nonce, hashContent, 0, signature);
    }

    @AfterEach
    public void normalize() throws InterruptedException {
        // Let all messages go through before normalizing.
        Thread.sleep(1000);
        for (ConsensusBroker broker: new ConsensusBroker[]{aliceBroker, bobBroker, carlBroker, jeffBroker}) {
            broker.returnToNormal();
            broker.resetEpoch();
            broker.resetConsensusId();
            broker.clearClientQueue();
            broker.clearActiveInstances();
            broker.clearMessageQueues();
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
