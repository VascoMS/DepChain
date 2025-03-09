package consensus.core.primitives;

import com.google.gson.Gson;
import consensus.core.KeyService;
import consensus.core.model.*;
import consensus.exception.LinkException;
import consensus.util.Observer;
import consensus.util.Process;
import consensus.util.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static consensus.core.model.ConsensusPayload.ConsensusType.READ;

public class ConsensusBroker implements Observer<Message>, Subject<ConsensusOutcomeDto> {
    // TODO: Add client request handling
    private static final Logger logger = LoggerFactory.getLogger(ConsensusBroker.class);
    private final ExecutorService executor;
    private final ConcurrentHashMap<Integer, BlockingQueue<ConsensusPayload>> consensusMessageQueues;
    private final BlockingQueue<Transaction> decidedMessages;
    private final ConcurrentHashMap<Integer, CompletableFuture<Void>> senderFutures = new ConcurrentHashMap<>();
    private final Process myProcess;
    private final Process[] peers;
    private final Link link;
    private final int byzantineProcesses;
    private final KeyService keyService;
    private int epoch;
    private final ConcurrentLinkedQueue<Transaction> clientRequests = new ConcurrentLinkedQueue<>();
    private final ExecutionModule executionModule;
    private final Map<Integer, Consensus> activeConsensusInstances = new HashMap<>();
    private final AtomicInteger currentConsensusRound = new AtomicInteger(1);
    private final List<Observer<ConsensusOutcomeDto>> consensusOutcomeObservers;


    public ConsensusBroker(Process myProcess, Process[] peers, Link link, int byzantineProcesses, KeyService keyService) {
        consensusMessageQueues = new ConcurrentHashMap<>();
        decidedMessages = new LinkedBlockingQueue<>();
        this.consensusOutcomeObservers = new ArrayList<>();
        this.myProcess = myProcess;
        this.peers = peers;
        this.executor = Executors.newFixedThreadPool(10);
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
        logger.info("P{}: Received {} message from P{}",
                myProcess.getId(), cPayload.getCType(), cPayload.getSenderId());
        // If the consensus round does not have a queue, create a new one
        BlockingQueue<?> oldQueue = consensusMessageQueues.putIfAbsent(cPayload.getConsensusId(), new LinkedBlockingQueue<>());
        if(oldQueue == null) {
            executor.execute(() -> {
                currentConsensusRound.set(cPayload.getConsensusId());
                Consensus consensusRound;
                if(!activeConsensusInstances.containsKey(cPayload.getConsensusId())) {
                    consensusRound = new Consensus(cPayload.getConsensusId(), this, myProcess, peers, keyService, link, epoch, byzantineProcesses);
                    activeConsensusInstances.put(consensusRound.getId(), consensusRound);
                } else {
                    consensusRound = activeConsensusInstances.get(cPayload.getConsensusId());
                    consensusRound.clearEpochState();
                }
                try {
                    Transaction deliveredMessage = consensusRound.collect(cPayload.getConsensusId());
                    if(deliveredMessage != null) {
                        decidedMessages.add(deliveredMessage);
                        if(senderFutures.containsKey(cPayload.getConsensusId()))
                            senderFutures.get(cPayload.getConsensusId()).complete(null);
                        senderFutures.remove(cPayload.getConsensusId());
                        activeConsensusInstances.remove(consensusRound.getId());
                    } else {
                        logger.info("Consensus round {} failed", cPayload.getConsensusId());
                    }
                    notifyObservers(new ConsensusOutcomeDto(cPayload.getConsensusId(), deliveredMessage));
                } catch (Exception e) {
                    logger.error("P{}: Error collecting messages: {}", myProcess.getId(), e.getMessage());
                    if(senderFutures.containsKey(cPayload.getConsensusId()))
                        senderFutures.get(cPayload.getConsensusId()).completeExceptionally(e);
                } finally {
                    consensusMessageQueues.remove(cPayload.getConsensusId());
                }
            });
        }
        try {
            consensusMessageQueues.get(cPayload.getConsensusId()).put(cPayload);
        } catch(InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public CompletableFuture<Void> startConsensus() throws LinkException {
        int myId = myProcess.getId();
        int consensusId = currentConsensusRound.getAndIncrement();
        ConsensusPayload cPayload = new ConsensusPayload(myId, consensusId, READ, null, keyService);
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

    public Consensus getConsensusInstance(int consensusId) {
        return activeConsensusInstances.get(consensusId);
    }

    protected ConsensusPayload receiveConsensusMessage(int consensusId) throws InterruptedException {
        return consensusMessageQueues.get(consensusId).take();
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

    @Override
    public void addObserver(Observer<ConsensusOutcomeDto> observer) {
        consensusOutcomeObservers.add(observer);
    }

    @Override
    public void removeObserver(Observer<ConsensusOutcomeDto> observer) {
        consensusOutcomeObservers.remove(observer);
    }

    @Override
    public void notifyObservers(ConsensusOutcomeDto outcome) {
        for (Observer<ConsensusOutcomeDto> observer : consensusOutcomeObservers) {
            logger.info("P{}: Notifying observer of consensus {} with result {}",
                    myProcess.getId(), outcome.id(), outcome.decision());
            observer.update(outcome);
        }
    }
}
