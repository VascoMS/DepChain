package consensus.core.primitives;

import com.google.gson.Gson;
import consensus.core.model.BroadcastPayload;
import consensus.core.model.Message;
import consensus.exception.LinkException;
import consensus.util.Observer;
import consensus.util.Process;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static consensus.core.model.BroadcastPayload.BroadcastType.SEND;

public class BroadcastBroker implements Observer {

    private static final Logger logger = LoggerFactory.getLogger(BroadcastBroker.class);
    private static final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final ConcurrentHashMap<String, BlockingQueue<BroadcastPayload>> broadcasts;
    private final BlockingQueue<String> deliveredMessages;
    private final ConcurrentHashMap<String, CompletableFuture<Void>> senderFutures = new ConcurrentHashMap<>();
    private final Process myProcess;
    private final Process[] peers;
    private final Link link;
    private final int byzantineProcesses;

    public BroadcastBroker(Process myProcess, Process[] peers, Link link, int byzantineProcesses) {
        broadcasts = new ConcurrentHashMap<>();
        deliveredMessages = new LinkedBlockingQueue<>();
        this.myProcess = myProcess;
        this.peers = peers;
        this.link = link;
        this.byzantineProcesses = byzantineProcesses;
        link.addObserver(this);
    }

    @Override
    public void update(Message message) {
        if(message.getType() != Message.Type.BROADCAST) return;
        BroadcastPayload bPayload = new Gson().fromJson(message.getPayload(), BroadcastPayload.class);
        // Creates a new broadcast to allow for a listener process to collect the messages and deliver them according to the Reliable Broadcast specification
        logger.info("P{}: Received {} broadcast message from P{}",
                myProcess.getId(), bPayload.getBType(), bPayload.getSenderId());
        broadcasts.computeIfAbsent(bPayload.getBroadcastId(), k -> {
            executor.execute(() -> {
                ReliableBroadcast broadcast =
                        new ReliableBroadcast(this, myProcess, peers, link, byzantineProcesses);
                try {
                    String deliveredMessage = broadcast.collect(bPayload.getBroadcastId());
                    deliveredMessages.add(deliveredMessage);
                    broadcasts.remove(bPayload.getBroadcastId());
                    if(senderFutures.containsKey(bPayload.getBroadcastId()))
                        senderFutures.get(bPayload.getBroadcastId()).complete(null);
                    senderFutures.remove(bPayload.getBroadcastId());
                } catch (Exception e) {
                    logger.error("P{}: Error collecting broadcast messages: {}", myProcess.getId(), e.getMessage());
                }
            });
            return new LinkedBlockingQueue<>();
        });
        broadcasts.get(bPayload.getBroadcastId()).add(bPayload);
    }

    public CompletableFuture<Void> broadcast(String payload) throws LinkException {
        int myId = myProcess.getId();
        BroadcastPayload bPayload = new BroadcastPayload(myId, SEND, payload);
        CompletableFuture<Void> future = new CompletableFuture<>();
        senderFutures.put(bPayload.getBroadcastId(), future);
        String payloadString = new Gson().toJson(bPayload);
        logger.info("P{}: Broadcasting message: {}", myProcess.getId(), payload);

        // Send the message to myself
        link.send(myId, new Message(myId, myId, Message.Type.BROADCAST, payloadString));
        // Send the message to everybody else
        for (Process process : peers) {
            int processId = process.getId();
            link.send(process.getId(), new Message(myId, processId, Message.Type.BROADCAST, payloadString));
        }
        return future;
    }

    protected BroadcastPayload receiveBroadcastMessage(String broadcastId) throws InterruptedException {

        broadcasts.computeIfAbsent(broadcastId, k -> new LinkedBlockingQueue<>());
        return broadcasts.get(broadcastId).take();
    }

    public String receiveMessage() throws InterruptedException {
        return deliveredMessages.take();
    }
}
