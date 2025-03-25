package server.consensus.core;

public class ClientRequestBrokerTest {
/*
    private static Process aliceProcess;
    private static Process bobProcess;
    private static Process carlProcess;
    private static Process jeffProcess;

    private static AuthenticatedPerfectLink aliceProcessLink;
    private static AuthenticatedPerfectLink bobProcessLink;
    private static AuthenticatedPerfectLink carlProcessLink;
    private static AuthenticatedPerfectLink jeffProcessLink;
    private static final String privateKeyPrefix = "p";

    private static Blockchain aliceBlockchain;
    private static Blockchain bobBlockchain;
    private static Blockchain carlBlockchain;
    private static Blockchain jeffBlockchain;

    private static AuthenticatedPerfectLink clientLink;

    private static Process aliceClientProcess;
    private static AuthenticatedPerfectLink aliceClientLink;

    private static ConsensusBroker aliceConsensusBroker;
    private static ConsensusBroker bobConsensusBroker;
    private static ConsensusBroker carlConsensusBroker;
    private static ConsensusBroker jeffConsensusBroker;

    private static ClientRequestBroker aliceClientBroker;

    private static KeyService serverKeyService;
    private static KeyService clientKeyService;

    private static final int blockTime = 6000;


    @BeforeAll
    public static void startLinks() throws Exception {
        // Assemble
        aliceProcess = new Process(0, "localhost", 1024);
        bobProcess = new Process(1, "localhost", 1025);
        carlProcess = new Process(2, "localhost", 1026);
        jeffProcess = new Process(3, "localhost", 1027);

        serverKeyService = new KeyService(SecurityUtil.SERVER_KEYSTORE_PATH, "mypass");
        clientKeyService = new KeyService(SecurityUtil.CLIENT_KEYSTORE_PATH, "mypass");

        aliceProcessLink = new AuthenticatedPerfectLink(
                aliceProcess,
                new Process[]{bobProcess, carlProcess, jeffProcess}, LinkType.SERVER_TO_SERVER,
                100, privateKeyPrefix, privateKeyPrefix, SecurityUtil.SERVER_KEYSTORE_PATH
        );

        bobProcessLink = new AuthenticatedPerfectLink(
                bobProcess,
                new Process[]{aliceProcess, carlProcess, jeffProcess}, LinkType.SERVER_TO_SERVER,
                100, privateKeyPrefix, privateKeyPrefix, SecurityUtil.SERVER_KEYSTORE_PATH
        );

        carlProcessLink = new AuthenticatedPerfectLink(
                carlProcess,
                new Process[]{bobProcess, aliceProcess, jeffProcess}, LinkType.SERVER_TO_SERVER,
                100, privateKeyPrefix, privateKeyPrefix, SecurityUtil.SERVER_KEYSTORE_PATH
        );

        jeffProcessLink = new AuthenticatedPerfectLink(
                jeffProcess,
                new Process[]{bobProcess, carlProcess, aliceProcess}, LinkType.SERVER_TO_SERVER,
                100, privateKeyPrefix, privateKeyPrefix, SecurityUtil.SERVER_KEYSTORE_PATH
        );

        aliceBlockchain = new MockBlockchain(serverKeyService);
        bobBlockchain = new MockBlockchain(serverKeyService);
        carlBlockchain = new MockBlockchain(serverKeyService);
        jeffBlockchain = new MockBlockchain(serverKeyService);

        aliceConsensusBroker = new ConsensusBroker(
                aliceProcess,
                new Process[]{bobProcess, carlProcess, jeffProcess},
                aliceProcessLink,
                1,
                serverKeyService,
                aliceBlockchain,
                blockTime
        );

        bobConsensusBroker = new ConsensusBroker(
                bobProcess,
                new Process[]{aliceProcess, carlProcess, jeffProcess},
                bobProcessLink,
                1,
                serverKeyService,
                bobBlockchain,
                blockTime
        );

        carlConsensusBroker = new ConsensusBroker(
                carlProcess,
                new Process[]{bobProcess, aliceProcess, jeffProcess},
                carlProcessLink,
                1,
                serverKeyService,
                carlBlockchain,
                blockTime
        );

        jeffConsensusBroker = new ConsensusBroker(
                jeffProcess,
                new Process[]{bobProcess, carlProcess, aliceProcess},
                jeffProcessLink,
                1,
                serverKeyService,
                jeffBlockchain,
                blockTime
        );

        Process clientProcess = new Process(1, "localhost", 8080);
        Process aliceClientProcess = new Process(0, "localhost", 1124);

        clientLink = new AuthenticatedPerfectLink(
                clientProcess,
                new Process[]{aliceClientProcess}, LinkType.CLIENT_TO_SERVER,
                100, "c", "p", SecurityUtil.CLIENT_KEYSTORE_PATH
        );

        aliceClientLink = new AuthenticatedPerfectLink(
                aliceClientProcess,
                new Process[]{clientProcess}, LinkType.SERVER_TO_CLIENT,
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
        return new Transaction(transactionId, clientId, clientId + 1, content, signature);
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
*/
}
