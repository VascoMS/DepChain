package client.app;

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
        System.out.println("balance");
        System.out.println("transfer <recipient> <value>");
        System.out.println("add-blacklist <address>");
        System.out.println("remove-blacklist <address>");
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
                case "balance" -> {
                    if(args.length != 0) {
                        System.out.println("Invalid number of arguments, balance command does not take any arguments.");
                        return;
                    }
                    int balance = operations.balance();
                    System.out.println("Current balance: " + balance);
                }
                case "transfer" -> {
                    if(args.length != 2) {
                        System.out.println("Invalid number of arguments, " +
                                "provide the address of the receiver and the amount of tokens transfered.");
                        return;
                    }
                    try {
                        String recipientAddress = args[0];
                        int tokensTransferred = Integer.parseInt(args[1]);
                        if(tokensTransferred > 0) {
                            operations.transfer(recipientAddress, tokensTransferred);
                        } else {
                            System.out.println("Invalid token amount: must be a positive integer.");
                        }
                    } catch(NumberFormatException nfe) {
                        System.out.println("Invalid inputs: Address must be a hex string " +
                                "and tokens must be a positive integer");
                    }
                }
                case "add-blacklist" -> {
                    if(args.length != 1) {
                        System.out.println("Invalid number of arguments, " +
                                "provide the address of the one being blacklisted.");
                        return;
                    }
                    String blacklistAddress = args[0];
                    operations.addToBlacklist(blacklistAddress);
                }
                case "remove-blacklist" -> {
                    if(args.length != 1) {
                        System.out.println("Invalid number of arguments, " +
                                "provide the address of the one not being blacklisted.");
                        return;
                    }
                    String blacklistAddress = args[0];
                    operations.removeFromBlacklist(blacklistAddress);
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
