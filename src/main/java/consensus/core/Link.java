package consensus.core;

import com.google.gson.Gson;
import consensus.exception.ErrorMessages;
import consensus.exception.LinkException;
import consensus.util.CollapsingSet;
import consensus.util.Process;
import consensus.util.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.DatagramSocket;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Link implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(Link.class);
    private final DatagramSocket socket;
    private final Process myProcess;
    private final Map<Integer, Process> peers;
    private final CollapsingSet acksList;
    private final AtomicInteger messageCounter;
    private final Queue<SignedMessage> localMessages;
    private final String privateKeyPath;
    ExecutorService executorService = Executors.newFixedThreadPool(5);
    private final int baseSleepTime;

    public Link(Process myProcess, Process[] peers, int baseSleepTime) throws LinkException {
        this.myProcess = myProcess;
        this.peers = new HashMap<>();
        this.baseSleepTime = baseSleepTime;
        this.privateKeyPath = "../util/keys/" + myProcess.getId() + ".pem";

        for (Process p : peers) {
            this.peers.put(p.getId(), p);
        }
        try {
            this.socket = new DatagramSocket(myProcess.getPort(), InetAddress.getByName(myProcess.getHost()));
        } catch (Exception e) {
            throw new LinkException(ErrorMessages.LinkCreationError, e);
        }
        this.acksList = new CollapsingSet();
        this.messageCounter = new AtomicInteger(0);
        this.localMessages = new ConcurrentLinkedQueue<>();
    }

    public void send(int nodeId, Message message) throws LinkException {
        if(socket.isClosed()) {
            throw new LinkException(ErrorMessages.LinkClosedException);
        }
        SignedMessage signedMessage;
        try {
            signedMessage = new SignedMessage(message, loadNodePrivateKey());

        } catch (Exception e) {
            throw new LinkException(ErrorMessages.SignatureError, e);
        }
        message.setMessageId(messageCounter.getAndIncrement());

        if(nodeId == myProcess.getId()) {
            localMessages.add(signedMessage);
            logger.info("{}: Message {} added to local messages", message.getMessageId(), myProcess.getId());
            return;
        }

        Process node = peers.get(nodeId);
        if(node == null) {
            throw new LinkException(ErrorMessages.NoSuchNodeError);
        }

        executorService.execute(() -> {
            try {
                InetAddress nodeHost = InetAddress.getByName(node.getHost());
                int nodePort = node.getPort();
                int sleepTime = baseSleepTime;
                for(int attempts = 1; !acksList.contains(message.getMessageId()); attempts++){
                    logger.info("{}: Sending message {} to node {} attempt {}", message.getMessageId(),
                            myProcess.getId(), nodeId, attempts);
                    unreliableSend(nodeHost, nodePort, message);
                    Thread.sleep(sleepTime);
                    sleepTime *= 2;
                }
                logger.info("{}: Message {} sent to node {}", message.getMessageId(), myProcess.getId(), nodeId);
            } catch (Exception e){
                logger.error(ErrorMessages.SendingError.getMessage(), e);
            }
        });
    }

    private PrivateKey loadNodePrivateKey() {
        try {
            return SecurityUtil.loadPrivateKey(privateKeyPath);
        } catch (Exception e) {
            logger.error(ErrorMessages.PrivateKeyError.getMessage(), e);
            return null;
        }
    }

    private void unreliableSend(InetAddress host, int port, Message message) {
        try {
            // Sign the message to make sure link is authenticated
            byte[] bytes = new Gson().toJson(message).getBytes();
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length, host, port);
            socket.send(packet);
        } catch (Exception e) {
            logger.error(ErrorMessages.SendingError.getMessage(), e);
        }
    }

    public Message receive() throws LinkException {
        if(socket.isClosed()) {
            throw new LinkException(ErrorMessages.LinkClosedException);
        }
        try {
            SignedMessage message;
            if(!localMessages.isEmpty()) {
                message = localMessages.poll();
                acksList.add(message.getMessageId());
                logger.info("{}: Message received from self.", message.getMessageId());
            } else {
                byte[] buf = new byte[65535];
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);

                byte[] buffer = Arrays.copyOfRange(packet.getData(), 0, packet.getLength());
                message = new Gson().fromJson(new String(buffer), SignedMessage.class);
                PublicKey peerPublicKey = SecurityUtil.loadPublicKey(peers.get(message.getSenderId()).getPublicKeyPath());
                boolean messageIsAuthentic = SecurityUtil.verifySignature(message, peerPublicKey);
                if(!messageIsAuthentic) {
                    logger.error("{}: Message received from node {} is not authentic.", message.getMessageId(), message.getSenderId());
                    return null;
                }
                int senderId = message.getSenderId();
                int messageId = message.getMessageId();
                logger.info("{}: Message received from node {}.", message.getMessageId(), senderId);
                if(message.getType() == Message.Type.ACK) {
                    acksList.add(messageId);
                    logger.info("{}: ACK received from node {}", message.getMessageId(), senderId);
                } else {
                    InetAddress senderHost = packet.getAddress();
                    int senderPort = packet.getPort();
                    // Responding with an ACK to the sender
                    Message response = new Message(myProcess.getId(), message.getSenderId(), Message.Type.ACK);
                    response.setMessageId(messageId);
                    unreliableSend(senderHost, senderPort, response);
                    logger.info("{}: ACK sent to node {}", message.getMessageId(), senderId);
                }
                return message;
            }
            return message;
        } catch (Exception e) {
            throw new LinkException(ErrorMessages.ReceivingError, e);
        }
    }

    @Override
    public void close() {
        socket.close();
    }

}
