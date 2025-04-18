package app;

import client.app.ClientOperations;
import client.app.TokenType;
import common.model.ClientRequest;
import common.model.ServerResponse;
import common.model.Transaction;
import common.model.TransactionType;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.PrivateKey;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

import server.app.ClientRequestBroker;
import server.app.Node;
import server.consensus.test.ConsensusByzantineMode;
import util.KeyService;
import util.Process;
import util.SecurityUtil;

public class DepChainTest {

    private static Process alice;
    private static Process bob;

    private static ClientOperations aliceClient;
    private static ClientOperations bobClient;

    private static ClientRequestBroker server0;
    private static ClientRequestBroker server1;
    private static ClientRequestBroker server2;
    private static ClientRequestBroker server3;

    private static final int SERVER_BASE_PORT = 1024;
    private static final int CLIENT_BASE_PORT = 8080;
    private static final int BLOCK_TIME = 6000;

    private static final KeyService SERVER_KEY_SERVICE;
    private static final KeyService CLIENT_KEY_SERVICE;

    private final List<TestClientOp> clientOps = List.of(this::balanceAndSend, this::spoofSender, this::offChainAttempt);

    static {
        try {
            SERVER_KEY_SERVICE = new KeyService(SecurityUtil.SERVER_KEYSTORE_PATH, "mypass");
            CLIENT_KEY_SERVICE = new KeyService(SecurityUtil.CLIENT_KEYSTORE_PATH, "mypass");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeAll
    public static void startup() throws Exception {

        alice = new Process("deaddeaddeaddeaddeaddeaddeaddeaddeaddead", "localhost", CLIENT_BASE_PORT);
        bob = new Process("beefbeefbeefbeefbeefbeefbeefbeefbeefbeef", "localhost", CLIENT_BASE_PORT + 1);

        Process process0 = new Process("p0", "localhost", SERVER_BASE_PORT);
        Process process1 = new Process("p1", "localhost", SERVER_BASE_PORT + 1);
        Process process2 = new Process("p2", "localhost", SERVER_BASE_PORT + 2);
        Process process3 = new Process("p3", "localhost", SERVER_BASE_PORT + 3);

        Process clientProcess0 = new Process("p0", "localhost", SERVER_BASE_PORT + 100);
        Process clientProcess1 = new Process("p1", "localhost", SERVER_BASE_PORT + 101);
        Process clientProcess2 = new Process("p2", "localhost", SERVER_BASE_PORT + 102);
        Process clientProcess3 = new Process("p3", "localhost", SERVER_BASE_PORT + 103);

        Process[] processes = { process0, process1, process2, process3 };
        Process[] clientProcesses = { clientProcess0, clientProcess1, clientProcess2, clientProcess3 };
        Process[] clients = { alice, bob };

        Node node0 = new Node(SERVER_BASE_PORT, process0.getId(), processes, SERVER_KEY_SERVICE);
        Node node1 = new Node(SERVER_BASE_PORT, process1.getId(), processes, SERVER_KEY_SERVICE);
        Node node2 = new Node(SERVER_BASE_PORT, process2.getId(), processes, SERVER_KEY_SERVICE);
        Node node3 = new Node(SERVER_BASE_PORT, process3.getId(), processes, SERVER_KEY_SERVICE);

        node3.becomeByzantine(ConsensusByzantineMode.DROP_ALL);

        server0 = new ClientRequestBroker(process0, clients, node0, SERVER_KEY_SERVICE);
        server1 = new ClientRequestBroker(process1, clients, node1, SERVER_KEY_SERVICE);
        server2 = new ClientRequestBroker(process2, clients, node2, SERVER_KEY_SERVICE);
        server3 = new ClientRequestBroker(process3, clients, node3, SERVER_KEY_SERVICE);

        server0.start();
        server1.start();
        server2.start();
        server3.start();

        aliceClient = new ClientOperations(alice, clientProcesses);
        bobClient = new ClientOperations(bob, clientProcesses);
    }


    private ClientRequest createSpoofedRequest(String actualSenderId, String spoofedSenderId) throws Exception {
        PrivateKey privateKey = CLIENT_KEY_SERVICE.loadPrivateKey(actualSenderId);

        ClientOperations client = actualSenderId.equals(alice.getId()) ? aliceClient : bobClient;
        long currentNonce = client.getAndIncrementNonce();
        String signature = SecurityUtil.signTransaction(spoofedSenderId, actualSenderId, currentNonce, null, 1, privateKey);

        Transaction transaction = new Transaction(
                spoofedSenderId,
                actualSenderId,
                currentNonce,
                null,
                1,
                signature
        );

        return new ClientRequest(spoofedSenderId, TransactionType.ONCHAIN, transaction);
    }

    private ClientRequest createOffChainTransferRequest(String senderId, String recipientId) throws Exception {
        PrivateKey privateKey = CLIENT_KEY_SERVICE.loadPrivateKey(senderId);

        ClientOperations client = senderId.equals(alice.getId()) ? aliceClient : bobClient;
        long currentNonce = client.getAndIncrementNonce();
        String signature = SecurityUtil.signTransaction(
                senderId,
                recipientId,
                currentNonce,
                null,
                10,
                privateKey);

        Transaction transaction = new Transaction(
                senderId,
                recipientId,
                currentNonce,
                null,
                10,
                signature
        );

        return new ClientRequest(senderId, TransactionType.OFFCHAIN, transaction);
    }

    @Test
    public void spoofingAttempt() throws Exception {
        // Act - Alice spoofing as Bob
        ClientRequest spoofRequest = createSpoofedRequest(alice.getId(), bob.getId());
        ServerResponse response = aliceClient.sendRequest(spoofRequest);

        // Assert
        assertFalse(response.success());
    }

    @Test
    public void replayAttackAttempt() throws Exception {
        // Assemble
        KeyService clientKeyService = new KeyService(SecurityUtil.CLIENT_KEYSTORE_PATH,  "mypass");
        PrivateKey privateKey = clientKeyService.loadPrivateKey(alice.getId());

        long currentNonce = aliceClient.getAndIncrementNonce();
        String signature = SecurityUtil.signTransaction(alice.getId(), bob.getId(), currentNonce, null, 1, privateKey);

        Transaction depcoinTransferCall = new Transaction(
                alice.getId(),
                bob.getId(),
                currentNonce,
                null,
                1,
                signature
        );

        ClientRequest clientRequest = new ClientRequest(alice.getId(), TransactionType.ONCHAIN, depcoinTransferCall);

        // Act
        ServerResponse response = aliceClient.sendRequest(clientRequest);

        // Assert
        assertTrue(response.success());

        // Act (REPLAY)
        ServerResponse replayResponse = bobClient.sendRequest(clientRequest);

        // Assert
        assertFalse(replayResponse.success());
    }

    @Test
    public void offChainingAnOnChainTransaction() throws Exception {
        // Act - Alice trying to make a transfer using off-chain transaction type
        ClientRequest offChainRequest = createOffChainTransferRequest(alice.getId(), bob.getId());
        ServerResponse response = aliceClient.sendRequest(offChainRequest);

        // Assert
        assertFalse(response.success());
    }

    @Test
    public void manageBlacklistWhenNotOwner() throws Exception {
        // Request should not be successful.
        boolean success = bobClient.addToBlacklist(alice.getId());
        assertFalse(success);
    }

    @Test
    public void checkData() throws Exception {

        String name = aliceClient.getData(ClientOperations.Operations.NAME);
        assertNotNull(name);
        assertEquals("ISTCoin", name);

        String symbol = aliceClient.getData(ClientOperations.Operations.SYMBOL);
        assertNotNull(symbol);
        assertEquals("IST", symbol);

        String owner = aliceClient.getData(ClientOperations.Operations.OWNER);
        assertNotNull(owner);
        assertEquals(alice.getId(), owner);

        String decimals = bobClient.getData(ClientOperations.Operations.DECIMALS);
        assertNotNull(decimals);
        assertEquals("2", decimals);

        String totalSupply = bobClient.getData(ClientOperations.Operations.TOTAL_SUPPLY);
        assertNotNull(totalSupply);
        assertEquals("100000000", totalSupply);
    }

    @Test
    public void checkBalance() throws Exception {
        // Request should be successful.
        Integer istBalance = aliceClient.balance(TokenType.ISTCOIN);
        assertNotNull(istBalance);

        Integer depBalance = bobClient.balance(TokenType.DEPCOIN);
        assertNotNull(depBalance);
    }

    @Test
    public void checkAllowance() throws Exception {
        Integer allowance = aliceClient.allowance(bob.getId());
        assertNotNull(allowance);
    }

    @Test
    public void approveAndTransferFrom() throws Exception {
        // Approve
        assertTrue(aliceClient.approve(bob.getId(), 1));

        // Transfer from
        assertTrue(bobClient.transferFrom(alice.getId(), alice.getId(), 1));
    }

    @Test
    public void transferringFromWithoutApproval() throws Exception {
        // Not approve
        assertTrue(aliceClient.approve(bob.getId(), 0));

        // Transfer from
        assertFalse(bobClient.transferFrom(alice.getId(), alice.getId(), 10));
    }

    @Test
    public void transferToBobISTCoin() throws Exception {
        // Assemble
        int oldAliceBalance = aliceClient.balance(TokenType.ISTCOIN);
        int oldBobBalance = bobClient.balance(TokenType.ISTCOIN);
        int amountSent = 1;

        // Act
        assertTrue(aliceClient.transfer(bob.getId(), amountSent, TokenType.ISTCOIN));

        // Assert
        int newAliceBalance = aliceClient.balance(TokenType.ISTCOIN);
        int newBobBalance = bobClient.balance(TokenType.ISTCOIN);

        assertEquals(oldAliceBalance - amountSent, newAliceBalance);
        assertEquals(oldBobBalance + amountSent, newBobBalance);
    }

    @Test
    public void transferWithoutFunds() throws Exception {
        // Assemble
        // Act
        boolean result = bobClient.transfer(alice.getId(), 1000, TokenType.ISTCOIN);

        // Assert
        assertFalse(result);
    }

    @Test
    public void transferToBobDEPCoin() throws Exception {
        // Assemble
        int oldAliceBalance = aliceClient.balance(TokenType.DEPCOIN);
        int oldBobBalance = bobClient.balance(TokenType.DEPCOIN);
        int amountSent = 1;

        // Act
        aliceClient.transfer(bob.getId(), amountSent, TokenType.DEPCOIN);

        // Assert
        int newAliceBalance = aliceClient.balance(TokenType.DEPCOIN);
        int newBobBalance = bobClient.balance(TokenType.DEPCOIN);

        assertEquals(oldAliceBalance - amountSent, newAliceBalance);
        assertEquals(oldBobBalance + amountSent, newBobBalance);
    }

    @Test
    public void manageBlacklist() throws Exception {
        // Add to blacklist
        // Assemble
        assertTrue(aliceClient.transfer(bob.getId(), 1, TokenType.ISTCOIN));
        assertTrue(aliceClient.addToBlacklist(bob.getId()));
        assertTrue(bobClient.isBlacklisted());

        // Act
        boolean blacklistedTransferSuccess = bobClient.transfer(alice.getId(), 1, TokenType.ISTCOIN);

        // Assert
        assertFalse(blacklistedTransferSuccess);

        // Remove from blacklist
        // Assemble
        assertTrue(aliceClient.removeFromBlacklist(bob.getId()));
        assertFalse(bobClient.isBlacklisted());

        // Act
        boolean notBlacklistedTransferSuccess = bobClient.transfer(alice.getId(), 1, TokenType.ISTCOIN);

        // Assert
        assertTrue(notBlacklistedTransferSuccess);
    }

    @Test
    public void transferringAndRenouncingOwnership() throws Exception {
        // Transfer ownership
        assertTrue(aliceClient.transferOwnership(bob.getId()));

        // Check owner
        assertEquals(bob.getId(), aliceClient.getData(ClientOperations.Operations.OWNER));

        // Transfer it back
        assertTrue(bobClient.transferOwnership(alice.getId()));
    }

    @Test
    public void transferringWhenNotOwner() throws Exception {
        // Transfer ownership
        assertFalse(bobClient.transferOwnership(bob.getId()));
    }

    @Test
    public void renouncingOwnerWhenNotOwner() throws Exception {
        // Transfer ownership
        assertFalse(bobClient.renounceOwnership());
    }


    @Test
    public void oneMinuteStressTest() throws Exception {
        // Assemble
        ConcurrentLinkedQueue<AssertionError> failures = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Exception> errors = new ConcurrentLinkedQueue<>();
        ExecutorService aliceExecutor = Executors.newFixedThreadPool(5);
        ExecutorService bobExecutor = Executors.newFixedThreadPool(5);

        long testDuration = 60_000;
        long delay = 500;

        Thread aliceThread = new Thread(() -> clientLoop(aliceClient, aliceExecutor, bob.getId(), testDuration, delay, failures, errors));
        Thread bobThread = new Thread(() -> clientLoop(bobClient, bobExecutor, alice.getId(), testDuration, delay, failures, errors));

        aliceThread.start();
        bobThread.start();

        aliceThread.join();
        bobThread.join();

        if(!failures.isEmpty()) {
            throw failures.peek();
        }

        if(!errors.isEmpty()) {
            throw errors.peek();
        }
        aliceExecutor.shutdownNow();
        bobExecutor.shutdownNow();

        assertTrue(aliceExecutor.awaitTermination(10, TimeUnit.SECONDS));
        assertTrue(bobExecutor.awaitTermination(10, TimeUnit.SECONDS));

        String p0Json = new String(Files.readAllBytes(Paths.get(String.format(Node.BLOCKCHAIN_PATH, "p0"))));
        for(int i = 0; i < 3; i++) {
            String path = String.format(Node.BLOCKCHAIN_PATH, "p" + i);
            String json = new String(Files.readAllBytes(Paths.get(path)));
            assertEquals(p0Json, json);
        }

    }

    private void clientLoop(
            ClientOperations client,
            ExecutorService clientExecutor,
            String transferRecipient,
            long duration,
            long delay,
            ConcurrentLinkedQueue<AssertionError> failures,
            ConcurrentLinkedQueue<Exception> errors
    ) {
        try {
            long startTime = System.currentTimeMillis();
            while(duration > System.currentTimeMillis() - startTime) {
                TestClientOp op = randomOp();

                if(!failures.isEmpty())
                    break;
                if(!errors.isEmpty())
                    break;

                if(duration - (System.currentTimeMillis() - startTime) > BLOCK_TIME)
                    clientExecutor.submit(() -> op.doOperation(client, transferRecipient, failures, errors));

                // Wait until delay ends.
                Thread.sleep(delay);
            }
        } catch (Exception e) {
            // May be because test thread interrupted them, so silence them.
            if(!(e instanceof InterruptedException)) {
                errors.add(e);
            }
        }
    }

    private void balanceAndSend (
            ClientOperations client,
            String transferRecipient,
            ConcurrentLinkedQueue<AssertionError> failures,
            ConcurrentLinkedQueue<Exception> errors
    ) {
        try {
            // Balance
            TokenType tokenType = randomTokenType();
            Integer balance = client.balance(tokenType);
            assertNotNull(balance);
            assertTrue(balance > -1);
            // Transfer
            if(balance > 0) {
                int amountSent = new Random().nextInt(balance);
                client.transfer(transferRecipient, amountSent, tokenType);
            }
        } catch (AssertionError ae) {
            failures.add(ae);
        } catch (Exception e) {
            // May be because test thread interrupted them, so silence them.
            if(!(e instanceof InterruptedException)) {
                errors.add(e);
            }
        }
    }

    private void spoofSender(
            ClientOperations client,
            String transferRecipient,
            ConcurrentLinkedQueue<AssertionError> failures,
            ConcurrentLinkedQueue<Exception> errors
    ) {
        try {
            // Get the sender ID (the one who will be spoofed)
            String senderId = transferRecipient.equals(alice.getId()) ? bob.getId() : alice.getId();

            // Create spoofed request
            ClientRequest spoofClientRequest = createSpoofedRequest(senderId, transferRecipient);

            // Act
            ServerResponse response = client.sendRequest(spoofClientRequest);

            // Assert
            assertFalse(response.success());
        } catch (AssertionError ae) {
            failures.add(ae);
        } catch (Exception e) {
            // May be because test thread interrupted them, so silence them.
            if(!(e instanceof InterruptedException)) {
                errors.add(e);
            }
        }
    }

    private void offChainAttempt (
            ClientOperations client,
            String transferRecipient,
            ConcurrentLinkedQueue<AssertionError> failures,
            ConcurrentLinkedQueue<Exception> errors
    ) {
        try {
            // Get the client ID (who is sending)
            String clientId = transferRecipient.equals(alice.getId()) ? bob.getId() : alice.getId();

            // Create off-chain transfer request
            ClientRequest clientRequest = createOffChainTransferRequest(clientId, transferRecipient);

            // Act
            ServerResponse response = client.sendRequest(clientRequest);

            // Assert
            assertFalse(response.success());
        } catch (AssertionError ae) {
            failures.add(ae);
        } catch (Exception e) {
            // May be because test thread interrupted them, so silence them.
            if(!(e instanceof InterruptedException)) {
                errors.add(e);
            }
        }
    }

    private TestClientOp randomOp() {
        int ops = clientOps.size();
        int chosenOp = new Random().nextInt(ops);
        return clientOps.get(chosenOp);
    }

    private TokenType randomTokenType() {
        int tokens = TokenType.values().length;
        int chosenTokenValue = new Random().nextInt(tokens);
        return TokenType.values()[chosenTokenValue];
    }

    @AfterAll
    public static void cleanup() throws Exception {
        for(ClientRequestBroker server: new ClientRequestBroker[]{server0, server1, server2, server3}) {
            server.close();
            Files.delete(Paths.get(String.format(Node.BLOCKCHAIN_PATH, server.getMyId()))); // Delete blockchain file
        }
        for(ClientOperations client: new ClientOperations[]{aliceClient, bobClient}) {
            client.close();
        }
    }

}