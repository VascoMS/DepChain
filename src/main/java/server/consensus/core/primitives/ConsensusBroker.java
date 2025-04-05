package server.consensus.core.primitives;

import com.google.gson.Gson;
import common.model.Message;
import common.model.Transaction;
import common.primitives.AuthenticatedPerfectLink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.blockchain.Blockchain;
import server.blockchain.model.Block;
import server.consensus.core.model.ConsensusOutcomeDto;
import server.consensus.core.model.ConsensusPayload;
import server.consensus.test.ConsensusByzantineMode;
import util.*;
import util.Observer;
import util.Process;

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
    private final Blockchain blockchain;
    private final BlockingQueue<Transaction> mempool = new LinkedBlockingQueue<>();
    private final Map<Integer, Consensus> activeConsensusInstances = new HashMap<>();
    private final AtomicInteger currentConsensusRound = new AtomicInteger(0);
    private final List<Observer<ConsensusOutcomeDto>> consensusOutcomeObservers;
    private ConsensusByzantineMode byzantineMode;
    private Thread brokerThread;
    private final int minBlockSize;


    public ConsensusBroker(Process myProcess, Process[] peers, AuthenticatedPerfectLink link, int byzantineProcesses, KeyService keyService, Blockchain blockchain, int blockTime, int minBlockSize) {
        this.consensusMessageQueues = new ConcurrentHashMap<>();
        this.consensusOutcomeObservers = new ArrayList<>();
        this.myProcess = myProcess;
        this.peers = peers;
        this.blockTime = blockTime;
        this.link = link;
        this.blockchain = blockchain;
        this.totalEpochs = 0;
        this.byzantineProcesses = byzantineProcesses;
        this.keyService = keyService;
        this.byzantineMode = ConsensusByzantineMode.NORMAL;
        this.minBlockSize = minBlockSize;
        this.brokerThread = null;
        link.addObserver(this);
    }

    public synchronized void start() {
        link.start();
        if (brokerThread == null) {
            brokerThread = new Thread(this::brokerThreadLoop);
            brokerThread.start();
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
                    logger.info("{}: Consensus round {} already active", myProcess.getId(), currentRound);
                    consensus = activeConsensusInstances.get(currentRound);
                } else if(mempool.size() >= minBlockSize) {
                    Block proposal = buildBlock();
                    consensus = new Consensus(currentRound, proposal, this, myProcess, peers,
                            keyService, link, byzantineProcesses, totalEpochs, byzantineMode);
                } else {
                    logger.info("Not enough transactions in mempool to build block...");
                    sleepUntilNextBlock(currentTime);
                    continue;
                }

                consensusMessageQueues.putIfAbsent(currentRound, new LinkedBlockingQueue<>());
                activeConsensusInstances.putIfAbsent(currentRound, consensus);

                Future<Block> consensusFuture = executorService.submit(() ->
                        iAmLeader() ? consensus.runAsLeader() : consensus.runAsFollower()
                );

                Block decision = null;
                try {
                    decision = consensusFuture.get(blockTime, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    logger.warn("{}: Consensus round {} timed out", myProcess.getId(), currentRound);
                    consensusFuture.cancel(true); // Attempt to interrupt the consensus thread
                    if(consensus.isReturnedToMempool()) { // Rebuild block in case transactions were returned to mempool before timeout
                        Block proposal = buildBlock();
                        consensus.setProposal(proposal);
                    }
                }

                if (decision != null) {
                    cleanUpConsensus(currentRound);
                    currentConsensusRound.incrementAndGet();
                }

                notifyObservers(new ConsensusOutcomeDto(currentRound, decision));

                sleepUntilNextBlock(currentTime);

            } catch (Exception e) {
                logger.error("Error in server consensus broker: ", e);
            }
        }
    }

    private void sleepUntilNextBlock(long startTime) throws InterruptedException {
        long elapsedTime = System.currentTimeMillis() - startTime;

        if (elapsedTime < blockTime) {
            Thread.sleep(blockTime - elapsedTime);
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
        logger.info("{}: Received {} message from {}",
                myProcess.getId(), cPayload.getCType(), cPayload.getSenderId());
        if(currentConsensusRound.get() != cPayload.getConsensusId()) return; // TODO: Handle out of order messages better
        consensusMessageQueues.putIfAbsent(cPayload.getConsensusId(), new LinkedBlockingQueue<>());
        consensusMessageQueues.get(cPayload.getConsensusId()).add(cPayload);
    }

    private Block buildBlock() throws Exception {
        List<Transaction> transactions = new ArrayList<>();
        mempool.drainTo(transactions, 8);
        if(byzantineMode == ConsensusByzantineMode.CLIENT_SPOOFING) {
            logger.debug("{}: Byzantine - adding another unreceived request", myProcess.getId());
            String signature = SecurityUtil.signTransaction(
                    "deaddeaddeaddeaddeaddeaddeaddeaddeaddead",
                    "deaddeaddeaddeaddeaddeaddeaddeaddeaddead",
                    999,
                    null,
                    999,
                    keyService.loadPrivateKey(myProcess.getId())
            );
            Transaction spoofTransaction = new Transaction (
                    "deaddeaddeaddeaddeaddeaddeaddeaddeaddead",
                    "deaddeaddeaddeaddeaddeaddeaddeaddeaddead",
                    999,
                    null,
                    999,
                    signature
            );
            transactions.add(spoofTransaction);
        }
        return new Block (
                blockchain.getLastBlock().getBlockHash(),
                transactions,
                System.currentTimeMillis()
        );
    }

    // Returns transactions present in block that are not present in decided block.
    protected void returnTransactions(Block block, Block decidedBlock) {
        List<Transaction> transactionsToReturn;
        if (decidedBlock == null) {
            transactionsToReturn = block.getTransactions();
        } else {
            transactionsToReturn = block.getTransactions().stream().filter(
                    (transaction) -> !decidedBlock.getTransactions().contains(transaction)
            ).toList();

        }
        mempool.addAll(transactionsToReturn);
    }

    protected ConsensusPayload receiveConsensusMessage(int consensusId, long timeout) throws InterruptedException {
        return consensusMessageQueues.get(consensusId).poll(timeout, TimeUnit.MILLISECONDS);
    }

    public void addClientRequest(Transaction... transaction) {
        mempool.addAll(Arrays.stream(transaction).toList());
    }

    public void clearClientQueue() { mempool.clear(); }

    public void clearActiveInstances() { activeConsensusInstances.clear(); }

    public void clearMessageQueues() { consensusMessageQueues.clear(); }

    public synchronized boolean iAmLeader() {
        int myIndex = Integer.parseInt(myProcess.getId().substring(1));
        return this.totalEpochs % (peers.length + 1) == myIndex;
    }

    public void resetConsensusId() { this.currentConsensusRound.set(0); }

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
            logger.info("{}: Notifying observer of consensus round {} with result {}",
                    myProcess.getId(), outcome.id(), outcome.decision());
            observer.update(outcome);
        }
    }
}
