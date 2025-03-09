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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class Link implements AutoCloseable, Subject<Message> {
    private static final Logger logger = LoggerFactory.getLogger(Link.class);
    private final DatagramSocket processSocket;
    private final DatagramSocket clientSocket;
    private final Process myProcess;
    private final Map<Integer, Process> peers;
    private final CollapsingSet acksList;
    private final AtomicInteger messageCounter;
    private final BlockingQueue<SignedMessage> messageQueue;
    private final KeyService keyService;
    private final int baseSleepTime;
    private final List<Observer<Message>> observers;
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
            this.processSocket = new DatagramSocket(myProcess.getPort(), InetAddress.getByName(myProcess.getHost()));
            this.clientSocket = new DatagramSocket(myProcess.getClientPort(), InetAddress.getByName(myProcess.getHost()));
        } catch (Exception e) {
            throw new LinkException(ErrorMessages.LinkCreationError, e);
        }
        this.messageQueue = new LinkedBlockingQueue<>();
        this.acksList = new CollapsingSet();
        this.messageCounter = new AtomicInteger(0);

        Thread receiverThread = new Thread(this::messageReceiver);
        Thread socketThread = new Thread(this::socketReceiver);
        Thread clientThread = new Thread(this::clientReceiver);
        this.running = true;
        receiverThread.start();
        socketThread.start();
        clientThread.start();
    }

    public void send(int nodeId, Message message) throws LinkException {
        if (processSocket.isClosed()) {
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

    private DatagramPacket listenOnClientSocket() throws IOException {
        byte[] buf = new byte[65535];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        clientSocket.receive(packet);
        return packet;
    }

    private void handleAckMessage(SignedMessage message) {
        acksList.add(message.getMessageId());
        logger.info("P{}: ACK {} received from node P{}",
                myProcess.getId(), message.getMessageId(), message.getSenderId());
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
        notifyObservers(message);
    }

    private void sendAck(int senderId, int messageId, String host, int port, String destinationType) throws Exception {
        SignedMessage signedResponse = new SignedMessage(
                myProcess.getId(), senderId, messageId,
                Message.Type.ACK, keyService.loadPrivateKey("p" + myProcess.getId())
        );
        unreliableSend(InetAddress.getByName(host), port, signedResponse);
        logger.info("P{}: ACK {} sent to node {}{}",
                myProcess.getId(), messageId, destinationType, senderId);
    }

    private void socketReceiver() {
        logger.info("P{}: Started listening on socket.", myProcess.getId());
        while(running && !processSocket.isClosed()) {
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

    private void clientReceiver() {
        logger.info("P{}: Started listening for client.", myProcess.getId());

        while (running && !clientSocket.isClosed()) {
            try {
                DatagramPacket packet = listenOnClientSocket();
                byte[] buffer = Arrays.copyOfRange(packet.getData(), 0, packet.getLength());
                String json = new String(buffer);
                SignedMessage message = new Gson().fromJson(json, SignedMessage.class);
                PublicKey peerPublicKey = keyService.loadPublicKey("c" + message.getSenderId());
                boolean messageIsAuthentic = SecurityUtil.verifySignature(message, peerPublicKey);

                if (!messageIsAuthentic) {
                    logger.error("P{}: Message {} received from node C{} is not authentic.",
                            myProcess.getId(), message.getMessageId(), message.getSenderId());
                    continue;
                }

                if (message.getType() == Message.Type.ACK) {
                    handleAckMessage(message);
                } else {
                    handleNonAckMessage(message, "C");
                }
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
                    handleNonAckMessage(message, "P");
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
