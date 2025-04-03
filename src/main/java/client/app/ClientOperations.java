package client.app;

import com.google.gson.Gson;
import common.model.*;
import common.primitives.AuthenticatedPerfectLink;
import common.primitives.LinkType;
import common.util.Addresses;
import lombok.Getter;
import server.consensus.exception.LinkException;
import util.*;
import util.Process;

import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import java.math.BigInteger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static client.app.TokenType.ISTCOIN;
import static client.app.TokenType.DEPCOIN;

public class ClientOperations implements Observer<Message>, AutoCloseable {

    private static final String KEYSTORE_PATH = SecurityUtil.CLIENT_KEYSTORE_PATH;
    private static final String KEYSTORE_PASS = "mypass";
    private final AuthenticatedPerfectLink link;
    private final String myId;
    private final KeyService keyService;
    private final Map<String, List<ServerResponse>> receivedResponses;
    private final Map<String, CompletableFuture<ServerResponse>> requestMap;
    private final String[] serverIds = {"p0", "p1", "p2", "p3"}; // Known servers
    private final int byzantineFailures = (serverIds.length - 1) / 3;
    private final String contractAddress;
    private final AtomicLong nonceCounter;
    private static final int TIMEOUT = 10000; // 10 seconds

    @Getter
    public enum Operations {

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
        this.nonceCounter = new AtomicLong(1);
        link.addObserver(this);
        link.start();
    }

    public Integer balance(TokenType tokenType) throws Exception {
        String id = UUID.randomUUID().toString();
        String calldata = tokenType.equals(ISTCOIN)
                ? Operations.BALANCE.callDataPrefix + padHexStringTo256Bit(myId)
                : null;
        String receiver = tokenType.equals(DEPCOIN) ? null : contractAddress;
        ClientRequest request = new ClientRequest(
                id,
                TransactionType.OFFCHAIN,
                createOffChainTransaction(
                        receiver,
                        calldata
                )
        );
        CompletableFuture<ServerResponse> future = sendToServers(request);
        ServerResponse result = future.get();
        if(result.success()) {
            System.out.println("Request successful!");
            return Integer.parseInt(result.payload());
        } else {
            System.out.println("Request fail.");
            return null;
        }
    }

    public boolean transfer(String recipientAddress, int value, TokenType tokenType) throws Exception {
        String id = UUID.randomUUID().toString();
        String calldata = tokenType.equals(ISTCOIN) ?
                Operations.TRANSFER.callDataPrefix +
                padHexStringTo256Bit(recipientAddress) +
                convertIntegerToHex256Bit(value)
                : null;
        String receiver = tokenType.equals(DEPCOIN) ? recipientAddress : contractAddress;
        int baseCurrencyValue = tokenType.equals(ISTCOIN) ? 0 : value;
        ClientRequest request = new ClientRequest(
                id,
                TransactionType.ONCHAIN,
                createOnChainTransaction(
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
            System.out.println("Transfer fail. Message: " + result.payload());
        }
        return result.success();
    }

    public boolean addToBlacklist(String address) throws Exception {
        String id = UUID.randomUUID().toString();
        ClientRequest request = new ClientRequest(
                id,
                TransactionType.ONCHAIN,
                createOnChainTransaction(
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
        return result.success();
    }

    public boolean removeFromBlacklist(String address) throws Exception {
        String id = UUID.randomUUID().toString();
        ClientRequest request = new ClientRequest(
                id,
                TransactionType.ONCHAIN,
                createOnChainTransaction(
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
        return result.success();
    }

    public ServerResponse sendRequest(ClientRequest clientRequest) throws Exception {
        CompletableFuture<ServerResponse> future = sendToServers(clientRequest);
        ServerResponse result = future.get();
        if(result.success()) {
            System.out.println("Success: " + result.payload());
        } else {
            System.out.println("Failed: " + result.payload());
        }
        return result;
    }

    public long getAndIncrementNonce() {
        return nonceCounter.getAndIncrement();
    }

    private CompletableFuture<ServerResponse> sendToServers(ClientRequest clientRequest) throws LinkException {

        CompletableFuture<ServerResponse> future = new CompletableFuture<ServerResponse>()
                .completeOnTimeout(ServerResponse.timeout(
                        clientRequest.id()), TIMEOUT, TimeUnit.MILLISECONDS
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

    private Transaction createOnChainTransaction(String receiver, String calldata, int value) throws Exception {
        long currentNonce = nonceCounter.getAndIncrement();
        PrivateKey privateKey = keyService.loadPrivateKey(myId);
        String signature = SecurityUtil.signTransaction(myId, receiver, currentNonce, calldata, value, privateKey);
        return new Transaction(myId, receiver, currentNonce, calldata, value, signature);
    }

    private Transaction createOffChainTransaction(String receiver, String calldata) throws Exception {
        PrivateKey privateKey = keyService.loadPrivateKey(myId);
        String signature = SecurityUtil.signTransaction(myId, receiver, -1, calldata, 0, privateKey);
        return new Transaction(myId, receiver, -1, calldata, 0, signature);
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



    @Override
    public void close() throws Exception {
        link.close();
    }
}
