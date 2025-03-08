package consensus.core.primitives;

import com.google.gson.Gson;
import consensus.core.KeyService;
import consensus.core.model.*;
import consensus.exception.LinkException;
import consensus.util.Observer;
import consensus.util.Process;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.*;

import static consensus.core.model.ConsensusPayload.ConsensusType.READ;

public class ConsensusBroker implements Observer {
    // TODO: Maybe change consensus id to consensus round index
    // TODO: Add client request handling
    private static final Logger logger = LoggerFactory.getLogger(ConsensusBroker.class);
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, BlockingQueue<ConsensusPayload>> consensus;
    private final BlockingQueue<Transaction> decidedMessages;
    private final ConcurrentHashMap<String, CompletableFuture<Void>> senderFutures = new ConcurrentHashMap<>();
    private final Process myProcess;
    private final Process[] peers;
    private final Link link;
    private final int byzantineProcesses;
    private final KeyService keyService;
    private int epoch;
    private final ConcurrentLinkedQueue<Transaction> clientRequests = new ConcurrentLinkedQueue<>();
    private final ExecutionModule executionModule;

    public ConsensusBroker(Process myProcess, Process[] peers, Link link, int byzantineProcesses, KeyService keyService) {
        consensus = new ConcurrentHashMap<>();
        decidedMessages = new LinkedBlockingQueue<>();
        this.myProcess = myProcess;
        this.peers = peers;
        this.executor = Executors.newFixedThreadPool(4);
        this.link = link;
        this.byzantineProcesses = byzantineProcesses;
        this.keyService = keyService;
        this.epoch = 0;
        this.executionModule = new ExecutionModule(decidedMessages);
        executionModule.start();
        link.addObserver(this);
    }

    @Override
    public void update(Message message) {
        if(message.getType() != Message.Type.CONSENSUS) return;
        ConsensusPayload cPayload = new Gson().fromJson(message.getPayload(), ConsensusPayload.class);
        // Creates a new broadcast to allow for a listener process to collect the messages and deliver them according to the Reliable Broadcast specification
        logger.info("P{}: Received {} message from P{}",
                myProcess.getId(), cPayload.getCType(), cPayload.getSenderId());
        BlockingQueue<?> oldQueue = consensus.putIfAbsent(cPayload.getConsensusId(), new LinkedBlockingQueue<>());
        if(oldQueue == null) {
            executor.execute(() -> {
                Consensus consensusRound =
                        new Consensus(this, myProcess, peers, keyService, link, epoch, byzantineProcesses);
                try {
                    Transaction deliveredMessage = consensusRound.collect(cPayload.getConsensusId());
                    if(deliveredMessage != null) {
                        decidedMessages.add(deliveredMessage);
                        this.consensus.remove(cPayload.getConsensusId());
                        if(senderFutures.containsKey(cPayload.getConsensusId()))
                            senderFutures.get(cPayload.getConsensusId()).complete(null);
                        senderFutures.remove(cPayload.getConsensusId());
                    }
                } catch (Exception e) {
                    logger.error("P{}: Error collecting messages: {}", myProcess.getId(), e.getMessage());
                }
            });
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

    protected Transaction fetchClientRequest() {
        return clientRequests.poll();
    }

    public void addClientRequest(Transaction transaction) {
        clientRequests.add(transaction);
    }

    public Set<String> getExecutedTransactions() {
        return executionModule.getExecutedTransactions();
    }

    public void incrementEpoch() {
        this.epoch++;
    }

    public void resetEpoch() {
        this.epoch = 0;
    }
}
