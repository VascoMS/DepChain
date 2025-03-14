package server.consensus.core;

import ch.qos.logback.core.net.server.Client;
import com.google.gson.Gson;
import common.model.*;
import common.primitives.Link;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.app.ClientRequestBroker;
import server.consensus.core.model.StringState;
import server.consensus.core.model.WriteState;
import server.consensus.core.primitives.ConsensusBroker;
import util.KeyService;
import util.Observer;
import util.Process;
import util.SecurityUtil;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ClientRequestBrokerTest {

    private static Link aliceProcessLink;
    private static Link bobProcessLink;
    private static Link carlProcessLink;
    private static Link jeffProcessLink;
    private static final String privateKeyPrefix = "p";

    private static Link clientLink;
    private static Link aliceClientLink;

    private static ConsensusBroker aliceConsensusBroker;
    private static ConsensusBroker bobConsensusBroker;
    private static ConsensusBroker carlConsensusBroker;
    private static ConsensusBroker jeffConsensusBroker;

    private static StringState aliceState;
    private static StringState bobState;
    private static StringState carlState;
    private static StringState jeffState;

    private static ClientRequestBroker aliceClientBroker;

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

        aliceState = new StringState();
        bobState = new StringState();
        carlState = new StringState();
        jeffState = new StringState();

        aliceProcessLink = new Link(
                aliceProcess,
                new Process[]{bobProcess, carlProcess, jeffProcess},
                100, privateKeyPrefix, privateKeyPrefix, SecurityUtil.SERVER_KEYSTORE_PATH
        );

        bobProcessLink = new Link(
                bobProcess,
                new Process[]{aliceProcess, carlProcess, jeffProcess},
                100, privateKeyPrefix, privateKeyPrefix, SecurityUtil.SERVER_KEYSTORE_PATH
        );

        carlProcessLink = new Link(
                carlProcess,
                new Process[]{bobProcess, aliceProcess, jeffProcess},
                100, privateKeyPrefix, privateKeyPrefix, SecurityUtil.SERVER_KEYSTORE_PATH
        );

        jeffProcessLink = new Link(
                jeffProcess,
                new Process[]{bobProcess, carlProcess, aliceProcess},
                100, privateKeyPrefix, privateKeyPrefix, SecurityUtil.SERVER_KEYSTORE_PATH
        );

        aliceConsensusBroker = new ConsensusBroker(
                aliceProcess,
                new Process[]{bobProcess, carlProcess, jeffProcess},
                aliceProcessLink,
                1,
                serverKeyService,
                aliceState
        );

        bobConsensusBroker = new ConsensusBroker(
                bobProcess,
                new Process[]{aliceProcess, carlProcess, jeffProcess},
                bobProcessLink,
                1,
                serverKeyService,
                bobState
        );

        carlConsensusBroker = new ConsensusBroker(
                carlProcess,
                new Process[]{bobProcess, aliceProcess, jeffProcess},
                carlProcessLink,
                1,
                serverKeyService,
                carlState
        );

        jeffConsensusBroker = new ConsensusBroker(
                jeffProcess,
                new Process[]{bobProcess, carlProcess, aliceProcess},
                jeffProcessLink,
                1,
                serverKeyService,
                jeffState
        );

        Process clientProcess = new Process(1, "localhost", 8080);
        Process aliceClientProcess = new Process(0, "localhost", 1124);

        clientLink = new Link(
                clientProcess,
                new Process[]{aliceClientProcess},
                100, "c", "p", SecurityUtil.CLIENT_KEYSTORE_PATH
        );

        aliceClientLink = new Link(
                aliceClientProcess,
                new Process[]{clientProcess},
                100, "p", "c", SecurityUtil.SERVER_KEYSTORE_PATH
        );

        aliceClientBroker = new ClientRequestBroker(
                aliceProcess.getId(),
                aliceClientLink,
                aliceConsensusBroker,
                aliceState
        );
    }



    @Test
    public void sendClientRequest() throws Exception {

        // Assemble
        ConcurrentLinkedQueue<AssertionError> failures = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Exception> errors = new ConcurrentLinkedQueue<>();

        CountDownLatch latch1 = new CountDownLatch(1);
        CountDownLatch latch2 = new CountDownLatch(1);

        Transaction clientTransaction = generateTransaction("hello");
        Message clientAppendMessage = generateAppendRequest(1, 0, clientTransaction);
        Message clientReadMessage = generateReadRequest(1, 0);
        Observer<Message> clientAppendObserver = message -> {
            try {
                ServerResponse response = new Gson().fromJson(message.getPayload(), ServerResponse.class);
                assertTrue(response.success());
            } catch (Throwable e) {
                if (e instanceof AssertionError) {
                    failures.add((AssertionError) e);
                } else if (e instanceof Exception) {
                    errors.add((Exception) e);
                }
            } finally {
                latch1.countDown();
            }
        };

        Observer<Message> clientReadObserver = message -> {
            try {
                ServerResponse response = new Gson().fromJson(message.getPayload(), ServerResponse.class);
                assertTrue(response.success());
                assertEquals("hello", response.payload());
            } catch (Throwable e) {
                if (e instanceof AssertionError) {
                    failures.add((AssertionError) e);
                } else if (e instanceof Exception) {
                    errors.add((Exception) e);
                }
            } finally {
                latch2.countDown();
            }
        };

        clientLink.addObserver(clientAppendObserver);

        // Act
        clientLink.send(0, clientAppendMessage);
        latch1.await();

        clientLink.removeObserver(clientAppendObserver);
        if(!failures.isEmpty()) {
            throw failures.peek();
        }

        if(!errors.isEmpty()) {
            throw errors.peek();
        }

        clientLink.addObserver(clientReadObserver);
        clientLink.send(0, clientReadMessage);
        latch2.await();

        clientLink.removeObserver(clientReadObserver);
        if(!failures.isEmpty()) {
            throw failures.peek();
        }

        if(!errors.isEmpty()) {
            throw errors.peek();
        }
    }

    private Transaction generateTransaction(String content) throws Exception {
        String transactionId = UUID.randomUUID().toString();
        int clientId = 1;
        String signature = SecurityUtil.signTransaction(transactionId, clientId, content, clientKeyService.loadPrivateKey("c" + clientId));
        return new Transaction(transactionId, clientId, content, signature);
    }

    private Message generateAppendRequest(int senderId, int destinationId, Transaction append) {
        ClientRequest request = new ClientRequest(UUID.randomUUID().toString(), Command.APPEND, append);
        return generateRequestMessage(senderId, destinationId, request);
    }

    private Message generateReadRequest(int senderId, int destinationId) {
        ClientRequest request = new ClientRequest(UUID.randomUUID().toString(), Command.READ, null);
        return generateRequestMessage(senderId, destinationId, request);
    }

    private Message generateRequestMessage(int senderId, int destinationId, ClientRequest request) {
        return new Message(
                senderId,
                destinationId,
                Message.Type.REQUEST,
                new Gson().toJson(request)
        );
    }

    @AfterAll
    public static void closeLinks() {
        aliceProcessLink.close();
        bobProcessLink.close();
        carlProcessLink.close();
        jeffProcessLink.close();

        clientLink.close();
        aliceClientLink.close();
    }
}
