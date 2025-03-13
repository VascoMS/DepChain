package server.app;

import com.google.gson.Gson;
import common.model.ClientRequest;
import common.model.ServerResponse;
import common.model.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.consensus.core.model.State;
import server.consensus.core.primitives.ConsensusBroker;
import server.consensus.exception.LinkException;
import util.Observer;
import common.model.Message;
import common.primitives.Link;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

public class ClientRequestBroker implements Observer<Message> {

    private static final Logger logger = LoggerFactory.getLogger(ClientRequestBroker.class);
    private final int myId;
    private final Link link;
    private final ConsensusBroker consensusBroker;
    private final State state;
    
    public ClientRequestBroker(int myId, Link link, ConsensusBroker consensusBroker, State state) {
        this.myId = myId;
        this.link = link;
        this.consensusBroker = consensusBroker;
        this.state = state;
    }
    
    @Override
    public void update(Message message) {
        if(message.getType() != Message.Type.REQUEST) return;
        ClientRequest clientRequest = new Gson().fromJson(message.getPayload(), ClientRequest.class);
        handleRequest(message.getSenderId(), clientRequest);
    }

    private void handleRequest(int senderId, ClientRequest clientRequest) {
        ServerResponse serverResponse;
        switch(clientRequest.command()) {
            case READ -> serverResponse = read(clientRequest.id());
            case APPEND -> serverResponse = append(clientRequest.transaction(), clientRequest.id());
            default -> serverResponse =
                    new ServerResponse(
                            clientRequest.id(),
                            false,
                            "Not supporting " + clientRequest.command()
                    );
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
            logger.error("P{}: Error in responding to client: {}", myId, e.getMessage());
        }
    }
    
    private ServerResponse read(String clientReqId) {
        String currentString = state.getCurrentState();
        return new ServerResponse(clientReqId, true, currentString);
    }

    private ServerResponse append(Transaction appendTransaction, String clientReqId) {
        try {
            consensusBroker.addClientRequest(appendTransaction);
            if(consensusBroker.iAmLeader()) {
                consensusBroker.startConsensus().get();
                logger.info("P{}: Leader, request now proposed for consensus", myId);
            } else {
                logger.info("P{}: Not leader, request stored for consensus", myId);
            }
            consensusBroker.waitForTransaction(appendTransaction.id());
            return new ServerResponse(clientReqId, true, null);
        } catch(Exception e) {
               logger.error("P{}: Error in append: {}", myId, e.getMessage());
               return new ServerResponse(clientReqId, false, "Error: " + e.getMessage());
        }
    }
    
}
