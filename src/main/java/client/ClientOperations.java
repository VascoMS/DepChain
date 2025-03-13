package client;

import com.google.gson.Gson;
import common.model.*;
import common.primitives.Link;
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

class ClientOperations implements Observer<Message> {

    private static final String KEYSTORE_PATH = "src/main/java/client/keys/keystore.p12";
    private static final String KEYSTORE_PASS = "mypass";
    private final Link link;
    private final int myId;
    private final KeyService keyService;
    private final Map<String, List<ServerResponse>> receivedResponses;
    private final Map<String, CompletableFuture<ServerResponse>> requestMap;
    private final int[] serverIds = {0, 1, 2, 3}; // Known servers
    private final int byzantineFailures = (serverIds.length - 1) / 3;

    public ClientOperations(Process myProcess, Process[] serverProcesses)  throws Exception {
        this.myId = myProcess.getId();
        this.link = new Link(myProcess, serverProcesses, 100, "c", "p", KEYSTORE_PATH);
        this.keyService = new KeyService(KEYSTORE_PATH, KEYSTORE_PASS);
        this.receivedResponses = new ConcurrentHashMap<>();
        this.requestMap = new ConcurrentHashMap<>();
    }

    public void append(String value) throws Exception {
        String id = UUID.randomUUID().toString();
        ClientRequest request = new ClientRequest(id, Command.APPEND, createTransaction(value));
        System.out.println("Appending value: " + value);
        CompletableFuture<ServerResponse> future = sendToServers(request);
        ServerResponse result = future.get();
        if(result.success()) {
            System.out.println("Append successful!");
        } else {
            System.out.println("Append failed.");
        }
    }

    public String read() throws Exception {
        String id = UUID.randomUUID().toString();
        ClientRequest request = new ClientRequest(id, Command.READ, null);
        System.out.println("Reading from servers...");
        try {
            CompletableFuture<ServerResponse> future = sendToServers(request);
            ServerResponse result = future.get();
            if(result != null && result.success()) {
                System.out.println("Read successful!");
                return result.payload();
            } else {
                System.out.println("Read failed.");
                return null;
            }
        } catch (LinkException e) {
            System.out.println("Error in sending request to servers: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private CompletableFuture<ServerResponse> sendToServers(ClientRequest clientRequest) throws LinkException{
        CompletableFuture<ServerResponse> future = new CompletableFuture<>();
        requestMap.put(clientRequest.id(), future);
        receivedResponses.put(clientRequest.id(), new ArrayList<>());
        for (int serverId : serverIds) {
            Message message = new Message(
                    myId,
                    serverId,
                    Message.Type.REQUEST,
                    new Gson().toJson(clientRequest));
            link.send(serverId, message);
        }
        return future;
    }

    private Transaction createTransaction(String value) throws Exception {
        String id = UUID.randomUUID().toString();
        PrivateKey privateKey = keyService.loadPrivateKey("c" + myId);
        String signature = SecurityUtil.signTransaction(id, myId, value, privateKey);
        return new Transaction(id, myId, value, signature);
    }

    @Override
    public void update(Message message) {
        if(message.getType() != Message.Type.REQUEST) return;
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
        if(responses.stream().filter(r -> r == response).count() > byzantineFailures) {
            future.complete(response);
        }
        if((long) responses.size() == serverIds.length) {
            future.complete(new ServerResponse(response.requestId(), false, null));
        }
    }
}
