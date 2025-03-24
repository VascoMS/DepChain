package server.app;

import common.primitives.AuthenticatedPerfectLink;
import common.primitives.LinkType;
import server.blockchain.model.Blockchain;
import server.evm.State;
import server.evm.StringState;
import server.consensus.core.primitives.ConsensusBroker;
//import server.blockchain.model.Blockchain;
import server.evm.ExecutionEngine;
//import server.execution.ExecutionEngine;
import util.KeyService;
import util.Process;
import util.SecurityUtil;

import java.util.ArrayList;

public class Node {
    private final Process myProcess;
    private final Process[] peers;
    private final ConsensusBroker consensusBroker;
    private final Blockchain blockchain;
    //private final ExecutionEngine executionEngine;
    private final ClientRequestBroker clientRequestBroker;
    private final AuthenticatedPerfectLink processLink;
    private final AuthenticatedPerfectLink clientLink;

    public Node(int basePort, int myId, int clientBasePort, Process[] peers) throws Exception {
        this.myProcess = new Process(myId, "localhost", basePort + myId);
        this.peers = peers;

        // State management
        State state = new StringState();

        // Communication Links
        this.processLink = new AuthenticatedPerfectLink(
                myProcess, peers, LinkType.SERVER_TO_SERVER, 100, "p", "p", SecurityUtil.SERVER_KEYSTORE_PATH);
        this.clientLink = new AuthenticatedPerfectLink(
                new Process(myProcess.getId(), myProcess.getHost(), myProcess.getPort() + 100),
                new Process[]{new Process(1, "localhost", clientBasePort)},
                LinkType.SERVER_TO_CLIENT, 100, "p", "c", SecurityUtil.SERVER_KEYSTORE_PATH);

        // Consensus Module
        this.consensusBroker = new ConsensusBroker(
                myProcess, peers, processLink, calculateByzantineFailures(peers.length + 1),
                new KeyService(SecurityUtil.SERVER_KEYSTORE_PATH, "mypass"), state);

        // Blockchain Module
        this.blockchain = new Blockchain(new ArrayList<>());

        // Execution Module
        //this.executionEngine = new ExecutionEngine();

        // Client Request Handling
        this.clientRequestBroker = new ClientRequestBroker(myProcess.getId(), clientLink, consensusBroker, state);
    }

    private static int calculateByzantineFailures(int numberOfProcesses) {
        return (numberOfProcesses - 1) / 3;
    }

    public void start() {
        // Start network links
        System.out.println("Node " + myProcess.getId() + " started.");
        processLink.start();
        clientLink.start();
        processLink.waitForTermination();
        clientLink.waitForTermination();
    }
}