package common.primitives;

import com.google.gson.Gson;
import common.model.DeliveryKey;
import common.model.Message;
import common.model.SignedMessage;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.consensus.exception.ErrorMessages;
import server.consensus.exception.LinkException;
import util.*;
import util.Observer;
import util.Process;

public class Link implements AutoCloseable, Subject<Message> {
    private static final Logger logger = LoggerFactory.getLogger(Link.class);
    private final DatagramSocket processSocket;
    private final Process myProcess;
    private final Map<Integer, Process> peers;
    private final Type linkType;
    private final ConcurrentHashMap<Integer, SecretKeySpec> keys;
    private final Map<DeliveryKey, CollapsingSet> deliveredMessages = new HashMap<>();
    private final CollapsingSet acksList;
    private final AtomicInteger messageCounter;
    private final BlockingQueue<SignedMessage> messageQueue;
    private final KeyService keyService;
    private final String privateKeyPrefix;
    private final String publicKeyPrefix;
    private final int baseSleepTime;
    private final ConcurrentHashMap<Integer, CompletableFuture<Boolean>> keyExchangeFutures;
    private final List<Observer<Message>> observers;
    private final Thread receiverThread;
    private final Thread socketThread;
    private boolean running;

    public enum Type {
        // I am a server and my peers are servers.
        SERVER_TO_SERVER,
        // I am a client and my peers are servers.
        CLIENT_TO_SERVER,
        // I am a server and my peers are clients.
        SERVER_TO_CLIENT
    }

    public Link(Process myProcess, Process[] peers, Type type, int baseSleepTime, String privateKeyPrefix, String publicKeyPrefix, String keyStorePath) throws Exception {
        this(myProcess, type, baseSleepTime, privateKeyPrefix, publicKeyPrefix, keyStorePath);

        // Add peers if provided
        if (peers != null) {
            for (Process p : peers) {
                this.peers.put(p.getId(), p);
                keyExchangeFutures.putIfAbsent(p.getId(), new CompletableFuture<>());
            }
        }

        // Start exchanging keys after boot-up. (Clients don't create keys).
        if (linkType != Type.CLIENT_TO_SERVER)
            new Thread(this::keyExchanger).start();
    }

    public Link(Process myProcess, Type type, int baseSleepTime, String privateKeyPrefix, String publicKeyPrefix, String keyStorePath) throws Exception {
        this.keyExchangeFutures = new ConcurrentHashMap<>();
        keyExchangeFutures.putIfAbsent(myProcess.getId(), new CompletableFuture<>());
        this.myProcess = myProcess;
        this.peers = new HashMap<>();
        this.linkType = type;
        this.keys = new ConcurrentHashMap<>();
        this.baseSleepTime = baseSleepTime;
        this.keyService = new KeyService(keyStorePath, "mypass");
        this.privateKeyPrefix = privateKeyPrefix;
        this.publicKeyPrefix = publicKeyPrefix;
        this.observers = new ArrayList<>();
        try {
            logger.info("P{}: Creating socket on {}:{}", myProcess.getId(), myProcess.getHost(), myProcess.getPort());
            this.processSocket = new DatagramSocket(myProcess.getPort(), InetAddress.getByName(myProcess.getHost()));
        } catch (Exception e) {
            throw new LinkException(ErrorMessages.LinkCreationError, e);
        }

        this.messageQueue = new LinkedBlockingQueue<>();
        this.acksList = new CollapsingSet();
        this.messageCounter = new AtomicInteger(0);
        this.running = true;
        receiverThread = new Thread(this::messageReceiver);
        socketThread = new Thread(this::socketReceiver);
        receiverThread.start();
        socketThread.start();
    }

