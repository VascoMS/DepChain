package consensus.core.primitives;

import com.google.gson.Gson;
import consensus.core.KeyService;
import consensus.core.model.*;
import consensus.exception.LinkException;
import consensus.util.Observer;
import consensus.util.Process;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;

import static consensus.core.model.ConsensusPayload.ConsensusType.READ;

public class ConsensusBroker implements Observer {

    private static final Logger logger = LoggerFactory.getLogger(ConsensusBroker.class);
    // private static final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final ConcurrentHashMap<String, BlockingQueue<ConsensusPayload>> consensus;
    private final BlockingQueue<Transaction> decidedMessages;
    private final ConcurrentHashMap<String, CompletableFuture<Void>> senderFutures = new ConcurrentHashMap<>();
    private final Process myProcess;
    private final Process[] peers;
    private final Link link;
    private final int byzantineProcesses;
    private final KeyService keyService;
    private final int epoch;
    private final WriteState myState;
    private final ExecutionModule executionModule;

    public ConsensusBroker(Process myProcess, Process[] peers, Link link, int byzantineProcesses, KeyService keyService, WriteState myState) {
        consensus = new ConcurrentHashMap<>();
        decidedMessages = new LinkedBlockingQueue<>();
        this.myProcess = myProcess;
        this.peers = peers;
        this.link = link;
        this.byzantineProcesses = byzantineProcesses;
        this.keyService = keyService;
        this.epoch = 0;
        this.myState = myState;
        this.executionModule = new ExecutionModule(decidedMessages);
        link.addObserver(this);
    }

    @Override
    public void update(Message message) {
        if(message.getType() != Message.Type.CONSENSUS) return;
        ConsensusPayload cPayload = new Gson().fromJson(message.getPayload(), ConsensusPayload.class);
        // Creates a new broadcast to allow for a listener process to collect the messages and deliver them according to the Reliable Broadcast specification
        logger.info("P{}: Received {} broadcast message from P{}",
                myProcess.getId(), cPayload.getCType(), cPayload.getSenderId());
        BlockingQueue<?> oldQueue = consensus.putIfAbsent(cPayload.getConsensusId(), new LinkedBlockingQueue<>());
        if(oldQueue == null) {
            new Thread(() -> {
                Consensus broadcast =
                        new Consensus(this, myProcess, peers, keyService, link, epoch, myState, byzantineProcesses);
                try {
                    Transaction deliveredMessage = broadcast.collect(cPayload.getConsensusId());
                    if(deliveredMessage != null) {
                        decidedMessages.add(deliveredMessage);
                        consensus.remove(cPayload.getConsensusId());
                        if(senderFutures.containsKey(cPayload.getConsensusId()))
                            senderFutures.get(cPayload.getConsensusId()).complete(null);
                        senderFutures.remove(cPayload.getConsensusId());
                    }
                } catch (Exception e) {
                    logger.error("P{}: Error collecting broadcast messages: {}", myProcess.getId(), e.getMessage());
                }
            }).start();
        }
        try {
            consensus.get(cPayload.getConsensusId()).put(cPayload);
        } catch(InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public CompletableFuture<Void> startConsensus() throws LinkException {
        int myId = myProcess.getId();
        ConsensusPayload cPayload = new ConsensusPayload(myId, READ, null, myId, keyService);
        CompletableFuture<Void> future = new CompletableFuture<>();
        senderFutures.put(cPayload.getConsensusId(), future);
        String payloadString = new Gson().toJson(cPayload);
        logger.info("P{}: Starting consensus", myProcess.getId());

        // Send the message to myself
        link.send(myId, new Message(myId, myId, Message.Type.CONSENSUS, payloadString));
        // Send the message to everybody else
        for (Process process : peers) {
            int processId = process.getId();
            link.send(process.getId(), new Message(myId, processId, Message.Type.CONSENSUS, payloadString));
        }
        return future;
    }

    protected ConsensusPayload receiveConsensusMessage(String consensusId) throws InterruptedException {
        return consensus.get(consensusId).take();
    }

    public Transaction receiveMessage() throws InterruptedException {
        executionModule.start();
        //return decidedMessages.take();
    }
}
