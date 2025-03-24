package server.consensus.core.primitives;

import com.google.gson.Gson;
import common.model.Transaction;
import common.model.Message;
import common.primitives.AuthenticatedPerfectLink;
import server.blockchain.model.Block;
import server.blockchain.model.Blockchain;
import server.consensus.test.ConsensusByzantineMode;
import server.evm.ExecutionEngine;
import server.evm.State;
import util.KeyService;
import server.consensus.core.model.*;
import server.consensus.exception.LinkException;
import util.Observer;
import util.Process;
import util.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static server.consensus.core.model.ConsensusPayload.ConsensusType.READ;

public class ConsensusBroker implements Observer<Message>, Subject<ConsensusOutcomeDto> {
    private static final Logger logger = LoggerFactory.getLogger(ConsensusBroker.class);
    private final int blockTime;
    private final ConcurrentHashMap<Integer, BlockingQueue<ConsensusPayload>> consensusMessageQueues;
    private final ConcurrentHashMap<Integer, CompletableFuture<Void>> senderFutures = new ConcurrentHashMap<>();
    private final Process myProcess;
    private final Process[] peers;
    private final AuthenticatedPerfectLink link;
    private int totalEpochs;
    private final int byzantineProcesses;
    private final KeyService keyService;
    private final BlockingQueue<Transaction> clientRequests = new LinkedBlockingQueue<>();
    private final Blockchain blockchain;
    private final Map<Integer, Consensus> activeConsensusInstances = new HashMap<>();
    private final AtomicInteger currentConsensusRound = new AtomicInteger(0);
    private long lastTimestamp;
    private final List<Observer<ConsensusOutcomeDto>> consensusOutcomeObservers;
    private ConsensusByzantineMode byzantineMode;

    public ConsensusBroker(Process myProcess, Process[] peers, AuthenticatedPerfectLink link, int byzantineProcesses, KeyService keyService, Blockchain blockchain, int blockTime) {
        this.consensusMessageQueues = new ConcurrentHashMap<>();
        this.consensusOutcomeObservers = new ArrayList<>();
        this.myProcess = myProcess;
        this.peers = peers;
        this.blockchain = blockchain;
        this.blockTime = 12000;
        this.link = link;
        this.totalEpochs = 0;
        this.byzantineProcesses = byzantineProcesses;
        this.keyService = keyService;
        this.byzantineMode = ConsensusByzantineMode.NORMAL;
        link.addObserver(this);
    }


    public void start() {
        //TODO: Add mechanism to abort round after blocktime
        this.lastTimestamp = (int) System.currentTimeMillis();
        new Thread (() -> {
            while(true) {
                try {
                    long currentTime = System.currentTimeMillis();
                    int currentRound = currentConsensusRound.incrementAndGet();
                    Consensus consensus = new Consensus(currentRound, this, myProcess, peers,
                            keyService, link, byzantineProcesses, totalEpochs, byzantineMode);
                    activeConsensusInstances.put(currentRound, consensus);
                    Block decision = iAmLeader() ? consensus.runAsLeader() : consensus.runAsFollower();
                    Thread.sleep(blockTime - (System.currentTimeMillis() - currentTime));
                    if(decision != null)
                        blockchain.addBlock(decision);
                    notifyObservers(new ConsensusOutcomeDto(currentRound, decision));
                } catch (Exception e) {
                    logger.error("Error in server consensus broker", e);
                }

            }
        }).start();
    }

    protected boolean validateBlock(Block block) {
        return blockchain.validateNextBlock(block);
    }

