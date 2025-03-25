package server.consensus.core.primitives;

import com.google.gson.Gson;
import common.model.Message;
import common.model.Transaction;
import common.primitives.AuthenticatedPerfectLink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.blockchain.model.Block;
import server.blockchain.model.Blockchain;
import server.consensus.core.model.ConsensusOutcomeDto;
import server.consensus.core.model.ConsensusPayload;
import server.consensus.test.ConsensusByzantineMode;
import util.KeyService;
import util.Observer;
import util.Process;
import util.Subject;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ConsensusBroker implements Observer<Message>, Subject<ConsensusOutcomeDto> {
    private static final Logger logger = LoggerFactory.getLogger(ConsensusBroker.class);
    private final int blockTime;
    private final ConcurrentHashMap<Integer, BlockingQueue<ConsensusPayload>> consensusMessageQueues;
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
    private final List<Observer<ConsensusOutcomeDto>> consensusOutcomeObservers;
    private ConsensusByzantineMode byzantineMode;
    private Thread brokerThread;

    public ConsensusBroker(Process myProcess, Process[] peers, AuthenticatedPerfectLink link, int byzantineProcesses, KeyService keyService, Blockchain blockchain, int blockTime) {
        this.consensusMessageQueues = new ConcurrentHashMap<>();
        this.consensusOutcomeObservers = new ArrayList<>();
        this.myProcess = myProcess;
        this.peers = peers;
        this.blockchain = blockchain;
        this.blockTime = blockTime;
        this.link = link;
        this.totalEpochs = 0;
        this.byzantineProcesses = byzantineProcesses;
        this.keyService = keyService;
        this.byzantineMode = ConsensusByzantineMode.NORMAL;
        this.brokerThread = null;
        link.addObserver(this);
    }

    public synchronized void start() {
        if (brokerThread == null) {
            brokerThread = new Thread(this::brokerThreadLoop);
        }
    }

    private void brokerThreadLoop() {
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        while (true) {
            try {
                long currentTime = System.currentTimeMillis();
                Consensus consensus;
                int currentRound = currentConsensusRound.intValue();

                if (activeConsensusInstances.containsKey(currentRound)) {
                    logger.info("P{}: Consensus round {} already active", myProcess.getId(), currentRound);
                    consensus = activeConsensusInstances.get(currentRound);
                } else {
                    Block proposal = buildBlock();
                    consensus = new Consensus(currentRound, proposal, this, myProcess, peers,
                            keyService, link, byzantineProcesses, totalEpochs, byzantineMode);
                }

                activeConsensusInstances.put(currentRound, consensus);

                Future<Block> consensusFuture = executorService.submit(() ->
                        iAmLeader() ? consensus.runAsLeader() : consensus.runAsFollower()
                );

                Block decision = null;
                try {
                    decision = consensusFuture.get(blockTime, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    logger.warn("P{}: Consensus round {} timed out", myProcess.getId(), currentRound);
                    consensusFuture.cancel(true); // Attempt to interrupt the consensus thread
                }

                if (decision != null) {
                    blockchain.addBlock(decision);
                    cleanUpConsensus(currentRound);
                    currentConsensusRound.incrementAndGet();
                }

                long elapsedTime = System.currentTimeMillis() - currentTime;

                if (elapsedTime < blockTime) {
                    Thread.sleep(blockTime - elapsedTime);
                }

                notifyObservers(new ConsensusOutcomeDto(currentRound, decision));
            } catch (Exception e) {
                logger.error("Error in server consensus broker: ", e);
            }
        }
    }

    public synchronized void stop() {
        brokerThread.interrupt();
        brokerThread = null;
    }

    private void cleanUpConsensus(int consensusRoundId) {
        activeConsensusInstances.remove(consensusRoundId);
        consensusMessageQueues.remove(consensusRoundId);
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
        if(currentConsensusRound.get() != cPayload.getConsensusId()) return;
        consensusMessageQueues.putIfAbsent(cPayload.getConsensusId(), new LinkedBlockingQueue<>());
        consensusMessageQueues.get(cPayload.getConsensusId()).add(cPayload);
    }

    public synchronized void skipConsensusRound() {
        currentConsensusRound.incrementAndGet();
    }

    private Block buildBlock() {
        List<Transaction> transactions = new ArrayList<>();
        clientRequests.drainTo(transactions);
        return new Block (
                blockchain.getLastBlock().getBlockHash(),
                transactions,
                System.currentTimeMillis()
        );
    }

    // Returns transactions present in block that are not present in decided block.
    protected void returnTransactions(Block block, Block decidedBlock) {
        List<Transaction> transactionsToReturn = block.getTransactions().stream().filter(
                (transaction) -> !decidedBlock.getTransactions().contains(transaction)
        ).toList();
        clientRequests.addAll(transactionsToReturn);
    }

    protected ConsensusPayload receiveConsensusMessage(int consensusId, long timeout) throws InterruptedException {
        return consensusMessageQueues.get(consensusId).poll(timeout, TimeUnit.MILLISECONDS);
    }

    public void addClientRequest(Transaction... transaction) {
        clientRequests.addAll(Arrays.stream(transaction).toList());
        //clientRequests.notifyAll();
    }

    public void clearClientQueue() { clientRequests.clear(); }

    public synchronized boolean iAmLeader() {
        int myIndex = Integer.parseInt(myProcess.getId().substring(1));
        return this.totalEpochs % (peers.length + 1) == myIndex;
    }

    public void waitForTransaction(String transactionId) throws InterruptedException {
        // executionEngine.waitForTransaction(transactionId);
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
