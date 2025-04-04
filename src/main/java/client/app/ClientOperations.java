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
import java.util.concurrent.ExecutionException;
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

        // Obtaining data operations
        NAME("06fdde03"),
        SYMBOL("95d89b41"),
        DECIMALS("313ce567"),
        OWNER("8da5cb5b"),
        TOTAL_SUPPLY("18160ddd"),

        // Financial operations
        BALANCE("70a08231"),
        ALLOWANCE("dd62ed3e"),
        TRANSFER("a9059cbb"),
        TRANSFER_FROM("23b872dd"),
        APPROVE("095ea7b3"),

        // Blacklist operations
        ADD_TO_BLACKLIST("44337ea1"),
        REMOVE_FROM_BLACKLIST("537df3b6"),
        IS_BLACKLISTED("fe575a87"),

        // Ownership operations
        RENOUNCE_OWNERSHIP("715018a6"),
        TRANSFER_OWNERSHIP("f2fde38b");

        private final String callDataPrefix;

        Operations(String callData) { this.callDataPrefix = callData; }

        public boolean isDataOperation() {
            List<Operations> dataOperations = List.of(
                    NAME, SYMBOL, DECIMALS, OWNER, TOTAL_SUPPLY
            );
            return dataOperations.contains(this);
        }
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

    public String getData(Operations dataType) throws Exception {
        if(!dataType.isDataOperation()) {
            System.out.println("Invalid data type.");
            return null;
        }
        String id = UUID.randomUUID().toString();
        String calldata = dataType.callDataPrefix;

        ClientRequest request = new ClientRequest(
                id,
                TransactionType.OFFCHAIN,
                createOffChainTransaction(
                        contractAddress,
                        calldata
                )
        );

        ServerResponse response = sendToServers(request);

        if(response.success()) {
            System.out.println("Request successful!");
            return response.payload();
        } else {
            System.out.println("Request fail.");
            return null;
        }
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
        ServerResponse response = sendToServers(request);

        if(response.success()) {
            System.out.println("Request successful!");
            return Integer.parseInt(response.payload());
        } else {
            System.out.println("Request fail.");
            return null;
        }
    }

    public Integer allowance(String address) throws Exception {
        String id = UUID.randomUUID().toString();
        String calldata = Operations.ALLOWANCE.callDataPrefix +
                padHexStringTo256Bit(myId) +
                padHexStringTo256Bit(address) ;
        ClientRequest request = new ClientRequest(
                id,
                TransactionType.OFFCHAIN,
                createOffChainTransaction(
                        contractAddress,
                        calldata
                )
        );
        ServerResponse response = sendToServers(request);

        if(response.success()) {
            System.out.println("Request successful!");
            return Integer.parseInt(response.payload());
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
        ServerResponse response = sendToServers(request);
        if(response.success()) {
            System.out.println("Transfer success!");
        } else {
            System.out.println("Transfer fail. Message: " + response.payload());
        }
        return response.success();
    }

    public boolean transferFrom(String senderAddress, String recipientAddress, int value) throws Exception {
        String id = UUID.randomUUID().toString();
        String calldata = Operations.TRANSFER_FROM.callDataPrefix +
                        padHexStringTo256Bit(senderAddress) +
                        padHexStringTo256Bit(recipientAddress) +
                        convertIntegerToHex256Bit(value);
        ClientRequest request = new ClientRequest(
                id,
                TransactionType.ONCHAIN,
                createOnChainTransaction(
                        contractAddress,
                        calldata,
                        0
                )
        );
        ServerResponse response = sendToServers(request);
        if(response.success()) {
            System.out.println("Transfer success!");
        } else {
            System.out.println("Transfer fail. Message: " + response.payload());
        }
        return response.success();
    }

    public boolean approve(String recipientAddress, int value) throws Exception {
        String id = UUID.randomUUID().toString();
        String calldata = Operations.APPROVE.callDataPrefix +
                        padHexStringTo256Bit(recipientAddress) +
                        convertIntegerToHex256Bit(value);
        ClientRequest request = new ClientRequest(
                id,
                TransactionType.ONCHAIN,
                createOnChainTransaction(
                        contractAddress,
                        calldata,
                        0
                )
        );
        ServerResponse response = sendToServers(request);
        if(response.success()) {
            System.out.println("Approval success!");
        } else {
            System.out.println("Approval fail. Message: " + response.payload());
        }
        return response.success();
    }

    public Boolean isBlacklisted() throws Exception {
        String id = UUID.randomUUID().toString();
        ClientRequest request = new ClientRequest(
                id,
                TransactionType.OFFCHAIN,
                createOffChainTransaction(
                        contractAddress,
                        Operations.IS_BLACKLISTED.callDataPrefix +
                                padHexStringTo256Bit(myId)
                )
        );
        ServerResponse response = sendToServers(request);
        if(response.success()) {
            System.out.println("Address added to blacklist!");
            return Boolean.valueOf(response.payload());
        } else {
            System.out.println("Add to blacklist failed.");
            return null;
        }
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
        ServerResponse response = sendToServers(request);
        if(response.success()) {
            System.out.println("Address added to blacklist!");
        } else {
            System.out.println("Add to blacklist failed.");
        }
        return response.success();
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
        ServerResponse response = sendToServers(request);

        if(response.success()) {
            System.out.println("Address removed from blacklist!");
        } else {
            System.out.println("Remove from blacklist failed.");
        }
        return response.success();
    }

    public boolean renounceOwnership() throws Exception {
        String id = UUID.randomUUID().toString();
        String calldata = Operations.RENOUNCE_OWNERSHIP.callDataPrefix;
        ClientRequest request = new ClientRequest(
                id,
                TransactionType.ONCHAIN,
                createOnChainTransaction(
                        contractAddress,
                        calldata,
                        0
                )
        );
        ServerResponse response = sendToServers(request);
        if(response.success()) {
            System.out.println("Ownership transfer success!");
        } else {
            System.out.println("Ownership transfer fail. Message: " + response.payload());
        }
        return response.success();
    }

    public boolean transferOwnership(String newOwnerAddress) throws Exception {
        String id = UUID.randomUUID().toString();
        String calldata = Operations.TRANSFER_OWNERSHIP.callDataPrefix +
                padHexStringTo256Bit(newOwnerAddress);
        ClientRequest request = new ClientRequest(
                id,
                TransactionType.ONCHAIN,
                createOnChainTransaction(
                        contractAddress,
                        calldata,
                        0
                )
        );
        ServerResponse response = sendToServers(request);
        if(response.success()) {
            System.out.println("Ownership transfer success!");
        } else {
            System.out.println("Ownership transfer fail. Message: " + response.payload());
        }
        return response.success();
    }

    public ServerResponse sendRequest(ClientRequest clientRequest) throws Exception {
        ServerResponse response = sendToServers(clientRequest);
        if(response.success()) {
            System.out.println("Success: " + response.payload());
        } else {
            System.out.println("Failed: " + response.payload());
        }
        return response;
    }

    public long getAndIncrementNonce() {
        return nonceCounter.getAndIncrement();
    }

    private ServerResponse sendToServers(ClientRequest clientRequest) throws Exception {
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
        ServerResponse response = future.get();
        requestMap.remove(clientRequest.id());
        receivedResponses.remove(clientRequest.id());
        return response;
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
        else if((long) responses.size() == serverIds.length) {
            future.complete(new ServerResponse(response.requestId(), false, null));
        }
    }



    @Override
    public void close() throws Exception {
        link.close();
    }
}
