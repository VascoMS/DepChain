package client.app;

import com.google.gson.Gson;
import common.model.*;
import common.primitives.AuthenticatedPerfectLink;
import common.primitives.LinkType;
import common.util.Addresses;
import server.consensus.exception.LinkException;
import util.KeyService;
import util.Observer;
import util.Process;
import util.SecurityUtil;

import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import java.math.BigInteger;
import java.util.concurrent.TimeUnit;

import static client.app.TokenType.ISTCOIN;
import static client.app.TokenType.DEPCOIN;

class ClientOperations implements Observer<Message> {

    private static final String KEYSTORE_PATH = "src/main/java/client/keys/keystore.p12";
    private static final String KEYSTORE_PASS = "mypass";
    private final AuthenticatedPerfectLink link;
    private final String myId;
    private final KeyService keyService;
    private final Map<String, List<ServerResponse>> receivedResponses;
    private final Map<String, CompletableFuture<ServerResponse>> requestMap;
    private final String[] serverIds = {"p0", "p1", "p2", "p3"}; // Known servers
    private final int byzantineFailures = (serverIds.length - 1) / 3;
    private final String contractAddress;

    private enum Operations {

        BALANCE("70a08231"),
        TRANSFER("23b872dd"),
        ADD_TO_BLACKLIST("44337ea1"),
        REMOVE_FROM_BLACKLIST("537df3b6");

        private final String callDataPrefix;

        Operations(String callData) { this.callDataPrefix = callData; }
    }

    public ClientOperations(Process myProcess, Process[] serverProcesses)  throws Exception {
        this.myId = myProcess.getId();
        this.link = new AuthenticatedPerfectLink(
                myProcess,
                serverProcesses,
                LinkType.CLIENT_TO_SERVER,
                100,
                KEYSTORE_PATH
        );
        this.keyService = new KeyService(KEYSTORE_PATH, KEYSTORE_PASS);
        this.receivedResponses = new ConcurrentHashMap<>();
        this.requestMap = new ConcurrentHashMap<>();
        this.contractAddress = Addresses.ISTCOIN_ADDRESS;
        link.addObserver(this);
    }

    public int balance(TokenType tokenType) throws Exception {
        String id = UUID.randomUUID().toString();
        String calldata = tokenType.equals(ISTCOIN)
                ? Operations.BALANCE.callDataPrefix + padHexStringTo256Bit(myId)
                : null;
        String receiver = tokenType.equals(DEPCOIN) ? null : contractAddress;
        ClientRequest request = new ClientRequest(
                id,
                TransactionType.OFFCHAIN,
                createTransaction(
                        receiver,
                        calldata,
                        0
                )
        );
        CompletableFuture<ServerResponse> future = sendToServers(request);
        ServerResponse result = future.get();
        if(result.success()) {
            System.out.println("Request successful!");
            return Integer.parseInt(result.payload());
        } else {
            System.out.println("Request fail.");
            return -1;
        }
    }

    public void transfer(String recipientAddress, int value, TokenType tokenType) throws Exception {
        String id = UUID.randomUUID().toString();
        String calldata = tokenType.equals(ISTCOIN) ?
                Operations.TRANSFER.callDataPrefix +
                padHexStringTo256Bit(recipientAddress) +
                convertIntegerToHex256Bit(value)
                : null;
        String receiver = tokenType.equals(DEPCOIN) ? recipientAddress : contractAddress;
        int baseCurrencyValue = tokenType.equals(DEPCOIN) ? 0 : value;
        ClientRequest request = new ClientRequest(
                id,
                TransactionType.ONCHAIN,
                createTransaction(
                        receiver,
                        calldata,
                        baseCurrencyValue
                )
        );
        CompletableFuture<ServerResponse> future = sendToServers(request);
        ServerResponse result = future.get();
        if(result.success()) {
            System.out.println("Transfer success!");
        } else {
            System.out.println("Transfer fail.");
        }
    }

    public void addToBlacklist(String address) throws Exception {
        String id = UUID.randomUUID().toString();
        ClientRequest request = new ClientRequest(
                id,
                TransactionType.ONCHAIN,
                createTransaction(
                        contractAddress,
                        Operations.ADD_TO_BLACKLIST.callDataPrefix +
                                padHexStringTo256Bit(address),
                        0
                )
        );
        CompletableFuture<ServerResponse> future = sendToServers(request);
        ServerResponse result = future.get();
        if(result.success()) {
            System.out.println("Address added to blacklist!");
        } else {
            System.out.println("Add to blacklist failed.");
        }
    }

    public void removeFromBlacklist(String address) throws Exception {
        String id = UUID.randomUUID().toString();
        ClientRequest request = new ClientRequest(
                id,
                TransactionType.ONCHAIN,
                createTransaction(
                        contractAddress,
                        Operations.REMOVE_FROM_BLACKLIST.callDataPrefix +
                                padHexStringTo256Bit(address),
                        0
                )
        );
        CompletableFuture<ServerResponse> future = sendToServers(request);
        ServerResponse result = future.get();
        if(result.success()) {
            System.out.println("Address removed from blacklist!");
        } else {
            System.out.println("Remove from blacklist failed.");
        }
    }

    private CompletableFuture<ServerResponse> sendToServers(ClientRequest clientRequest) throws LinkException {

        CompletableFuture<ServerResponse> future = new CompletableFuture<ServerResponse>()
                .completeOnTimeout(ServerResponse.timeout(
                        clientRequest.id()), 1000L, TimeUnit.MILLISECONDS
                );
        requestMap.put(clientRequest.id(), future);
        receivedResponses.put(clientRequest.id(), new ArrayList<>());
        for (String serverId : serverIds) {
            Message message = new Message(
                    myId,
                    serverId,
                    Message.Type.REQUEST_RESPONSE,
                    new Gson().toJson(clientRequest));
            link.send(serverId, message);
        }
        return future;
    }

    private Transaction createTransaction(String receiver, String calldata, int value) throws Exception {
        String id = UUID.randomUUID().toString();
        PrivateKey privateKey = keyService.loadPrivateKey(myId);
        String signature = SecurityUtil.signTransaction(id, myId, calldata, value, privateKey);
        return new Transaction(id, myId, receiver, calldata, value, signature); //
    }

    private static String convertIntegerToHex256Bit(int number) {
        BigInteger bigInt = BigInteger.valueOf(number);
        return String.format("%064x", bigInt);
    }

    public static String padHexStringTo256Bit(String hexString) {
        if (hexString.startsWith("0x")) {
            hexString = hexString.substring(2);
        }

        int length = hexString.length();
        int targetLength = 64;

        if (length >= targetLength) {
            return hexString.substring(0, targetLength);
        }

        return "0".repeat(targetLength - length) +
                hexString;
    }

    @Override
    public void update(Message message) {
        if(message.getType() != Message.Type.REQUEST_RESPONSE) return;
        ServerResponse response = new Gson().fromJson(message.getPayload(), ServerResponse.class);
        CompletableFuture<ServerResponse> future = requestMap.get(response.requestId());
        if(future == null) {
            System.out.println("No future found for message: " + response.requestId());
            return;
        }
        List<ServerResponse> responses = receivedResponses.get(response.requestId());
        if(responses == null) {
            System.out.println("Received response, but ignoring: " + response.requestId());
            return;
        }
        responses.add(response);
        if(responses.stream().filter(r -> r.equals(response)).count() > byzantineFailures) {
            future.complete(response);
        }
        if((long) responses.size() == serverIds.length) {
            future.complete(new ServerResponse(response.requestId(), false, null));
        }
    }
}
