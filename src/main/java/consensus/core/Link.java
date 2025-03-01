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
    private final KeyService keyService;
    ExecutorService executorService = Executors.newFixedThreadPool(5);
    private final int baseSleepTime;

    public Link(Process myProcess, Process[] peers, int baseSleepTime) throws Exception {
        this.myProcess = myProcess;
        this.peers = new HashMap<>();
        this.baseSleepTime = baseSleepTime;
        this.keyService = new KeyService(SecurityUtil.KEYSTORE_PATH, "mypass");

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
            signedMessage = new SignedMessage(message, keyService.loadPrivateKey("p" + myProcess.getId()));

        } catch (Exception e) {
            throw new LinkException(ErrorMessages.SignatureError, e);
        }
        message.setMessageId(messageCounter.getAndIncrement());

        if(nodeId == myProcess.getId()) {
            localMessages.add(signedMessage);
            logger.info("P{}: Message {} {} added to local messages", myProcess.getId(), message.getType(), message.getMessageId());
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
                for(int attempts = 1; attempts != 5 && !acksList.contains(message.getMessageId()); attempts++){
                    logger.info("P{}: Sending message {} {} to node P{} attempt {}",
                            myProcess.getId(), message.getType(), message.getMessageId(), nodeId, attempts);
                    unreliableSend(nodeHost, nodePort, signedMessage);
                    Thread.sleep(sleepTime);
                    sleepTime *= 2;
                }
                logger.info("P{}: Message {} {} sent to node P{}", myProcess.getId(), message.getType(), message.getMessageId(), nodeId);
            } catch (Exception e){
                logger.error(ErrorMessages.SendingError.getMessage(), e);
            }
        });
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

    private DatagramPacket listenOnSocket() throws IOException{
        byte[] buf = new byte[65535];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        socket.receive(packet);
        return packet;
    }

    public Message receive() throws LinkException {
        if(socket.isClosed()) {
            throw new LinkException(ErrorMessages.LinkClosedException);
        }
        try {
            SignedMessage message;
            if(!localMessages.isEmpty()) {
                message = localMessages.poll();
                PublicKey peerPublicKey = keyService.loadPublicKey("p" + myProcess.getId());
                boolean messageIsAuthentic = SecurityUtil.verifySignature(message, peerPublicKey);
                if(messageIsAuthentic) {
                    acksList.add(message.getMessageId());
                    logger.info("P{}: Message {} {} received from self.", myProcess.getId(), message.getType(), message.getMessageId());
                }
            } else {
                do {
                    DatagramPacket packet = listenOnSocket();
                    byte[] buffer = Arrays.copyOfRange(packet.getData(), 0, packet.getLength());
                    message = new Gson().fromJson(new String(buffer), SignedMessage.class);
                    int senderId = message.getSenderId();
                    int messageId = message.getMessageId();
                    PublicKey peerPublicKey = keyService.loadPublicKey("p" + message.getSenderId());
                    boolean messageIsAuthentic = SecurityUtil.verifySignature(message, peerPublicKey);
                    if(!messageIsAuthentic) {
                        logger.error("P{}: Message {} received from node P{} is not authentic.",myProcess.getId(), message.getMessageId(), message.getSenderId());
                        return null;
                    }
                    if(message.getType() == Message.Type.ACK){
                        acksList.add(message.getMessageId());
                        logger.info("P{}: ACK {} received from node P{}",myProcess.getId(), message.getMessageId(), senderId);
                    } else {
                        logger.info("P{}: Message {} {} received from node P{}.", myProcess.getId(), message.getType(), message.getMessageId(), senderId);
                        InetAddress senderHost = packet.getAddress();
                        int senderPort = packet.getPort();
                        // Responding with an ACK to the sender
                        SignedMessage signedResponse = new SignedMessage(
                                myProcess.getId(), message.getSenderId(), Message.Type.ACK,
                                keyService.loadPrivateKey("p" + myProcess.getId())
                        );
                        signedResponse.setMessageId(messageId);
                        unreliableSend(senderHost, senderPort, signedResponse);
                        logger.info("P{}: ACK {} sent to node P{}",myProcess.getId(), message.getMessageId(), senderId);
                        }
                } while(message.getType() == Message.Type.ACK);

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
