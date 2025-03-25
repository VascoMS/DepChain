package common.primitives;

import com.google.gson.Gson;
import common.model.Message;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.consensus.exception.ErrorMessages;
import server.consensus.exception.LinkException;
import util.Observer;
import util.Process;
import util.Subject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class FairLossLink implements AutoCloseable, Subject<Message> {
    private static final Logger logger = LoggerFactory.getLogger(FairLossLink.class);
    private final DatagramSocket processSocket;
    private final Process myProcess;
    private final BlockingQueue<Message> messageQueue;
    private Thread socketThread;
    private Thread queueThread;
    @Getter
    private final Map<String, Process> peers;
    private final LinkType type;
    protected boolean running;
    protected final List<Observer<Message>> observers;

    public FairLossLink(Process myProcess, LinkType type) throws Exception {
        this.myProcess = myProcess;
        this.type = type;
        this.messageQueue = new LinkedBlockingQueue<>();
        this.peers = new HashMap<>();
        this.observers = new ArrayList<>();

        try {
            logger.info("P{}: Creating socket on {}:{}", myProcess.getId(), myProcess.getHost(), myProcess.getPort());
            this.processSocket = new DatagramSocket(myProcess.getPort(), InetAddress.getByName(myProcess.getHost()));
        } catch (Exception e) {
            throw new LinkException(ErrorMessages.LinkCreationError, e);
        }
    }

    public FairLossLink(Process myProcess, Process[] peers, LinkType type) throws Exception {
        this(myProcess, type);
        for(Process peer: peers) {
            addPeer(peer);
        }
    }

    public void start() {
        this.running = true;
        this.socketThread = new Thread(this::socketReceiver);
        this.queueThread = new Thread(this::queueReceiver);
        this.socketThread.start();
        this.queueThread.start();
    }

    public void addPeer(Process peer) {
        this.peers.put(peer.getId(), peer);
    }

    public void send(String nodeId, Message message) throws LinkException {
        if (processSocket.isClosed()) {
            throw new LinkException(ErrorMessages.LinkClosedException);
        }

        if (!peers.containsKey(nodeId) && !nodeId.equals(myProcess.getId())) {
            throw new LinkException(ErrorMessages.NoSuchNodeError);
        }

        message.setDestinationId(nodeId);

        if (nodeId.equals(myProcess.getId()) && type == LinkType.SERVER_TO_SERVER) {
            messageQueue.add(message);
            logger.info("P{}: Message added to local queue", myProcess.getId());
            return;
        }

        try {
            Process node = peers.get(nodeId);
            InetAddress nodeHost = InetAddress.getByName(node.getHost());
            int nodePort = node.getPort();

            byte[] bytes = new Gson().toJson(message).getBytes();
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length, nodeHost, nodePort);
            processSocket.send(packet);

            logger.info("P{}: Message sent to node P{} at {}:{}",
                    myProcess.getId(), nodeId, nodeHost.getHostAddress(), nodePort);
        } catch (Exception e) {
            logger.error("Error sending message: {}", e.getMessage(), e);
            throw new LinkException(ErrorMessages.SendingError, e);
        }
    }

    protected DatagramPacket listenOnProcessSocket() throws IOException {
        byte[] buf = new byte[65535];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        processSocket.receive(packet);
        return packet;
    }

    private void socketReceiver() {
        logger.info("P{}: Started listening on socket.", myProcess.getId());
        while (running && !processSocket.isClosed()) {
            try {
                DatagramPacket packet = listenOnProcessSocket();
                byte[] buffer = Arrays.copyOfRange(packet.getData(), 0, packet.getLength());
                String json = new String(buffer);
                Message message = new Gson().fromJson(json, Message.class);
                messageQueue.add(message);
            } catch (Exception e) {
                if (running) {
                    logger.error("Error in socket receiver: {}", e.getMessage(), e);
                }
            }
        }
    }

    private void queueReceiver() {
        while(running) {
            try {
                Message message = messageQueue.take();
                logger.info("P{}: Message received from node P{}", myProcess.getId(), message.getSenderId());
                notifyObservers(message);
            } catch (Exception e) {
                if(running) {
                    logger.error("P{}: Error in receiving messages: {}", myProcess.getId(), e.getMessage(), e);
                }
            }
        }
    }

    public void waitForTermination() {
        try {
            socketThread.join();
            queueThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void close() {
        running = false;
        processSocket.close();
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
        for(Observer<Message> observer: observers) {
            observer.update(message);
        }
    }
}