package common.primitives;

import common.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.consensus.exception.ErrorMessages;
import server.consensus.exception.LinkException;
import util.CollapsingSet;
import util.Observer;
import util.Process;
import util.Subject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class StubbornLink implements AutoCloseable, Subject<Message>, Observer<Message> {
    private static final Logger logger = LoggerFactory.getLogger(StubbornLink.class);
    private final Process myProcess;
    private final FairLossLink flsLink;
    private final CollapsingSet acksList;
    private final int baseSleepTime;
    private final List<Observer<Message>> observers;

    public StubbornLink(Process myProcess, Process[] peers, LinkType type, int baseSleepTime) throws Exception {
        this.myProcess = myProcess;
        this.flsLink = new FairLossLink(myProcess, peers, type);
        this.baseSleepTime = baseSleepTime;
        this.acksList = new CollapsingSet();
        this.observers = new ArrayList<>();
        flsLink.addObserver(this);
    }

    public void start() {
        flsLink.start();
    }

    public void send(String nodeId, Message message) throws LinkException {
        if (flsLink.isClosed()) {
            throw new LinkException(ErrorMessages.LinkClosedException);
        }
        if (!flsLink.getPeers().containsKey(nodeId) && !nodeId.equals(myProcess.getId())) {
            throw new LinkException(ErrorMessages.NoSuchNodeError);
        }
        if(nodeId.equals(myProcess.getId())) {
            flsLink.send(nodeId, message);
        } else {
            int sleepTime = baseSleepTime;
            try {
                for (int attempts = 1; !acksList.contains(message.getMessageId()); attempts++) {
                    logger.info("P{}: Sending message {} to node P{} attempt {}",
                            myProcess.getId(), message.getMessageId(), nodeId, attempts);

                    flsLink.send(nodeId, message);

                    Thread.sleep(sleepTime);
                    sleepTime *= 2;  // Exponential backoff
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        logger.info("P{}: Message {} sent to P{}", myProcess.getId(), message.getMessageId(), nodeId);
    }

    protected void handleAckMessage(Message message) {
        acksList.add(message.getMessageId());
        logger.info("P{}: ACK {} received from node P{}",
                myProcess.getId(), message.getMessageId(), message.getSenderId());
    }

    protected void sendAck(String senderId, int messageId) throws LinkException {
        Message ackMessage = new Message(myProcess.getId(), senderId, Message.Type.ACK, "");
        ackMessage.setMessageId(messageId);
        flsLink.send(senderId, ackMessage);
        logger.info("P{}: ACK {} sent to node P{}", myProcess.getId(), messageId, senderId);
    }

    protected Map<String, Process> getPeers() {
        return flsLink.getPeers();
    }

    public void waitForTermination() {
        flsLink.waitForTermination();
    }

    public boolean isClosed() { return flsLink.isClosed(); }

    @Override
    public void addObserver(Observer observer) {
        observers.add(observer);
    }

    @Override
    public void removeObserver(Observer observer) {
        observers.remove(observer);
    }

    @Override
    public void notifyObservers(Message message) {
        for (Observer observer : observers) {
            logger.info("P{}: Notifying observer of message {} {}",
                    myProcess.getId(), message.getType(), message.getMessageId());
            observer.update(message);
        }
    }

    @Override
    public void close() {
        flsLink.close();
    }

    @Override
    public void update(Message message) {
        try {
            if(message.getType() == Message.Type.ACK) {
                handleAckMessage(message);
            } else {
                if(!message.getSenderId().equals(myProcess.getId()))
                    sendAck(message.getSenderId(), message.getMessageId());
                notifyObservers(message);
            }
        } catch (LinkException e) {
            logger.error("P{}: Error in receiving message: {}", myProcess.getId(), e.getMessage(), e);
        }
    }
}