package consensus.core.primitives;

import com.google.gson.Gson;
import consensus.core.model.Message;
import consensus.exception.LinkException;
import consensus.util.Observer;
import consensus.util.Process;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;

public class BroadcastBroker implements Observer {

    private static final Logger logger = LoggerFactory.getLogger(BroadcastBroker.class);
    private static final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final ConcurrentHashMap<String, BlockingQueue<BroadcastMessage>> broadcasts;
    private final LinkedBlockingQueue<Message> deliveredMessages;
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
        if(!(message instanceof BroadcastMessage bMessage)) return;
        // Creates a new broadcast to allow for a listener process to collect the messages and deliver them according to the Reliable Broadcast specification
        broadcasts.computeIfAbsent(bMessage.getBroadcastId(), k -> {
            executor.execute(() -> {
                ReliableBroadcast broadcast =
                        new ReliableBroadcast(this, myProcess, peers, link, byzantineProcesses);
                try {
                    Message deliveredMessage = broadcast.collect(bMessage.getBroadcastId());
                    deliveredMessages.add(deliveredMessage);
                    broadcasts.remove(bMessage.getBroadcastId());
                    senderFutures.get(bMessage.getBroadcastId()).complete(null);
                    senderFutures.remove(bMessage.getBroadcastId());
                } catch (LinkException e) {
                    logger.error("P{}: Error collecting broadcast messages: {}", myProcess.getId(), e.getMessage());
                }
            });
            return new LinkedBlockingQueue<>();
        });
        broadcasts.get(bMessage.getBroadcastId()).add(bMessage);
    }

    public CompletableFuture<Void> broadcast(Message message) throws LinkException {
        int myId = myProcess.getId();
        BroadcastMessage bMessage = new BroadcastMessage(
                message.getSenderId(),
                message.getDestinationId(),
                message.getType(),
                message.getPayload()
        );
        CompletableFuture<Void> future = new CompletableFuture<>();
        senderFutures.put(bMessage.getBroadcastId(), future);
        String messageString = new Gson().toJson(bMessage);
        logger.info("P{}: Broadcasting message with id {}", myProcess.getId(), message.getMessageId());

        // Send the message to myself
        link.send(myId, new Message(myId, myId, Message.Type.SEND, messageString));
        // Send the message to everybody else
        for (Process process : peers) {
            int processId = process.getId();
            link.send(process.getId(), new Message(myId, processId, Message.Type.SEND, messageString));
        }
        return future;
    }

    protected BroadcastMessage receiveBroadcastMessage(String broadcastId) {
        broadcasts.computeIfAbsent(broadcastId, k -> new LinkedBlockingQueue<>());
        return broadcasts.get(broadcastId).poll();
    }

    public Message receiveMessage()  {
        return deliveredMessages.poll();
    }
}
