package server.app;

import com.google.gson.Gson;
import common.model.*;
import common.primitives.AuthenticatedPerfectLink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.consensus.exception.LinkException;
import server.consensus.exception.TransactionExecutionException;
import server.evm.model.TransactionResult;
import util.Observer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ClientRequestBroker implements Observer<Message> {

    private static final Logger logger = LoggerFactory.getLogger(ClientRequestBroker.class);
    private final String myId;
    private final AuthenticatedPerfectLink link;
    private final Node node;
    private final ExecutorService executor;

    public ClientRequestBroker(String myId, AuthenticatedPerfectLink link, Node node) {
        this.myId = myId;
        this.link = link;
        this.node = node;
        this.executor = Executors.newFixedThreadPool(5);
    }

    @Override
    public void update(Message message) {
        if(message.getType() != Message.Type.REQUEST) return;
        ClientRequest clientRequest = new Gson().fromJson(message.getPayload(), ClientRequest.class);
        executor.execute(() -> handleRequest(message.getSenderId(), clientRequest));
    }

    public void start(String genesisFilePath) {
        link.addObserver(this);
        link.start();
        node.start(genesisFilePath);
        link.waitForTermination();
    }

    private void handleRequest(String senderId, ClientRequest clientRequest) {
        ServerResponse serverResponse;
        if(clientRequest.command() == Command.BALANCE) {
            serverResponse = balance(clientRequest.id(), clientRequest.transaction());
        } else {
            serverResponse = onChainTransaction(clientRequest.id(), clientRequest.transaction());
        }
        Message response = new Message(
                myId,
                senderId,
                Message.Type.REQUEST,
                new Gson().toJson(serverResponse)
        );
        try {
            link.send(senderId, response);
        } catch(LinkException e) {
            logger.error("P{}: Error in handling request from client: {}", myId, e.getMessage());
        }
    }
    
    private ServerResponse balance(String clientReqId, Transaction transaction) {
        TransactionResult result = node.submitOffChainTransaction(transaction);
        return new ServerResponse(clientReqId, result.isSuccess(), result.message());
    }

    private ServerResponse onChainTransaction(String clientReqId, Transaction transaction) {
        try {
            TransactionResult result = node.submitOnChainTransaction(transaction);
            return new ServerResponse(clientReqId, result.isSuccess(), result.message());
        } catch (Exception e) {
            logger.error("P{}: Error in handling request from client: {}", myId, e.getMessage());
            return new ServerResponse(clientReqId, false, "Server Error");
        }
    }
}
