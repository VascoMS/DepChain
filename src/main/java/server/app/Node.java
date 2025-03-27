package server.app;

import common.model.Transaction;
import common.primitives.AuthenticatedPerfectLink;
import common.primitives.LinkType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.blockchain.Blockchain;
import server.blockchain.BlockchainImpl;
import server.consensus.core.model.ConsensusOutcomeDto;
import server.consensus.core.primitives.ConsensusBroker;
import server.evm.core.ExecutionEngine;
import server.evm.core.ExecutionEngineImpl;
import server.evm.model.TransactionResult;
import util.KeyService;
import util.Observer;
import util.Process;
import util.SecurityUtil;

import java.util.Arrays;

public class Node implements Observer<ConsensusOutcomeDto> {
    private static final Logger logger = LoggerFactory.getLogger(Node.class);
    private final Process myProcess;
    private final Blockchain blockchain;
    private final ConsensusBroker consensusBroker;
    private final ExecutionEngine executionEngine;
    private final AuthenticatedPerfectLink processLink;

    public Node(int basePort, String myId, Process[] processes, int blockTime, KeyService keyService) throws Exception {
        this.myProcess = new Process(myId, "localhost", basePort + Integer.parseInt(myId.substring(1)));
        Process[] peers = Arrays.stream(processes).filter(process -> !process.getId().equals(myId)).toArray(Process[]::new);
        this.executionEngine = new ExecutionEngineImpl();
        // Communication Links
        this.processLink = new AuthenticatedPerfectLink(
                myProcess, peers, LinkType.SERVER_TO_SERVER, 100, SecurityUtil.SERVER_KEYSTORE_PATH);
        // Blockchain Module
        blockchain = new BlockchainImpl(keyService, executionEngine);
        // Consensus Module
        this.consensusBroker = new ConsensusBroker(
                myProcess, peers, processLink, calculateByzantineFailures(peers.length + 1),
                keyService, blockchain, blockTime);
    }

    public void bootstrap(String genesisFilePath) {
        blockchain.bootstrap(genesisFilePath);
    }

    public TransactionResult submitOffChainTransaction(Transaction transaction) {
        return executionEngine.performOffChainOperation(transaction);
    }

    public TransactionResult submitOnChainTransaction(Transaction transaction) throws Exception{
        logger.info("P{}: Queueing transaction {} for consensus.", myProcess.getId(), transaction.id());
        consensusBroker.addClientRequest(transaction);
        try {
            return executionEngine.getTransactionFuture(transaction.id()).get();
            // consensusBroker.waitForTransaction(transaction.id());
        } catch(Exception e) {
            logger.error("P{}: Request handling interrupted: {}", myProcess.getId(), e.getMessage(), e);
            throw e;
        }
    }

    private static int calculateByzantineFailures(int numberOfProcesses) {
        return (numberOfProcesses - 1) / 3;
    }

    private void start() {
        // Start network links
        System.out.println("Node " + myProcess.getId() + " started.");
        consensusBroker.addObserver(this);
        processLink.start();
        processLink.waitForTermination();
    }

    public void start(String genesisFilePath) {
        bootstrap(genesisFilePath);
        start();
    }

    // TODO: Maybe add catch up start if we have time

    @Override
    public void update(ConsensusOutcomeDto message) {
        if(message.decision() != null) {
            blockchain.addBlock(message.decision());
        }
    }
}