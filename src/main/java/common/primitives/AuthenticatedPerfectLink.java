package common.primitives;

import common.model.DeliveryKey;
import common.model.Message;
import common.model.SignedMessage;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.consensus.exception.ErrorMessages;
import server.consensus.exception.LinkException;
import util.*;
import util.Observer;
import util.Process;

public class AuthenticatedPerfectLink implements AutoCloseable, Subject<Message>, Observer<Message> {
    private static final Logger logger = LoggerFactory.getLogger(AuthenticatedPerfectLink.class);
    private final Map<DeliveryKey, CollapsingSet> deliveredMessages = new HashMap<>();
    private final Process myProcess;
    private final String privateKeyPrefix;
    private final String publicKeyPrefix;
    private final StubbornLink stbLink;
    private final List<Observer<Message>> observers = new ArrayList<>();
    private final LinkType linkType;
    private final AtomicInteger messageCounter;
    private final KeyService keyService;
    private final ConcurrentHashMap<Integer, SecretKeySpec> keys = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, CompletableFuture<Boolean>> keyExchangeFutures = new ConcurrentHashMap<>();

    public AuthenticatedPerfectLink(Process myProcess, Process[] peers, LinkType type, int baseSleepTime,
                                    String privateKeyPrefix, String publicKeyPrefix, String keyStorePath) throws Exception {
        this.myProcess = myProcess;
        this.stbLink = new StubbornLink(myProcess, peers, type, baseSleepTime);
        this.linkType = type;
        this.privateKeyPrefix = privateKeyPrefix;
        this.publicKeyPrefix = publicKeyPrefix;
        this.messageCounter = new AtomicInteger(0);
        // Initialize key exchanger
        this.keyService = new KeyService(keyStorePath, "mypass");

        // Add peers if provided
        if (peers != null) {
            for (Process p : peers) {
                keyExchangeFutures.putIfAbsent(p.getId(), new CompletableFuture<>());
            }
        }

        // For self-messaging in SERVER_TO_SERVER mode
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
        // For SERVER_TO_SERVER, generate a key for self
        if (linkType == LinkType.SERVER_TO_SERVER) {
            try {
                keys.put(myProcess.getId(), keyService.generateSecretKey());
                keyExchangeFutures.get(myProcess.getId()).complete(true);
            } catch (Exception e) {
                logger.error("Error generating key for self: {}", e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }

        // Determine peers that need key exchange
        List<Integer> peersToShareKeys = linkType == LinkType.SERVER_TO_SERVER
                ? Arrays.stream(peers).map(Process::getId).filter(id -> id > myProcess.getId())
                .toList()
                : Arrays.stream(peers).map(Process::getId).toList();

        // Exchange keys with peers
        for (Integer peerId : peersToShareKeys) {
            try {
                // Generate a new secret key
                SecretKeySpec key = keyService.generateSecretKey();
                keys.put(peerId, key);

                // Encrypt the key with peer's public key
                String cipheredKey = SecurityUtil.cipherSecretKey(key, keyService.loadPublicKey(publicKeyPrefix + peerId));
                logger.info("P{}: Sending key to peer P{}: {}", myProcess.getId(), peerId, cipheredKey);

                // Create and sign a key exchange message
                Message keyMessage = new Message(myProcess.getId(), peerId, Message.Type.KEY_EXCHANGE, cipheredKey);

                // Send the signed key exchange message using the underlying stubborn link
                send(peerId, keyMessage);

                // Complete future to indicate key is ready
                keyExchangeFutures.get(peerId).complete(true);
                logger.info("P{}: Key exchanged with peer P{}", myProcess.getId(), peerId);
            } catch (Exception e) {
                logger.error("Error exchanging key with peer {}: {}", peerId, e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }
    }

    private void sendSignedMessage(int nodeId, SignedMessage signedMessage) throws LinkException {
        // Convert SignedMessage to a Message with the signed content as payload
        String signedContent = new com.google.gson.Gson().toJson(signedMessage);
        Message wrappedMessage = new Message(
                signedMessage.getSenderId(),
                signedMessage.getDestinationId(),
                signedMessage.getType(),
                signedContent
        );
        wrappedMessage.setMessageId(signedMessage.getMessageId());

        // Use the link's send method
        stbLink.send(nodeId, wrappedMessage);
    }


    public void send(int nodeId, Message message) throws LinkException {

        // For non-key-exchange messages, wait for key to be available
        if (message.getType() != Message.Type.KEY_EXCHANGE) {
            waitForKeyExchange(nodeId);
        }

        // Set message ID and destination
        message.setMessageId(messageCounter.getAndIncrement());
        message.setDestinationId(nodeId);

        // Authenticate the message
        try {
            SignedMessage signedMessage;
            if (message.getType() == Message.Type.KEY_EXCHANGE) {
                // For key exchange, use digital signature
                signedMessage = new SignedMessage(message, keyService.loadPrivateKey(privateKeyPrefix + myProcess.getId()));
            } else {
                // For regular messages, use HMAC with shared secret key
                signedMessage = new SignedMessage(message, keys.get(nodeId));
            }

            // Send the signed message
            sendSignedMessage(nodeId, signedMessage);
        } catch (Exception e) {
            logger.error("Error signing message: {}", e.getMessage(), e);
            throw new LinkException(ErrorMessages.SignatureError, e);
        }
    }

    private void waitForKeyExchange(int nodeId) {
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

            // Extract and decrypt the secret key
            SecretKeySpec secretKey =
                    SecurityUtil.decipherSecretKey(message.getPayload(), keyService.loadPrivateKey(privateKeyPrefix + myProcess.getId()));

            // Store the key and complete the future
            keys.put(message.getSenderId(), secretKey);
            keyExchangeFutures.putIfAbsent(message.getSenderId(), new CompletableFuture<>());
            keyExchangeFutures.get(message.getSenderId()).complete(true);
        } catch (Exception e) {
            logger.error("Error handling key exchange: {}", e.getMessage(), e);
        }
    }

    private void processAuthenticatedMessage(SignedMessage message) {
        // Check if message has already been delivered
        DeliveryKey key = new DeliveryKey(message.getSenderId(), message.getType());

        if (!deliveredMessages.containsKey(key)) {
            deliveredMessages.put(key, new CollapsingSet());
        }

        // Only process if not already delivered (perfect link property)
        if (!deliveredMessages.get(key).contains(message.getMessageId())) {
            deliveredMessages.get(key).add(message.getMessageId());

            // Notify observers
            logger.info("P{}: Delivering message {} from P{} to application layer",
                    myProcess.getId(), message.getMessageId(), message.getSenderId());
            notifyObservers(message);
        } else {
            logger.info("P{}: Message {} from P{} already delivered, discarding duplicate",
                    myProcess.getId(), message.getMessageId(), message.getSenderId());
        }
    }

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
        // Convert message back to SignedMessage
        try {
            SignedMessage signedMessage = new com.google.gson.Gson().fromJson(
                    message.getPayload(), SignedMessage.class);

            // Verify message authenticity
            boolean messageIsAuthentic;
            if (signedMessage.getType() == Message.Type.KEY_EXCHANGE) {
                messageIsAuthentic = SecurityUtil.verifySignature(
                        signedMessage,
                        keyService.loadPublicKey(publicKeyPrefix + message.getSenderId())
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

            // Handle specific message types
            if (signedMessage.getType() == Message.Type.KEY_EXCHANGE) {
                handleKeyExchangeMessage(signedMessage);
            } else {
                // For other message types, process if not already delivered
                processAuthenticatedMessage(signedMessage);
            }
        } catch (Exception e) {
            logger.error("Error processing message: {}", e.getMessage(), e);
        }
    }
}