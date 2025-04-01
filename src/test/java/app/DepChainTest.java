package app;

import client.app.ClientOperations;
import client.app.TokenType;
import common.model.ClientRequest;
import common.model.ServerResponse;
import common.model.Transaction;
import common.model.TransactionType;
import java.security.PrivateKey;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.app.ClientRequestBroker;
import server.app.Node;
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
    private static final int CLIENT_BASE_PORT = 924;
    private static final int BLOCK_TIME = 6000;

    private static final KeyService SERVER_KEY_SERVICE;

    static {
        try {
            SERVER_KEY_SERVICE = new KeyService(SecurityUtil.SERVER_KEYSTORE_PATH, "mypass");
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

        Process[] processes = { process0, process1, process2, process3 };
        Process[] clients = { alice, bob };

        Node node0 = new Node(SERVER_BASE_PORT, process0.getId(), processes, BLOCK_TIME, SERVER_KEY_SERVICE);
        Node node1 = new Node(SERVER_BASE_PORT, process1.getId(), processes, BLOCK_TIME, SERVER_KEY_SERVICE);
        Node node2 = new Node(SERVER_BASE_PORT, process2.getId(), processes, BLOCK_TIME, SERVER_KEY_SERVICE);
        Node node3 = new Node(SERVER_BASE_PORT, process3.getId(), processes, BLOCK_TIME, SERVER_KEY_SERVICE);

        server0 = new ClientRequestBroker(process0, clients, node0, SERVER_KEY_SERVICE);
        server1 = new ClientRequestBroker(process1, clients, node1, SERVER_KEY_SERVICE);
        server2 = new ClientRequestBroker(process2, clients, node2, SERVER_KEY_SERVICE);
        server3 = new ClientRequestBroker(process3, clients, node3, SERVER_KEY_SERVICE);

        aliceClient = new ClientOperations(alice, processes);
        bobClient = new ClientOperations(bob, processes);
    }

    @Test
    public void spoofingAttempt() throws Exception {
        // Assemble (alice spoofing as bob)
        KeyService clientKeyService = new KeyService(SecurityUtil.CLIENT_KEYSTORE_PATH,  "mypass");
        PrivateKey privateKey = clientKeyService.loadPrivateKey(alice.getId());

        String spoofedBobId = bob.getId();
        String transactionId = UUID.randomUUID().toString();
        String signature = SecurityUtil.signTransaction(transactionId, spoofedBobId, null, null, 0, privateKey);

        Transaction depcoinBalanceCall = new Transaction(
                UUID.randomUUID().toString(),
                bob.getId(),
                null,
                null,
                0,
                signature
        );

        ClientRequest spoofClientRequest = new ClientRequest(bob.getId(), TransactionType.OFFCHAIN, depcoinBalanceCall);

        // Act
        ServerResponse response = aliceClient.sendRequest(spoofClientRequest);

        // Assert
        assertFalse(response.success());
    }

    @Test
    public void replayAttackAttempt() throws Exception {
        // Assemble (alice spoofing as bob)
        KeyService clientKeyService = new KeyService(SecurityUtil.CLIENT_KEYSTORE_PATH,  "mypass");
        PrivateKey privateKey = clientKeyService.loadPrivateKey(alice.getId());

        String transactionId = UUID.randomUUID().toString();
        String signature = SecurityUtil.signTransaction(transactionId, alice.getId(), null, null, 0, privateKey);

        Transaction depcoinBalanceCall = new Transaction(
                UUID.randomUUID().toString(),
                alice.getId(),
                null,
                null,
                0,
                signature
        );

        ClientRequest clientRequest = new ClientRequest(alice.getId(), TransactionType.OFFCHAIN, depcoinBalanceCall);

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
        // Assemble (transfer off-chain)
        KeyService clientKeyService = new KeyService(SecurityUtil.CLIENT_KEYSTORE_PATH,  "mypass");
        PrivateKey privateKey = clientKeyService.loadPrivateKey(alice.getId());

        String transactionId = UUID.randomUUID().toString();
        String signature = SecurityUtil.signTransaction(
                transactionId,
                alice.getId(),
                bob.getId(),
                null,
                10,
                privateKey);

        Transaction depcoinBalanceCall = new Transaction(
                UUID.randomUUID().toString(),
                alice.getId(),
                bob.getId(),
                null,
                10,
                signature
        );

        ClientRequest spoofClientRequest = new ClientRequest(bob.getId(), TransactionType.OFFCHAIN, depcoinBalanceCall);

        // Act
        ServerResponse response = aliceClient.sendRequest(spoofClientRequest);

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
    public void checkBalance() throws Exception {
        // Request should be successful.
        int istBalance = aliceClient.balance(TokenType.ISTCOIN);
        assertTrue(istBalance != -1);

        int depBalance = bobClient.balance(TokenType.DEPCOIN);
        assertTrue(depBalance != -1);
    }

    @Test
    public void transferToBobISTCoin() throws Exception {
        // Assemble
        int oldAliceBalance = aliceClient.balance(TokenType.ISTCOIN);
        int oldBobBalance = bobClient.balance(TokenType.ISTCOIN);
        int amountSent = 1;

        // Act
        aliceClient.transfer(bob.getId(), amountSent, TokenType.ISTCOIN);

        // Assert
        int newAliceBalance = aliceClient.balance(TokenType.ISTCOIN);
        int newBobBalance = bobClient.balance(TokenType.ISTCOIN);

        assertEquals(oldAliceBalance - amountSent, newAliceBalance);
        assertEquals(oldBobBalance + amountSent, newBobBalance);
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

        // Act
        boolean blacklistedTransferSuccess = bobClient.transfer(alice.getId(), 1, TokenType.ISTCOIN);

        // Assert
        assertFalse(blacklistedTransferSuccess);

        // Remove from blacklist
        // Assemble
        assertTrue(aliceClient.removeFromBlacklist(bob.getId()));

        // Act
        boolean notBlacklistedTransferSuccess = bobClient.transfer(alice.getId(), 1, TokenType.ISTCOIN);

        // Assert
        assertTrue(notBlacklistedTransferSuccess);

    }

    @AfterAll
    public static void cleanup() throws Exception {
        for(ClientRequestBroker server: new ClientRequestBroker[]{server0, server1, server2, server3}) {
            server.close();
        }
        for(ClientOperations client: new ClientOperations[]{aliceClient, bobClient}) {
            client.close();
        }
    }

}
