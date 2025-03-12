package common.primitives;

import com.google.gson.Gson;
import common.model.DeliveryKey;
import util.KeyService;
import common.model.Message;
import common.model.SignedMessage;
import server.consensus.exception.ErrorMessages;
import server.consensus.exception.LinkException;
import util.Observer;
import util.Process;
import util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.security.PublicKey;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class Link implements AutoCloseable, Subject<Message> {
    private static final Logger logger = LoggerFactory.getLogger(Link.class);
    private final DatagramSocket processSocket;
    private final Process myProcess;
    private final Map<Integer, Process> peers;
    private final Map<DeliveryKey, CollapsingSet> deliveredMessages = new HashMap<>();
    private final CollapsingSet acksList;
    private final AtomicInteger messageCounter;
    private final BlockingQueue<SignedMessage> messageQueue;
    private final KeyService keyService;
    private final String privateKeyPrefix;
    private final String publicKeyPrefix;
    private final int baseSleepTime;
    private final List<Observer<Message>> observers;
    private final Thread receiverThread;
    private boolean running;

    public Link(Process myProcess, Process[] peers, int baseSleepTime, String privateKeyPrefix, String publicKeyPrefix) throws Exception {
        this(myProcess, baseSleepTime, privateKeyPrefix, publicKeyPrefix);

        // Add peers if provided
        if (peers != null) {
            for (Process p : peers) {
                this.peers.put(p.getId(), p);
            }
        }
    }

    public Link(Process myProcess, int baseSleepTime, String privateKeyPrefix, String publicKeyPrefix) throws Exception {
        this.myProcess = myProcess;
        this.peers = new HashMap<>();
        this.baseSleepTime = baseSleepTime;
        this.keyService = new KeyService(SecurityUtil.SERVER_KEYSTORE_PATH, "mypass");
        this.privateKeyPrefix = privateKeyPrefix;
        this.publicKeyPrefix = publicKeyPrefix;
        this.observers = new ArrayList<>();

        try {
            this.processSocket = new DatagramSocket(myProcess.getPort(), InetAddress.getByName(myProcess.getHost()));
        } catch (Exception e) {
            throw new LinkException(ErrorMessages.LinkCreationError, e);
        }

        this.messageQueue = new LinkedBlockingQueue<>();
        this.acksList = new CollapsingSet();
        this.messageCounter = new AtomicInteger(0);

        receiverThread = new Thread(this::messageReceiver);
        Thread socketThread = new Thread(this::socketReceiver);
        receiverThread.start();
        socketThread.start();
        this.running = true;
    }

    public void send(int nodeId, Message message) throws LinkException {
        if (processSocket.isClosed()) {
            throw new LinkException(ErrorMessages.LinkClosedException);
        }

        SignedMessage signedMessage = prepareMessage(message);

        if (nodeId == myProcess.getId() && message.getType() != Message.Type.REQUEST) {
            messageQueue.add(signedMessage);
            logger.info("P{}: Message {} {} added to local messages", myProcess.getId(), message.getType(), message.getMessageId());
            return;
        }

        Process node = peers.get(nodeId);
        if (node == null) {
            throw new LinkException(ErrorMessages.NoSuchNodeError);
        }

        try {
            InetAddress nodeHost = InetAddress.getByName(node.getHost());
            int nodePort = node.getPort();
            sendWithRetry(message, signedMessage, nodeHost, nodePort, "node P" + nodeId);
        } catch (Exception e) {
            logger.error(ErrorMessages.SendingError.getMessage(), e);
        }
    }

    public void send(String host, int port, Message message) throws LinkException {
        if (processSocket.isClosed()) {
            throw new LinkException(ErrorMessages.LinkClosedException);
        }

        SignedMessage signedMessage = prepareMessage(message);

        try {
            InetAddress nodeHost = InetAddress.getByName(host);
            sendWithRetry(message, signedMessage, nodeHost, port, host + ":" + port);
        } catch (Exception e) {
            logger.error(ErrorMessages.SendingError.getMessage(), e);
        }
    }

    private SignedMessage prepareMessage(Message message) throws LinkException {
        message.setMessageId(messageCounter.getAndIncrement());
        try {
            return new SignedMessage(message, keyService.loadPrivateKey(privateKeyPrefix + myProcess.getId()));
        } catch (Exception e) {
            throw new LinkException(ErrorMessages.SignatureError, e);
        }
    }

    private void sendWithRetry(Message message, SignedMessage signedMessage, InetAddress host, int port, String destination)
            throws InterruptedException {
        int sleepTime = baseSleepTime;
        for (int attempts = 1; !acksList.contains(message.getMessageId()); attempts++) {
            logger.info("P{}: Sending message {} {} to {} attempt {}",
                    myProcess.getId(), message.getType(), message.getMessageId(), destination, attempts);
            unreliableSend(host, port, signedMessage);
            Thread.sleep(sleepTime);
            sleepTime *= 2;
        }
        logger.info("P{}: Message {} {} sent to {}", myProcess.getId(), message.getType(), message.getMessageId(), destination);
    }

    private void unreliableSend(InetAddress host, int port, SignedMessage message) {
        try {
            // Sign the message to make sure link is authenticated
            byte[] bytes = new Gson().toJson(message).getBytes();
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length, host, port);
            processSocket.send(packet);
        } catch (Exception e) {
            logger.error(ErrorMessages.SendingError.getMessage(), e);
        }
    }

    private DatagramPacket listenOnProcessSocket() throws IOException {
        byte[] buf = new byte[65535];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        processSocket.receive(packet);
        return packet;
    }

    private void handleAckMessage(SignedMessage message) {
        acksList.add(message.getMessageId());
        logger.info("P{}: ACK {} received from node P{}",
                myProcess.getId(), message.getMessageId(), message.getSenderId());
    }

    private boolean checkDelivered(SignedMessage message) {
        DeliveryKey key = new DeliveryKey(message.getSenderId(), message.getType());
        if(!deliveredMessages.containsKey(key)) {
            deliveredMessages.put(key, new CollapsingSet());
            return false;
        }
        return deliveredMessages.get(key).contains(message.getMessageId());
    }

    private void addDelivered(SignedMessage message) {
        DeliveryKey key = new DeliveryKey(message.getSenderId(), message.getType());
        deliveredMessages.get(key).add(message.getMessageId());
    }

    private void handleNonAckMessage(SignedMessage message, String destinationType) throws Exception {
        logger.info("P{}: Message {} {} received from node P{}.",
                myProcess.getId(), message.getType(), message.getMessageId(), message.getSenderId());

        // Send ACK back to the sender if not me
        if(message.getSenderId() != myProcess.getId()) {
            Process sender = peers.get(message.getSenderId());
            String senderHost = sender.getHost();
            int senderPort = sender.getPort();
            sendAck (
                    message.getSenderId(),
                    message.getMessageId(),
                    senderHost,
                    senderPort,
                    destinationType
            );
        }

        // Notify observers of the received message
        if(!checkDelivered(message)) {
            addDelivered(message);
            notifyObservers(message);
        }
    }

    private void sendAck(int senderId, int messageId, String host, int port, String destinationType) throws Exception {
        SignedMessage signedResponse = new SignedMessage(
                myProcess.getId(), senderId, messageId,
                Message.Type.ACK, keyService.loadPrivateKey(privateKeyPrefix + myProcess.getId())
        );
        unreliableSend(InetAddress.getByName(host), port, signedResponse);
        logger.info("P{}: ACK {} sent to node {}{}",
                myProcess.getId(), messageId, destinationType, senderId);
    }

    private void socketReceiver() {
        logger.info("P{}: Started listening on socket.", myProcess.getId());
        while (running && !processSocket.isClosed()) {
            try {
                DatagramPacket packet = listenOnProcessSocket();
                byte[] buffer = Arrays.copyOfRange(packet.getData(), 0, packet.getLength());
                String json = new String(buffer);
                SignedMessage message = new Gson().fromJson(json, SignedMessage.class);
                messageQueue.add(message);
            } catch (Exception e) {
                if (running) {
                    logger.error("Error in message receiver: {}", e.getMessage(), e);
                }
            }

        }
    }

    private void messageReceiver() {
        logger.info("P{}: Started listening.", myProcess.getId());

        while (running && !processSocket.isClosed()) {
            try {
                SignedMessage message = messageQueue.take();
                PublicKey peerPublicKey = keyService.loadPublicKey(publicKeyPrefix + message.getSenderId());
                boolean messageIsAuthentic = SecurityUtil.verifySignature(message, peerPublicKey);

                if (!messageIsAuthentic) {
                    logger.error("P{}: Message {} received from node P{} is not authentic.",
                            myProcess.getId(), message.getMessageId(), message.getSenderId());
                    continue;
                }

                if (message.getType() == Message.Type.ACK) {
                    handleAckMessage(message);
                } else {
                    handleNonAckMessage(message, "P");
                }
            } catch (Exception e) {
                if (running) {
                    logger.error("Error in message receiver: {}", e.getMessage(), e);
                }
            }
        }
    }

    public void waitForTermination() {
        try {
            receiverThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
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
            logger.info("P{}: Notifying observer of message {} {}",
                    myProcess.getId(), message.getType(), message.getMessageId());
            observer.update(message);
        }
    }

    @Override
    public void close() {
        running = false;
        processSocket.close();
    }

}
