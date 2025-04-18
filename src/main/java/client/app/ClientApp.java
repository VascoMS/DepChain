package client.app;

import util.Process;

import java.util.Scanner;


public class ClientApp {

    public static void main(String[] args) {
        if(args.length < 3) {
            System.err.println("Argument(s) missing!");
            System.err.printf("Usage: java %s <client-port> <address> <server-base-port>%n", ClientApp.class.getName());
            return;
        }

        int port = Integer.parseInt(args[0]);
        String address = args[1];
        int serverBasePort = Integer.parseInt(args[2]);
        String defaultHost = "localhost";

        Scanner scanner = new Scanner(System.in);
        try {
            Process myProcess = new Process(address, defaultHost, port);
            Process[] servers = {
                    new Process("p0", defaultHost, serverBasePort),
                    new Process("p1", defaultHost, serverBasePort + 1),
                    new Process("p2", defaultHost, serverBasePort + 2),
                    new Process("p3", defaultHost, serverBasePort + 3),
            };
            ClientConsole clientConsole = new ClientConsole(scanner, new ClientOperations(myProcess, servers));
            clientConsole.start();
        } catch (Exception e) {
            System.err.println("An error occurred while starting the client application: " + e.getMessage());
            System.exit(1);
        }
    }
}