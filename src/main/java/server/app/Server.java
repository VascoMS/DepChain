package server.app;

import server.consensus.core.model.State;
import server.consensus.core.model.StringState;
import server.consensus.core.primitives.ConsensusBroker;
import common.primitives.Link;
import util.KeyService;
import util.Process;
import util.SecurityUtil;

import java.util.Arrays;

public class Server {

    public static void main(String[] args) {
        // Print received arguments.
        System.out.printf("Received %d arguments%n", args.length);
        for (int i = 0; i < args.length; i++) {
            System.out.printf("arg[%d] = %s%n", i, args[i]);
        }

        // Check arguments.
        if (args.length < 3) {
            System.err.println("Argument(s) missing!");
            System.err.printf("Usage: java %s <baseport replica-id client-base-port%n", Server.class.getName());
            return;
        }
        State state = new StringState();
        int basePort = Integer.parseInt(args[0]);
        int myId = Integer.parseInt(args[1]);
        int clientBasePort = Integer.parseInt(args[2]);
            Process[] processes = {
                    new Process(0, "localhost", basePort),
                    new Process(1, "localhost", basePort + 1),
                    new Process(2, "localhost", basePort + 2),
                    new Process(3, "localhost", basePort + 3)
            };
        Process[] clients = {
                new Process(1, "localhost", clientBasePort)
        };
        Process myProcess = Arrays.stream(processes).filter(process -> process.getId() == myId).findFirst().get();
        Process[] peers = Arrays.stream(processes).filter(process -> process.getId() != myId).toArray(Process[]::new);
        try {
            Link processLink = new Link(myProcess, peers, 100, "p", "p", SecurityUtil.SERVER_KEYSTORE_PATH);
            ConsensusBroker consensusBroker = new ConsensusBroker(
                    myProcess,
                    peers,
                    processLink,
                    calculateByzantineFailures(processes.length),
                    new KeyService(SecurityUtil.SERVER_KEYSTORE_PATH, "mypass"),
                    state
            );
            processLink.addObserver(consensusBroker);

            Link clientLink = new Link(
                    new Process(myProcess.getId(), myProcess.getHost(), myProcess.getPort() + 100),
                    clients,
                    100,
                    "p",
                    "c",
                    SecurityUtil.SERVER_KEYSTORE_PATH
            );
            ClientRequestBroker clientRequestBroker = new ClientRequestBroker(
                    myProcess.getId(),
                    clientLink,
                    consensusBroker,
                    state
            );
            clientLink.addObserver(clientRequestBroker);
            processLink.waitForTermination();
            clientLink.waitForTermination();
        } catch(Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static int calculateByzantineFailures(int numberOfProcesses) {
        return (numberOfProcesses - 1) / 3;
    }

}
