package client;

import util.Process;
import java.util.Scanner;


public class ClientApp {

    public static void main(String[] args) {
        if(args.length < 3) {
            System.err.println("Argument(s) missing!");
            System.err.printf("Usage: java %s <port> <id>%n", ClientApp.class.getName());
            return;
        }

        int port = Integer.parseInt(args[0]);
        int myId = Integer.parseInt(args[1]);
        String defaultHost = "localhost";

        Scanner scanner = new Scanner(System.in);
        try {
            Process myProcess = new Process(myId, defaultHost, port);
            ClientConsole clientConsole = new ClientConsole(scanner, new ClientOperations(myProcess));
            clientConsole.start();
        } catch (Exception e) {
            System.err.println("An error occurred while starting the client application: " + e.getMessage());
            System.exit(1);
        }
    }
}