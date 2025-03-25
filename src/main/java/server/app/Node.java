package server.app;

import common.model.Transaction;
import common.primitives.AuthenticatedPerfectLink;
import common.primitives.LinkType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.blockchain.model.Blockchain;
import server.consensus.core.primitives.ConsensusBroker;
import server.evm.ExecutionEngine;
import util.KeyService;
import util.Process;
import util.SecurityUtil;

import java.util.Arrays;

public class Node {
    private static final Logger logger = LoggerFactory.getLogger(Node.class);
    private final Process myProcess;
    private final Process[] peers;
    private final ConsensusBroker consensusBroker;
    private final Blockchain blockchain;
    private final ExecutionEngine executionEngine;
    private final AuthenticatedPerfectLink processLink;

    public Node(int basePort, String myId, Process[] processes, int blockTime, KeyService keyService) throws Exception {
        this.myProcess = new Process(myId, "localhost", basePort + Integer.parseInt(myId.substring(1)));
        this.peers = Arrays.stream(processes).filter(process -> !process.getId().equals(myId)).toArray(Process[]::new);
        this.executionEngine = new ExecutionEngine();
        // Communication Links
        this.processLink = new AuthenticatedPerfectLink(
                myProcess, peers, LinkType.SERVER_TO_SERVER, 100, SecurityUtil.SERVER_KEYSTORE_PATH);
        // Blockchain Module
        this.blockchain = new Blockchain(keyService, executionEngine);

        // Consensus Module
        this.consensusBroker = new ConsensusBroker(
                myProcess, peers, processLink, calculateByzantineFailures(peers.length + 1),
                keyService, blockchain, blockTime);
    }

    public void submitOffChainTransaction(Transaction transaction) {
        executionEngine.performOffChainOperation(transaction);
    }

    public void submitOnChainTransaction(Transaction transaction) {
        logger.info("P{}: Queueing transaction {} for consensus.", myProcess.getId(), transaction.id());
        consensusBroker.addClientRequest(transaction);
        try {
            consensusBroker.waitForTransaction(transaction.id());
        } catch(InterruptedException e) {
            logger.error("P{}: Request handling interrupted: {}", myProcess.getId(), e.getMessage(), e);
        }
    }

    private static int calculateByzantineFailures(int numberOfProcesses) {
        return (numberOfProcesses - 1) / 3;
    }

    public void start() {
        // Start network links
        System.out.println("Node " + myProcess.getId() + " started.");
        processLink.start();
        processLink.waitForTermination();
    }
}