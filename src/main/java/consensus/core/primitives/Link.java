package consensus.core.primitives;

import com.google.gson.Gson;
import consensus.core.KeyService;
import consensus.core.model.Message;
import consensus.core.model.SignedMessage;
import consensus.exception.ErrorMessages;
import consensus.exception.LinkException;
import consensus.util.Observer;
import consensus.util.Process;
import consensus.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.security.PublicKey;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class Link implements AutoCloseable, Subject {
    private static final Logger logger = LoggerFactory.getLogger(Link.class);
    private final DatagramSocket socket;
    private final Process myProcess;
    private final Map<Integer, Process> peers;
    private final CollapsingSet acksList;
    private final AtomicInteger messageCounter;
    private final Queue<SignedMessage> localMessages;
    private final KeyService keyService;
    private final int baseSleepTime;
    private final List<Observer> observers;
    private boolean running;

    public Link(Process myProcess, Process[] peers, int baseSleepTime) throws Exception {
        this.myProcess = myProcess;
        this.peers = new HashMap<>();
        this.baseSleepTime = baseSleepTime;
        this.keyService = new KeyService(SecurityUtil.KEYSTORE_PATH, "mypass");
        this.observers = new ArrayList<>();
        for (Process p : peers) {
            this.peers.put(p.getId(), p);
        }
        try {
            this.socket = new DatagramSocket(myProcess.getPort(), InetAddress.getByName(myProcess.getHost()));
        } catch (Exception e) {
            throw new LinkException(ErrorMessages.LinkCreationError, e);
        }
        Thread receiverThread = new Thread(this::messageReceiver);
        this.running = true;
        receiverThread.start();
        this.acksList = new CollapsingSet();
        this.messageCounter = new AtomicInteger(0);
        this.localMessages = new ConcurrentLinkedQueue<>();
    }

    public void send(int nodeId, Message message) throws LinkException {
        if (socket.isClosed()) {
            throw new LinkException(ErrorMessages.LinkClosedException);
        }
        message.setMessageId(messageCounter.getAndIncrement());
        SignedMessage signedMessage;
        try {
            signedMessage = new SignedMessage(message, keyService.loadPrivateKey("p" + myProcess.getId()));

        } catch (Exception e) {
            throw new LinkException(ErrorMessages.SignatureError, e);
        }

        if (nodeId == myProcess.getId()) {
            localMessages.add(signedMessage);
            logger.info("P{}: Message {} {} added to local messages", myProcess.getId(), message.getType(), message.getMessageId());
            observers.forEach(observer -> observer.update(message));
            return;
        }

        Process node = peers.get(nodeId);
        if (node == null) {
            throw new LinkException(ErrorMessages.NoSuchNodeError);
        }

        try {
            InetAddress nodeHost = InetAddress.getByName(node.getHost());
            int nodePort = node.getPort();
            int sleepTime = baseSleepTime;
            for (int attempts = 1; !acksList.contains(message.getMessageId()); attempts++) {
                logger.info("P{}: Sending message {} {} to node P{} attempt {}",
                        myProcess.getId(), message.getType(), message.getMessageId(), nodeId, attempts);
                unreliableSend(nodeHost, nodePort, signedMessage);
                Thread.sleep(sleepTime);
                sleepTime *= 2;
            }
            logger.info("P{}: Message {} {} sent to node P{}", myProcess.getId(), message.getType(), message.getMessageId(), nodeId);
        } catch (Exception e) {
            logger.error(ErrorMessages.SendingError.getMessage(), e);
        }
    }

    private void unreliableSend(InetAddress host, int port, SignedMessage message) {
        try {
            // Sign the message to make sure link is authenticated
            byte[] bytes = new Gson().toJson(message).getBytes();
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length, host, port);
            socket.send(packet);
        } catch (Exception e) {
            logger.error(ErrorMessages.SendingError.getMessage(), e);
        }
    }

    private DatagramPacket listenOnSocket() throws IOException {
        byte[] buf = new byte[65535];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        socket.receive(packet);
        return packet;
    }

    private void handleAckMessage(SignedMessage message) {
        acksList.add(message.getMessageId());
        logger.info("P{}: ACK {} received from node P{}",
                myProcess.getId(), message.getMessageId(), message.getSenderId());
    }

    private void handleNonAckMessage(SignedMessage message, DatagramPacket packet) throws Exception {
        logger.info("P{}: Message {} {} received from node P{}.",
                myProcess.getId(), message.getType(), message.getMessageId(), message.getSenderId());

        // Send ACK back to the sender if not me
        if(message.getSenderId() != myProcess.getId() && packet != null) {
            InetAddress senderHost = packet.getAddress();
            int senderPort = packet.getPort();

            SignedMessage signedResponse = new SignedMessage(
                    myProcess.getId(), message.getSenderId(), message.getMessageId(),
                    Message.Type.ACK, keyService.loadPrivateKey("p" + myProcess.getId())
            );
            unreliableSend(senderHost, senderPort, signedResponse);
            logger.info("P{}: ACK {} sent to node P{}",
                    myProcess.getId(), message.getMessageId(), message.getSenderId());
        }

        // Notify observers of the received message
        notifyObservers(message);
    }

    private void messageReceiver() {
        logger.info("P{}: Started listening.", myProcess.getId());
        while (running && !socket.isClosed()) {
            try {

                DatagramPacket packet;
                SignedMessage message;

                if(!localMessages.isEmpty()) {
                    packet = null;
                    message = localMessages.poll();
                } else {
                    packet = listenOnSocket();
                    byte[] buffer = Arrays.copyOfRange(packet.getData(), 0, packet.getLength());
                    String json = new String(buffer);
                    message = new Gson().fromJson(json, SignedMessage.class);
                }
                PublicKey peerPublicKey = keyService.loadPublicKey("p" + message.getSenderId());
                boolean messageIsAuthentic = SecurityUtil.verifySignature(message, peerPublicKey);

                if (!messageIsAuthentic) {
                    logger.error("P{}: Message {} received from node P{} is not authentic.",
                            myProcess.getId(), message.getMessageId(), message.getSenderId());
                    continue;
                }

                if (message.getType() == Message.Type.ACK) {
                    handleAckMessage(message);
                } else {
                    handleNonAckMessage(message, packet);
                }
            } catch (Exception e) {
                if (running) {
                    logger.error("Error in message receiver: {}", e.getMessage(), e);
                }
            }
        }
    }

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
            observer.update(message);
        }
    }

    @Override
    public void close() {
        running = false;
        socket.close();
    }

}
