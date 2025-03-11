package client;

import common.primitives.Link;

import java.util.Arrays;
import java.util.Scanner;

class ClientConsole {
    private final Scanner scanner;
    private final ClientOperations operations;

    public ClientConsole(Scanner scanner, ClientOperations operations) {
        this.scanner = scanner;
        this.operations = operations;
    }

    public void start() {
        while(true) {
            displayMenu();
            String input = scanner.nextLine();
            processCommand(input);
        }
    }

    private void displayMenu() {
        System.out.println("Choose an option:");
        System.out.println("append <value>");
        System.out.println("read");
        System.out.println("exit");
    }

    private void processCommand(String input) {
        String[] parts = input.split(" ");
        String command = parts[0];
        String[] args = Arrays.copyOfRange(parts, 1, parts.length);

        try {
            switch (command) {
                case "append" -> {
                    if (args.length != 1) {
                        System.out.println("Invalid number of arguments, provide a single value to be appended.");
                        return;
                    }
                    operations.append(args[0]);
                }
                case "read" -> {
                    if(args.length != 0) {
                        System.out.println("Invalid number of arguments, read command does not take any arguments.");
                        return;
                    }
                    String result = operations.read();
                    System.out.println(result);
                }
                case "exit" -> {
                    if (args.length != 0) {
                        System.out.println("Invalid number of arguments, exit command does not take any arguments.");
                        return;
                    }
                    System.exit(0);
                }
                default -> System.out.println("Invalid command, please try again.");
            }
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        }
    }
}
