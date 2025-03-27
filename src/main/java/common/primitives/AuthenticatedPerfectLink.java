package common.primitives;

import common.model.DeliveryKey;
import common.model.Message;
import common.model.SignedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.consensus.exception.ErrorMessages;
import server.consensus.exception.LinkException;
import util.Observer;
import util.Process;
import util.*;

import javax.crypto.spec.SecretKeySpec;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class AuthenticatedPerfectLink implements AutoCloseable, Subject<Message>, Observer<Message> {
    private static final Logger logger = LoggerFactory.getLogger(AuthenticatedPerfectLink.class);
    private final Map<DeliveryKey, CollapsingSet> deliveredMessages = new HashMap<>();
    private final Process myProcess;
    private final StubbornLink stbLink;
    private final List<Observer<Message>> observers = new ArrayList<>();
    private final LinkType linkType;
    private final AtomicInteger messageCounter;
    private final KeyService keyService;
    private final ConcurrentHashMap<String, SecretKeySpec> keys = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> keyExchangeFutures = new ConcurrentHashMap<>();

    public AuthenticatedPerfectLink(Process myProcess, Process[] peers, LinkType type, int baseSleepTime,
                                    String keyStorePath) throws Exception {
        this.myProcess = myProcess;
        this.stbLink = new StubbornLink(myProcess, peers, type, baseSleepTime);
        this.linkType = type;
        this.messageCounter = new AtomicInteger(0);
        this.keyService = new KeyService(keyStorePath, "mypass");

        if (peers != null) {
            for (Process p : peers) {
                keyExchangeFutures.putIfAbsent(p.getId(), new CompletableFuture<>());
            }
        }

        if (type == LinkType.SERVER_TO_SERVER) {
            keyExchangeFutures.putIfAbsent(myProcess.getId(), new CompletableFuture<>());
        }

        stbLink.addObserver(this);
    }

    public void start() {
        stbLink.start();
        if (linkType != LinkType.CLIENT_TO_SERVER) {
            new Thread(() -> initiateKeyExchange(stbLink.getPeers().values().toArray(new Process[0]))).start();
        }
    }

    private void initiateKeyExchange(Process[] peers) {
        if (linkType == LinkType.SERVER_TO_SERVER) {
            try {
                keys.put(myProcess.getId(), keyService.generateSecretKey());
                keyExchangeFutures.get(myProcess.getId()).complete(true);
            } catch (Exception e) {
                logger.error("Error generating key for self: {}", e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }

        List<String> peersToShareKeys = (linkType == LinkType.SERVER_TO_SERVER)
                ? Arrays.stream(peers).filter(peer -> myProcess.getId().compareTo(peer.getId()) < 0)
                .map(Process::getId)
                .toList()
                : Arrays.stream(peers).map(Process::getId).toList();

        for (String peerId : peersToShareKeys) {
            try {
                SecretKeySpec key = keyService.generateSecretKey();
                keys.put(peerId, key);

                String cipheredKey = SecurityUtil.cipherSecretKey(key, keyService.loadPublicKey(peerId));
                logger.info("P{}: Sending key to peer P{}: {}", myProcess.getId(), peerId, cipheredKey);

                Message keyMessage = new Message(myProcess.getId(), peerId, Message.Type.KEY_EXCHANGE, cipheredKey);

                send(peerId, keyMessage);

                keyExchangeFutures.get(peerId).complete(true);
                logger.info("P{}: Key exchanged with peer P{}", myProcess.getId(), peerId);
            } catch (Exception e) {
                logger.error("Error exchanging key with peer {}: {}", peerId, e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }
    }


    public void send(String nodeId, Message message) throws LinkException {

        if (stbLink.isClosed()) {
            throw new LinkException(ErrorMessages.LinkClosedException);
        }
        if (!stbLink.getPeers().containsKey(nodeId) && !nodeId.equals(myProcess.getId())) {
            throw new LinkException(ErrorMessages.NoSuchNodeError);
        }

        if (message.getType() != Message.Type.KEY_EXCHANGE) {
            waitForKeyExchange(nodeId);
        }

        message.setMessageId(messageCounter.getAndIncrement());
        message.setDestinationId(nodeId);

        logger.info("P{}: Sending {} message {} to peer P{}: {}",
                myProcess.getId(), message.getType(), message.getMessageId(),
                nodeId, message.getPayload()
        );

        try {
            SignedMessage signedMessage;
            if (message.getType() == Message.Type.KEY_EXCHANGE) {
                signedMessage = new SignedMessage(message, keyService.loadPrivateKey(myProcess.getId()));
            } else {
                signedMessage = new SignedMessage(message, keys.get(nodeId));
            }
            logger.info("P{}: Generated integrity field for message {}: {}",
                    myProcess.getId(), message.getMessageId(), signedMessage.getIntegrity()
            );

            sendSignedMessage(nodeId, signedMessage);
        } catch (Exception e) {
            logger.error("Error signing message: {}", e.getMessage(), e);
            throw new LinkException(ErrorMessages.SignatureError, e);
        }
    }

    private void sendSignedMessage(String nodeId, SignedMessage signedMessage) throws LinkException {
        String signedContent = new com.google.gson.Gson().toJson(signedMessage);
        Message wrappedMessage = new Message(
                signedMessage.getSenderId(),
                signedMessage.getDestinationId(),
                signedMessage.getType(),
                signedContent
        );
        wrappedMessage.setMessageId(signedMessage.getMessageId());

        stbLink.send(nodeId, wrappedMessage);
    }

    private void waitForKeyExchange(String nodeId) {
        try {
            keyExchangeFutures.putIfAbsent(nodeId, new CompletableFuture<>());
            keyExchangeFutures.get(nodeId).get();
        } catch (Exception e) {
            throw new RuntimeException("Error waiting for key exchange with node " + nodeId, e);
        }
    }

    private void handleKeyExchangeMessage(SignedMessage message) {
        try {
            logger.info("P{}: Received key from P{}", myProcess.getId(), message.getSenderId());

            SecretKeySpec secretKey =
                    SecurityUtil.decipherSecretKey(message.getPayload(), keyService.loadPrivateKey(myProcess.getId()));

            keys.put(message.getSenderId(), secretKey);
            keyExchangeFutures.putIfAbsent(message.getSenderId(), new CompletableFuture<>());
            keyExchangeFutures.get(message.getSenderId()).complete(true);
            logger.info("P{}: Key exchanged with peer P{}", myProcess.getId(), message.getSenderId());
        } catch (Exception e) {
            logger.error("Error handling key exchange: {}", e.getMessage(), e);
        }
    }

    private void processAuthenticatedMessage(SignedMessage message) {
        DeliveryKey key = new DeliveryKey(message.getSenderId(), message.getType());

        if (!deliveredMessages.containsKey(key)) {
            deliveredMessages.put(key, new CollapsingSet());
        }

        if (!deliveredMessages.get(key).contains(message.getMessageId())) {
            deliveredMessages.get(key).add(message.getMessageId());

            logger.info("P{}: Delivering message {} from P{} to application layer",
                    myProcess.getId(), message.getMessageId(), message.getSenderId());
            notifyObservers(message);
        } else {
            logger.info("P{}: Message {} from P{} already delivered, discarding duplicate",
                    myProcess.getId(), message.getMessageId(), message.getSenderId());
        }
    }

    public void waitForTermination() {
        stbLink.waitForTermination();
    }

    public boolean isClosed() { return stbLink.isClosed(); }

    @Override
    public void addObserver(Observer<Message> observer) {
        observers.add(observer);
    }

    @Override
    public void removeObserver(Observer<Message> observer) {
        observers.remove(observer);
    }

    @Override
    public void notifyObservers(Message message) {
        for (Observer<Message> observer : observers) {
            logger.info("P{}: Notifying observer of message from P{}",
                    myProcess.getId(), message.getSenderId());
            observer.update(message);
        }
    }


    @Override
    public void close()  {
        stbLink.close();
    }

    @Override
    public void update(Message message) {
        try {
            SignedMessage signedMessage = new com.google.gson.Gson().fromJson(
                    message.getPayload(), SignedMessage.class);

            boolean messageIsAuthentic;
            if (signedMessage.getType() == Message.Type.KEY_EXCHANGE) {
                messageIsAuthentic = SecurityUtil.verifySignature(
                        signedMessage,
                        keyService.loadPublicKey(message.getSenderId())
                );
            } else {
                SecretKeySpec key = keys.get(signedMessage.getSenderId());
                messageIsAuthentic = SecurityUtil.verifyHMAC(signedMessage, key);
            }

            if (!messageIsAuthentic) {
                logger.error("P{}: Message {} from P{} failed authentication check",
                        myProcess.getId(), signedMessage.getMessageId(), signedMessage.getSenderId());
                return;
            }

            if (signedMessage.getType() == Message.Type.KEY_EXCHANGE) {
                handleKeyExchangeMessage(signedMessage);
            } else {
                processAuthenticatedMessage(signedMessage);
            }
        } catch (Exception e) {
            logger.error("Error processing message: {}", e.getMessage(), e);
        }
    }
}