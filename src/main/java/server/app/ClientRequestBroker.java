package server.app;

import com.google.gson.Gson;
import common.model.*;
import common.primitives.AuthenticatedPerfectLink;
import common.primitives.LinkType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.evm.model.TransactionResult;
import util.*;
import util.Process;

import java.security.PublicKey;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ClientRequestBroker implements Observer<Message>, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ClientRequestBroker.class);
    private final String myId;
    private final AuthenticatedPerfectLink link;
    private final Node node;
    private final KeyService keyService;
    private final ExecutorService executor;

    public ClientRequestBroker(Process myProcess, Process[] clients, Node node, KeyService keyService) throws Exception {
        this.myId = myProcess.getId();
        this.link = new AuthenticatedPerfectLink(
                new Process(myProcess.getId(), myProcess.getHost(), myProcess.getPort() + 100),
                clients,
                LinkType.SERVER_TO_CLIENT, 100, SecurityUtil.SERVER_KEYSTORE_PATH);
        this.node = node;
        this.keyService = keyService;
        this.executor = Executors.newFixedThreadPool(5);
    }

    @Override
    public void update(Message message) {
        if(message.getType() != Message.Type.REQUEST_RESPONSE) return;
        ClientRequest clientRequest = new Gson().fromJson(message.getPayload(), ClientRequest.class);
        executor.execute(() -> handleRequest(message.getSenderId(), clientRequest));
    }

    @Override
    public void close() throws Exception {
        link.close();
        node.close();
    }

    public void start() {
        link.addObserver(this);
        link.start();
        node.start();
    }

    public void waitForTermination() {
        link.waitForTermination();
    }

    private void handleRequest(String senderId, ClientRequest clientRequest) {
        try {
            ServerResponse serverResponse;
            PublicKey senderPublicKey = keyService.loadPublicKey(senderId);
            if(senderPublicKey == null){
                serverResponse = new ServerResponse(clientRequest.id(), false, "Unkown sender.");
            }
            else if(clientRequest.command() == TransactionType.OFFCHAIN) {
                serverResponse = offChainTransaction(clientRequest.id(), clientRequest.transaction());
            } else {
                serverResponse = onChainTransaction(clientRequest.id(), clientRequest.transaction());
            }
            Message response = new Message(
                    myId,
                    senderId,
                    Message.Type.REQUEST_RESPONSE,
                    new Gson().toJson(serverResponse)
            );
            link.send(senderId, response);
        } catch(Exception e) {
            e.printStackTrace();
            logger.error("{}: Error in handling request from client: {}", myId, e.getMessage());
        }
    }
    
    private ServerResponse offChainTransaction(String clientReqId, Transaction transaction) {
        TransactionResult result = node.submitOffChainTransaction(transaction);
        return new ServerResponse(clientReqId, result.isSuccess(), result.message());
    }

    private ServerResponse onChainTransaction(String clientReqId, Transaction transaction) {
        try {
            TransactionResult result = node.submitOnChainTransaction(transaction);
            return new ServerResponse(clientReqId, result.isSuccess(), result.message());
        } catch (Exception e) {
            logger.error("{}: Error in handling request from client: {}", myId, e.getMessage(), e);
            return new ServerResponse(clientReqId, false, "Server Error");
        }
    }
}