    @Override
    public void update(Message message) {
        if(message.getType() != Message.Type.CONSENSUS) return;
        ConsensusPayload cPayload = new Gson().fromJson(message.getPayload(), ConsensusPayload.class);
        logger.info("P{}: Received {} message from P{}",
                myProcess.getId(), cPayload.getCType(), cPayload.getSenderId());
        // If the server consensus round does not have a queue, create a new one
        if(needToCollect(cPayload.getConsensusId())) {
            consensusMessageQueues.putIfAbsent(cPayload.getConsensusId(), new LinkedBlockingQueue<>());
            int consensusRoundId = cPayload.getConsensusId();
            executor.execute(() -> {
                logger.info("P{}: Creating server.consensus instance round {}", myProcess.getId(), cPayload.getConsensusId());
                Consensus consensusRound;
                if(!activeConsensusInstances.containsKey(consensusRoundId)) {
                    consensusRound = new Consensus(
                            consensusRoundId, this, myProcess, peers,
                            keyService, link, byzantineProcesses, totalEpochs, byzantineMode
                    );
                    activeConsensusInstances.put(consensusRoundId, consensusRound);
                } else {
                    consensusRound = activeConsensusInstances.get(consensusRoundId);
                }
                try {
                    Transaction deliveredMessage = consensusRound.collect(consensusRoundId);
                    if(deliveredMessage != null) {
                        decidedMessages.add(deliveredMessage);
                        if(senderFutures.containsKey(consensusRoundId))
                            senderFutures.get(consensusRoundId).complete(null);
                        senderFutures.remove(consensusRoundId);
                        activeConsensusInstances.remove(consensusRoundId);
                        updateConsensusRound(consensusRoundId);
                        clientRequests.remove(deliveredMessage); // Remove the message from the client request queue to avoid duplicates
                    } else {
                        logger.info("P{}: Consensus round {} failed", myProcess.getId(), consensusRoundId);
                        consensusRound.changeLeader();
                    }
                    notifyObservers(new ConsensusOutcomeDto(consensusRoundId, deliveredMessage));
                } catch (Exception e) {
                    logger.error("P{}: Error collecting messages: {}", myProcess.getId(), e.getMessage());
                    if(senderFutures.containsKey(consensusRoundId))
                        senderFutures.get(consensusRoundId).completeExceptionally(e);
                } finally {
                    consensusMessageQueues.remove(consensusRoundId);
                }
            });
        }
        try {
            if(consensusMessageQueues.containsKey(cPayload.getConsensusId()))
                consensusMessageQueues.get(cPayload.getConsensusId()).put(cPayload);
        } catch(InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized void skipConsensusRound() {
        currentConsensusRound.incrementAndGet();
    }

    private synchronized void updateConsensusRound(int proposedRound) {
        if(proposedRound > currentConsensusRound.get())
            currentConsensusRound.set(proposedRound);
    }

    private boolean needToCollect(int consensusId) {
        return (consensusId > currentConsensusRound.get() && !activeConsensusInstances.containsKey(consensusId)) || abortedConsensus(consensusId);
    }

    private boolean abortedConsensus(int consensusId) {
        return activeConsensusInstances.containsKey(consensusId) && consensusMessageQueues.get(consensusId) == null;
    }


    private void buildBlocks() {

    }

    public Consensus getConsensusInstance(int consensusId) {
        return activeConsensusInstances.get(consensusId);
    }

    protected ConsensusPayload receiveConsensusMessage(int consensusId, long timeout) throws InterruptedException {
        return consensusMessageQueues.get(consensusId).poll(timeout, TimeUnit.MILLISECONDS);
    }

    protected Transaction fetchClientRequest() throws InterruptedException {
        return clientRequests.poll();
    }

    public void addClientRequest(Transaction transaction) {
        clientRequests.add(transaction);
        //clientRequests.notifyAll();
    }

    public void clearClientQueue() { clientRequests.clear(); }

    public synchronized boolean iAmLeader() { return this.totalEpochs % (peers.length + 1) == myProcess.getId(); }

    public synchronized boolean checkLeader(int epoch) { return epoch % (peers.length + 1) == myProcess.getId();}

    public void waitForTransaction(String transactionId) throws InterruptedException {
        executionEngine.waitForTransaction(transactionId);
    }

    protected synchronized void incrementEpoch() {
        this.totalEpochs++;
    }

    public synchronized void resetEpoch() { this.totalEpochs = 0; }

    public void returnToNormal() { this.byzantineMode = ConsensusByzantineMode.NORMAL;}

    public void becomeByzantine(ConsensusByzantineMode mode) { this.byzantineMode = mode; }

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
            logger.info("P{}: Notifying observer of consensus round {} with result {}",
                    myProcess.getId(), outcome.id(), outcome.decision());
            observer.update(outcome);
        }
    }
}
