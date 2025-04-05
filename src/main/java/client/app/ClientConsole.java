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
        System.out.println();
        System.out.println("- Information -");
        System.out.println("info <name / symbol / decimals / owner / total>");
        System.out.println();
        System.out.println("- ISTCoin / DEPCoin -");
        System.out.println("balance <token>");
        System.out.println("transfer <recipient> <value> <token>");
        System.out.println();
        System.out.println("- ISTCoin Only -");
        System.out.println("allowance <address>");
        System.out.println("transfer-from <recipient> <value>");
        System.out.println("approve <recipient> <value>");
        System.out.println();
        System.out.println("- Blacklist Check -");
        System.out.println("am-i-blacklisted");
        System.out.println();
        System.out.println("- Owner Only -");
        System.out.println("add-blacklist <address>");
        System.out.println("remove-blacklist <address>");
        System.out.println("transfer-owner <address>");
        System.out.println("renounce-owner");
        System.out.println();
        System.out.println("- Exit -");
        System.out.println("exit");
    }

    private void processCommand(String input) {
        String[] parts = input.split(" ");
        String command = parts[0];
        String[] args = Arrays.copyOfRange(parts, 1, parts.length);

        try {
            switch (command) {
                case "info" -> {
                    if(args.length != 1) {
                        System.out.println("Invalid number of arguments, specify what information.");
                        return;
                    }
                    String dataType = args[0];
                    ClientOperations.Operations operation = ClientOperations.Operations.fromName(dataType);

                    if(operation == null) {
                        System.out.println("Invalid data. Must be name, symbol, decimals, owner or total");
                    } else {
                        String data = operations.getData(operation);
                        System.out.println(dataType + ": " + data);
                    }
                }
                case "balance" -> {
                    if(args.length != 1) {
                        System.out.println("Invalid number of arguments, specify currency type.");
                        return;
                    }
                    TokenType tokenType = TokenType.getTokenTypeFromSymbol(args[0]);
                    if(tokenType == null) {
                        System.out.println("Invalid token type. Must be either DEP or IST.");
                    } else {
                        int balance = operations.balance(tokenType);
                        System.out.println("Current balance: " + balance);
                    }
                }
                case "allowance" -> {
                    if(args.length != 1) {
                        System.out.println("Invalid number of arguments, provide a user address.");
                        return;
                    }
                    String address = args[0];
                    int allowance = operations.allowance(address);
                    System.out.println("Allowance of " + address + ": " + allowance);
                }
                case "transfer" -> {
                    if(args.length != 3) {
                        System.out.println("Invalid number of arguments, " +
                                "provide the address of the receiver, the amount of tokens and the token symbol.");
                        return;
                    }
                    try {
                        String recipientAddress = args[0];
                        int tokensTransferred = Integer.parseInt(args[1]);
                        TokenType tokenType = TokenType.getTokenTypeFromSymbol(args[2]);
                        if(tokenType == null) {
                            System.out.println("Invalid token type. Must be either DEP or IST.");
                        } else {
                            if(tokensTransferred > 0) {
                                operations.transfer(recipientAddress, tokensTransferred, tokenType);
                            } else {
                                System.out.println("Invalid token amount: must be a positive integer.");
                            }
                        }
                    } catch(NumberFormatException nfe) {
                        System.out.println("Invalid inputs: Address must be a hex string " +
                                "and tokens must be a positive integer");
                    }
                }
                case "transfer-from" -> {
                    if(args.length != 3) {
                        System.out.println("Invalid number of arguments, " +
                                "provide the address of the sender, receiver and the amount of tokens.");
                        return;
                    }
                    try {
                        String senderAddress = args[0];
                        String recipientAddress = args[1];
                        int tokensTransferred = Integer.parseInt(args[2]);
                        if(tokensTransferred > 0) {
                            operations.transferFrom(senderAddress, recipientAddress, tokensTransferred);
                        } else {
                            System.out.println("Invalid token amount: must be a positive integer.");
                        }
                    } catch(NumberFormatException nfe) {
                        System.out.println("Invalid inputs: Address must be a hex string " +
                                "and tokens must be a positive integer");
                    }
                }
                case "approve" -> {
                    if(args.length != 2) {
                        System.out.println("Invalid number of arguments, " +
                                "provide the address of the receiver and the amount of tokens.");
                        return;
                    }
                    try {
                        String recipientAddress = args[0];
                        int tokensAllowed = Integer.parseInt(args[1]);
                        if(tokensAllowed >= 0) {
                            operations.approve(recipientAddress, tokensAllowed);
                        } else {
                            System.out.println("Invalid token amount: cannot be a negative integer.");
                        }
                    } catch(NumberFormatException nfe) {
                        System.out.println("Invalid inputs: Address must be a hex string " +
                                "and tokens must be a positive integer");
                    }
                }
                case "am-i-blacklisted" -> {
                    if (args.length != 0) {
                        System.out.println("Invalid number of arguments, blacklisted command does not take any arguments.");
                        return;
                    }
                    boolean isBlacklisted = operations.isBlacklisted();
                    if(isBlacklisted) {
                        System.out.println("You are not blacklisted.");
                    } else {
                        System.out.println("You are blacklisted.");
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
                case "transfer-owner" -> {
                    if(args.length != 1) {
                        System.out.println("Invalid number of arguments, " +
                                "provide the address of the new owner.");
                        return;
                    }
                    String newOwnerAddress = args[0];
                    operations.transferOwnership(newOwnerAddress);
                }
                case "renounce-owner" -> {
                    if(args.length != 0) {
                        System.out.println("Invalid number of arguments, " +
                                "renounce-owner does not take arguments.");
                        return;
                    }
                    operations.renounceOwnership();
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
