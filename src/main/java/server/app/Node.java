package server.app;

import common.model.ServerResponse;
import common.model.Transaction;
import common.primitives.AuthenticatedPerfectLink;
import common.primitives.LinkType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.blockchain.Blockchain;
import server.blockchain.BlockchainImpl;
import server.consensus.core.model.ConsensusOutcomeDto;
import server.consensus.core.primitives.ConsensusBroker;
import server.consensus.test.ConsensusByzantineMode;
import server.evm.core.ExecutionEngine;
import server.evm.core.ExecutionEngineImpl;
import server.evm.model.TransactionResult;
import util.*;
import util.Process;

import java.util.Arrays;

public class Node implements Observer<ConsensusOutcomeDto>, AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(Node.class);
    private final Process myProcess;
    private final Blockchain blockchain;
    private final ConsensusBroker consensusBroker;
    private final ExecutionEngine executionEngine;
    private final AuthenticatedPerfectLink processLink;
    private final KeyService keyService;
    public static final int MIN_BLOCK_SIZE = 4;
    public static final int BLOCK_TIME = 6000;
    public static final String GENESIS_BLOCK_PATH = "src/main/java/server/blockchain/resources/genesis.json";

    public Node(int basePort, String myId, Process[] processes, KeyService keyService) throws Exception {
        this.myProcess = new Process(myId, "localhost", basePort + Integer.parseInt(myId.substring(1)));
        Process[] peers = Arrays.stream(processes).filter(process -> !process.getId().equals(myId)).toArray(Process[]::new);
        this.executionEngine = new ExecutionEngineImpl();
        this.keyService = keyService;
        // Communication Links
        this.processLink = new AuthenticatedPerfectLink(
                myProcess, peers, LinkType.SERVER_TO_SERVER, 100, SecurityUtil.SERVER_KEYSTORE_PATH);
        // Blockchain Module
        blockchain = new BlockchainImpl(keyService, executionEngine, MIN_BLOCK_SIZE);
        // Consensus Module
        this.consensusBroker = new ConsensusBroker(
                myProcess, peers, processLink, calculateByzantineFailures(peers.length + 1),
                keyService, blockchain, BLOCK_TIME, MIN_BLOCK_SIZE);
    }

    public void becomeByzantine(ConsensusByzantineMode cbm) {
        consensusBroker.becomeByzantine(cbm);
    }

    public void bootstrap(String genesisBlockPath) {
        blockchain.bootstrap(genesisBlockPath);
    }

    public TransactionResult submitOffChainTransaction(Transaction transaction) {
        return executionEngine.performOffChainOperation(transaction);
    }

    public TransactionResult submitOnChainTransaction(Transaction transaction) throws Exception{
        logger.info("{}: Queueing transaction {} {} for consensus.", myProcess.getId(), transaction.from(), transaction.nonce());
        if(!validateTransaction(transaction))
            return TransactionResult.fail("Invalid transaction");
        consensusBroker.addClientRequest(transaction);
        try {
            return executionEngine.getTransactionResult(transaction.from(), transaction.nonce());
        } catch(Exception e) {
            logger.error("{}: Request handling interrupted: {}", myProcess.getId(), e.getMessage(), e);
            throw e;
        }
    }

    private boolean validateTransaction(Transaction transaction) throws Exception{
        return transaction != null
                && transaction.isValid(keyService.loadPublicKey(transaction.from()))
                && executionEngine.validateTransactionNonce(transaction);
    }

    private static int calculateByzantineFailures(int numberOfProcesses) {
        return (numberOfProcesses - 1) / 3;
    }


    public void start() {
        bootstrap(GENESIS_BLOCK_PATH);
        logger.info("Node {} started.", myProcess.getId());
        consensusBroker.addObserver(this);
        consensusBroker.start();
    }

    public void waitForTermination() {
        processLink.waitForTermination();
    }

    @Override
    public void update(ConsensusOutcomeDto message) {
        if(message.decision() != null) { // Node is responsible for adding the block to the blockchain
            blockchain.addBlock(message.decision());
        }
    }

    @Override
    public void close() throws Exception {
        processLink.close();
    }
}