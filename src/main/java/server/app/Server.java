package server.app;

import common.primitives.AuthenticatedPerfectLink;
import common.primitives.LinkType;
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
        int basePort = Integer.parseInt(args[0]);
        String myId = args[1];
        int clientBasePort = Integer.parseInt(args[2]);
        int blockTime = 6000;
            Process[] processes = {
                    new Process("P0", "localhost", basePort),
                    new Process("P1", "localhost", basePort + 1),
                    new Process("P2", "localhost", basePort + 2),
                    new Process("P3", "localhost", basePort + 3)
            };
        Process[] clients = {
                new Process("deaddeaddeaddeaddeaddeaddeaddeaddeaddead", "localhost", clientBasePort),
                new Process("beefbeefbeefbeefbeefbeefbeefbeefbeefbeef", "localhost", clientBasePort + 1)
        };
        Process myProcess = Arrays.stream(processes).filter(process -> process.getId().equals(myId)).findFirst().get();
        try {
            KeyService keyService = new KeyService(SecurityUtil.SERVER_KEYSTORE_PATH, "mypass");
            Node node = new Node(basePort, myId, processes, blockTime, keyService);
            AuthenticatedPerfectLink clientLink = new AuthenticatedPerfectLink(
                    new Process(myProcess.getId(), myProcess.getHost(), myProcess.getPort() + 100),
                    clients,
                    LinkType.SERVER_TO_CLIENT, 100, SecurityUtil.SERVER_KEYSTORE_PATH);

            ClientRequestBroker broker = new ClientRequestBroker(myId, clientLink, node);
            broker.start();
        } catch(Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static int calculateByzantineFailures(int numberOfProcesses) {
        return (numberOfProcesses - 1) / 3;
    }
}