    public void waitForKeyExchange(int nodeId) {
        try {
            keyExchangeFutures.putIfAbsent(nodeId, new CompletableFuture<>());
            keyExchangeFutures.get(nodeId).get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void send(int nodeId, Message message) throws LinkException {
        if (processSocket.isClosed()) {
            throw new LinkException(ErrorMessages.LinkClosedException);
        }

        if (!(peers.containsKey(nodeId) || (nodeId == myProcess.getId() && linkType == Type.SERVER_TO_SERVER))) {
            throw new LinkException(ErrorMessages.NoSuchNodeError);
        }

        if (message.getType() != Message.Type.KEY_EXCHANGE)
            waitForKeyExchange(nodeId);

        // Assuring that destination is set, as this is point-to-point messages.
        message.setDestinationId(nodeId);

        SignedMessage signedMessage = message.getType() == Message.Type.KEY_EXCHANGE
                ? prepareMessageWithSignature(message)
                : prepareMessageWithHMAC(message);

        if (nodeId == myProcess.getId() && linkType == Type.SERVER_TO_SERVER) {
            messageQueue.add(signedMessage);
            logger.info("P{}: Message {} {} added to local messages", myProcess.getId(), message.getType(), message.getMessageId());
            return;
        }

        try {
            Process node = peers.get(nodeId);
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

        SignedMessage signedMessage = message.getType() == Message.Type.KEY_EXCHANGE
                ? prepareMessageWithSignature(message)
                : prepareMessageWithHMAC(message);

        try {
            InetAddress nodeHost = InetAddress.getByName(host);
            sendWithRetry(message, signedMessage, nodeHost, port, host + ":" + port);
        } catch (Exception e) {
            logger.error(ErrorMessages.SendingError.getMessage(), e);
        }
    }

    private SignedMessage prepareMessageWithSignature(Message message) throws LinkException {
        message.setMessageId(messageCounter.getAndIncrement());
        try {
            return new SignedMessage(message, keyService.loadPrivateKey(privateKeyPrefix + myProcess.getId()));
        } catch (Exception e) {
            throw new LinkException(ErrorMessages.SignatureError, e);
        }
    }

    private SignedMessage prepareMessageWithHMAC(Message message) throws LinkException {
        message.setMessageId(messageCounter.getAndIncrement());
        try {
            return new SignedMessage(message, keys.get(message.getDestinationId()));
        } catch (Exception e) {
            throw new LinkException(ErrorMessages.SignatureError, e);
        }
    }

    private void sendWithRetry(Message message, SignedMessage signedMessage, InetAddress host, int port, String destination)
            throws InterruptedException {
        int sleepTime = baseSleepTime;
        for (int attempts = 1; !acksList.contains(message.getMessageId()); attempts++) {
            logger.info("P{}: Sending message {} {} to {} {}:{} attempt {}",
                    myProcess.getId(), message.getType(), message.getMessageId(), destination, host, port, attempts);
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
        if (!deliveredMessages.containsKey(key)) {
            deliveredMessages.put(key, new CollapsingSet());
            return false;
        }
        return deliveredMessages.get(key).contains(message.getMessageId());
    }

    private void addDelivered(SignedMessage message) {
        DeliveryKey key = new DeliveryKey(message.getSenderId(), message.getType());
        deliveredMessages.get(key).add(message.getMessageId());
    }

    private void handleKeyExchangeMessage(SignedMessage message) throws Exception {
        logger.info("P{}: Received key from node P{}.",
                myProcess.getId(), message.getSenderId());

        if (message.getSenderId() != myProcess.getId()) {
            // Unwrap the received key.
            SecretKeySpec secretKey = SecurityUtil.decipherSecretKey(
                    message.getPayload(),
                    keyService.loadPrivateKey(privateKeyPrefix + myProcess.getId())
            );

            keys.put(message.getSenderId(), secretKey);
            keyExchangeFutures.putIfAbsent(message.getSenderId(), new CompletableFuture<>());
            keyExchangeFutures.get(message.getSenderId()).complete(true);
        }
    }

    private void handleNonAckMessage(SignedMessage message, String destinationType) throws Exception {
        logger.info("P{}: Message {} {} received from node P{}.",
                myProcess.getId(), message.getType(), message.getMessageId(), message.getSenderId());

        // Send ACK back to the sender if not me
        if (message.getSenderId() != myProcess.getId() || linkType != Type.SERVER_TO_SERVER) {
            Process sender = peers.get(message.getSenderId());
            String senderHost = sender.getHost();
            int senderPort = sender.getPort();
            sendAck(
                    message.getSenderId(),
                    message.getMessageId(),
                    senderHost,
                    senderPort,
                    destinationType
            );
        }

        // Notify observers of the received message
        if (!checkDelivered(message)) {
            addDelivered(message);
            // Not notifying internal messages for key generation.
            if (message.getType() == Message.Type.KEY_EXCHANGE) return;
            notifyObservers(message);
        }
    }

    private void sendAck(int senderId, int messageId, String host, int port, String destinationType) throws Exception {
        SecretKeySpec key = keys.get(senderId);
        SignedMessage signedResponse = new SignedMessage(myProcess.getId(), senderId, messageId, Message.Type.ACK, key);
        unreliableSend(InetAddress.getByName(host), port, signedResponse);
        logger.info("P{}: ACK {} sent to node {}{}",
                myProcess.getId(), messageId, destinationType, senderId);
    }

    private void keyExchanger() {
        logger.info("P{}: Exchanging keys with peers...", myProcess.getId());
        List<Integer> peersToShareKeys = linkType == Type.SERVER_TO_SERVER
                ? peers.keySet().stream()
                .filter(id -> id > myProcess.getId())
                .toList()
                : peers.keySet().stream().toList();
        // Generate key for myself if the link is server-to-server
        if (linkType == Type.SERVER_TO_SERVER) {
            try {
                keys.put(myProcess.getId(), keyService.generateSecretKey());
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
            keyExchangeFutures.putIfAbsent(myProcess.getId(), new CompletableFuture<>());
            keyExchangeFutures.get(myProcess.getId()).complete(true);
        }
        // Generate key for peers
        for (Integer peer : peersToShareKeys) {
                try {
                    SecretKeySpec key = keyService.generateSecretKey();
                    keys.put(peer, key);
                    String cipheredKey = SecurityUtil.cipherSecretKey(key, keyService.loadPublicKey(publicKeyPrefix + peer));
                    send(peer, new Message(myProcess.getId(), peer, Message.Type.KEY_EXCHANGE, cipheredKey));
                    keyExchangeFutures.putIfAbsent(peer, new CompletableFuture<>());
                    keyExchangeFutures.get(peer).complete(true);
                } catch (Exception e) {
                    logger.error("Error in exchanging keys: {}", e.getMessage(), e);
                    throw new RuntimeException(e);
                }
        }
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
                logger.info("P{}: Message {} {} received from node P{} queue size = {}.",
                        myProcess.getId(), message.getMessageId(), message.getType(), message.getSenderId(), messageQueue.size());
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
                boolean messageIsAuthentic;
                if (message.getType() == Message.Type.KEY_EXCHANGE) {
                    PublicKey peerPublicKey = keyService.loadPublicKey(publicKeyPrefix + message.getSenderId());
                    messageIsAuthentic = SecurityUtil.verifySignature(message, peerPublicKey);
                } else {
                    SecretKeySpec secretKey = keys.get(message.getSenderId());
                    messageIsAuthentic = SecurityUtil.verifyHMAC(message, secretKey);
                }

                if (!messageIsAuthentic) {
                    logger.error("P{}: Message {} received from node P{} is not authentic.",
                            myProcess.getId(), message.getMessageId(), message.getSenderId());
                    continue;
                }

                if (message.getType() == Message.Type.ACK) {
                    handleAckMessage(message);
                } else {
                    if (message.getType() == Message.Type.KEY_EXCHANGE) {
                        handleKeyExchangeMessage(message);
                    }
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
            socketThread.join();
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
        // Be careful not to have a blocking update method in the observer
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
