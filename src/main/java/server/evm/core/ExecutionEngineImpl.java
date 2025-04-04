package server.evm.core;

import com.google.gson.*;
import common.model.Transaction;
import common.model.TransactionKey;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.fluent.EVMExecutor;
import org.hyperledger.besu.evm.fluent.SimpleWorld;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.tracing.StandardJsonTracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.evm.model.TransactionResult;
import server.evm.util.EvmMetadataUtils;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class ExecutionEngineImpl implements ExecutionEngine {
    private static final Logger logger = LoggerFactory.getLogger(ExecutionEngineImpl.class);
    private static final int BASE_NONCE = 0;
    private final SimpleWorld state;
    private final EVMExecutor evmExecutor;
    private final ByteArrayOutputStream executionOutputStream;

    private final ConcurrentHashMap<TransactionKey, CompletableFuture<TransactionResult>> transactionFutures;

    private final Set<String> readFunctionIdentifiers;

    public ExecutionEngineImpl() {
        logger.info("Initializing ExecutionEngine with Cancun EVM spec");
        this.state = new SimpleWorld();

        this.evmExecutor = EVMExecutor.evm(EvmSpecVersion.CANCUN);
        readFunctionIdentifiers = Set.of("70a08231", "313ce567", "06fdde03", "95d89b41", "18160ddd");
        logger.debug("Configured read function identifiers: {}", readFunctionIdentifiers);

        this.executionOutputStream = new ByteArrayOutputStream();
        this.transactionFutures = new ConcurrentHashMap<>();
        PrintStream printStream = new PrintStream(executionOutputStream);
        StandardJsonTracer tracer = new StandardJsonTracer(printStream, true, true, true, true);
        evmExecutor.tracer(tracer);
        evmExecutor.commitWorldState();
        logger.info("ExecutionEngine initialized successfully");
    }

    public void initState(JsonObject state) {
        logger.info("Initializing state from JSON object with {} accounts", state.keySet().size());
        parseAndApplyState(state);
        logger.info("State initialization completed");
    }

    private void parseAndApplyState(JsonObject json) {
        for (String address : json.keySet()) {
            try {
                logger.debug("Processing account state for address: {}", address);
                processAccountState(address, json.getAsJsonObject(address));
            } catch (Exception e) {
                logger.error("Error processing account for address {}: {}", address, e.getMessage(), e);
            }
        }
    }

    private void processAccountState(String addressHex, JsonObject accountJson) {
        Address accountAddress = Address.fromHexString(addressHex);

        int balance = extractBalance(accountJson);
        logger.debug("Setting balance for account {}: {} ETH", addressHex, balance);

        state.createAccount(accountAddress, BASE_NONCE, Wei.fromEth(balance));

        MutableAccount account = state.getAccount(accountAddress);

        extractAndSetCode(accountJson, account);

        extractAndSetStorage(accountJson, account);
        logger.debug("Account state processing completed for: {}", addressHex);
    }

    private int extractBalance(JsonObject accountJson) {
        return accountJson.has("balance")
                ? accountJson.get("balance").getAsInt()
                : 0;
    }

    private void extractAndSetCode(JsonObject accountJson, MutableAccount account) {
        if (accountJson.has("code")) {
            String code = accountJson.get("code").getAsString();
            logger.debug("Setting code for account {}", account.getAddress().toHexString());
            account.setCode(Bytes.fromHexString(code));
        }
    }

    private void extractAndSetStorage(JsonObject accountJson, MutableAccount account) {
        if (accountJson.has("storage")) {
            JsonObject storageJson = accountJson.getAsJsonObject("storage");
            logger.debug("Setting {} storage slots for account", storageJson.keySet().size());
            for (String slotMapping : storageJson.keySet()) {
                try {
                    UInt256 slot = UInt256.fromHexString(slotMapping);
                    JsonElement slotValue = storageJson.get(slotMapping);
                    UInt256 value = parseStorageValue(slotValue);
                    account.setStorageValue(slot, value);
                } catch (Exception e) {
                    logger.error("Error setting storage for slot {}: {}", slotMapping, e.getMessage(), e);
                }
            }
        }
    }

    private UInt256 parseStorageValue(JsonElement valueElement) {
        if (valueElement.isJsonPrimitive() && valueElement.getAsJsonPrimitive().isString()) {
            return UInt256.fromHexString(valueElement.getAsString());
        }
        throw new IllegalArgumentException("Unsupported storage value type");
    }

    public void executeTransactions(List<Transaction> transactions) {
        logger.info("Executing batch of {} transactions", transactions.size());
        for (Transaction transaction : transactions) {
            logger.debug("Processing transaction from: {} Nonce: {}", transaction.from(), transaction.nonce());
            TransactionResult result = executeOnChain(transaction);
            logger.debug("Transaction {} {} completed with status: {}", transaction.from(),transaction.nonce(), result.isSuccess() ? "SUCCESS" : "FAIL");
            getTransactionFuture(transaction.from(), transaction.nonce()).complete(result);
        }
        logger.info("Batch execution completed");
    }

    private CompletableFuture<TransactionResult> getTransactionFuture(String from, long nonce) {
        TransactionKey transactionKey = new TransactionKey(from, nonce);
        logger.debug("Getting future for transaction: {}", transactionKey);
        transactionFutures.putIfAbsent(transactionKey, new CompletableFuture<>());
        return transactionFutures.get(transactionKey);
    }

    public TransactionResult getTransactionResult(String from, long nonce) throws Exception {
        TransactionKey transactionKey = new TransactionKey(from, nonce);
        CompletableFuture<TransactionResult> future = getTransactionFuture(from, nonce);
        TransactionResult result = future.get();
        transactionFutures.remove(transactionKey);
        return result;
    }

    public TransactionResult performOffChainOperation(Transaction transaction) {
        logger.info("Performing off-chain operation for transaction: {} {}", transaction.from(), transaction.nonce());
        String callData = transaction.data();
        if(!validateOffChainOperation(transaction)) {
            return TransactionResult.fail("Invalid off-chain operation...");
        }
        if(callData == null) {
            logger.debug("Null calldata - reading DEPCOIN balance for address: {}", transaction.from());
            String address = transaction.from();
            long balance = readNativeCurrencyBalance(address);
            logger.info("DEPCOIN balance read for {}: {}", address, balance);
            return TransactionResult.success(String.valueOf(balance));
        }
        logger.debug("Executing offchain read operation...");
        return executeOffChain(transaction);
    }

    private boolean validateOffChainOperation(Transaction transaction) {
        if(transaction == null) {
            logger.warn("Null transaction received for off-chain operation");
            return false;
        } else if(transaction.value() != 0) {
            logger.warn("Invalid value for off-chain operation: {}", transaction.value());
            return false;
        } else if(transaction.data() != null) {
            String functionId = transaction.data().substring(0, 8);
            if(!readFunctionIdentifiers.contains(functionId)) {
                logger.warn("Invalid function identifier for off-chain operation: {}", functionId);
                return false;
            }
        }
        return true;
    }

    private long readNativeCurrencyBalance(String address) {
        logger.debug("Reading native currency balance for: {}", address);
        Account account = state.getAccount(Address.fromHexString(address));
        long balance = account != null
                ? account.getBalance().getAsBigInteger().divide(BigInteger.TEN.pow(18)).longValue()
                : 0;
        logger.debug("Native balance for {}: {}", address, balance);
        return balance;
    }

    public boolean validateTransactionNonce(Transaction transaction) {
        Account sender = state.getAccount(Address.fromHexString(transaction.from()));
        long currentStoredNonce = sender.getNonce();
        return transaction.nonce() > currentStoredNonce;
    }

    private TransactionResult executeOffChain(Transaction transaction) {
         return executeTransaction(transaction, false);
    }
    private TransactionResult executeOnChain(Transaction transaction) {
        return executeTransaction(transaction, true);
    }

    private synchronized TransactionResult executeTransaction(Transaction transaction, boolean setNonce) {
        logger.info("Executing transaction {} from: {} to: {}", transaction.nonce(), transaction.from(), transaction.to());
        Address sender = Address.fromHexString(transaction.from());
        Address receiver = Address.fromHexString(transaction.to());

        Bytes callData = null;
        if (transaction.data() != null && !transaction.data().isEmpty()) {
            callData = Bytes.fromHexString(transaction.data());
            logger.debug("Transaction calldata: 0x{}", transaction.data());
        } else {
            logger.debug("Transaction has no calldata");
        }

        MutableAccount receiverAccount  = state.getAccount(receiver);
        if (receiverAccount == null) {
            logger.error("Receiver account not found at address: {}", receiver);
            return TransactionResult.fail("Receiver account not found");
        }

        Bytes code = receiverAccount.getCode();

        evmExecutor.sender(sender);
        evmExecutor.receiver(receiver);
        evmExecutor.code(code);
        evmExecutor.messageFrameType(MessageFrame.Type.MESSAGE_CALL);

        if(callData != null) {
            evmExecutor.callData(callData);
        }

        if(setNonce && !validateTransactionNonce(transaction)) {
            logger.warn("Invalid nonce for transaction: {} from: {}", transaction.nonce(), transaction.from());
            return TransactionResult.fail("Invalid nonce");
        }

        Wei ethValue = Wei.fromEth(transaction.value());
        logger.debug("Transaction value: {} DEP", transaction.value());
        evmExecutor.ethValue(ethValue);
        logger.debug("Starting EVM execution");
        executionOutputStream.reset();
        TransactionResult result;
        try {
            evmExecutor.worldUpdater(state.updater());
            evmExecutor.execute();
            MutableAccount senderAccount = state.getAccount(sender);
            logger.info("Sender account nonce updated to: {}", senderAccount.getNonce());
            logger.debug("EVM execution completed");
            if(isContract(receiverAccount)) {
                String error = getError(executionOutputStream);
                if (error != null) {
                    logger.warn("Error executing transaction {} {}: {}", transaction.from(), transaction.nonce(), error);
                    String errorMessage = parseError(error);
                    logger.info("Parsed error message: {}", errorMessage);
                    result = TransactionResult.fail(errorMessage);
                } else {
                    String resultOutput = parseEvmOutput(executionOutputStream, transaction.data());
                    logger.info("Transaction {} {} executed successfully with result: {}", transaction.from(), transaction.nonce(), resultOutput);
                    result = TransactionResult.success(resultOutput);
                }
            } else {
                result = TransactionResult.success("DEP Transfer executed successfully");
            }
        } catch (IllegalStateException e) {
            logger.warn("EVM execution failed: {}", e.getMessage(), e);
            result = TransactionResult.fail("Insufficient balance.");
        } finally {
            if(setNonce) {
                MutableAccount senderAccount = state.getAccount(sender);
                logger.debug("Sender previous nonce: {}", senderAccount.getNonce());
                logger.debug("Setting nonce for sender: {}", transaction.nonce());
                senderAccount.setNonce(transaction.nonce());
            }
        }
        return result;
    }

    public static String getError(ByteArrayOutputStream byteArrayOutputStream) {
        String output = byteArrayOutputStream.toString();
        String[] lines = output.split("\\r?\\n");
        if (lines.length == 0) {
            return null;
        }

        JsonObject jsonObject = JsonParser.parseString(lines[lines.length - 1]).getAsJsonObject();
        return jsonObject.get("error") != null ? jsonObject.get("error").getAsString() : null;
    }

    public static String parseError(String error) {
        if (error == null || error.length() < 10) {
            return "Unknown error";
        }
        String errorSignature = error.substring(2, 10);
        logger.debug("Parsing error with signature: {}", errorSignature);
        return switch (errorSignature) {
            case "e450d38c" -> // ERC20InsufficientBalance(address,uint256,uint256)
                    "ERC20 Insufficient Balance for: " + extractParameter(error, 0) +
                            " Balance: " + extractParameter(error, 1)
                            + " Needed: " + extractParameter(error, 2);
            case "96c6fd1e" -> // ERC20InvalidSender(address)
                    "ERC20 Invalid Sender: " + extractParameter(error, 0);
            case "ec442f05" -> // ERC20InvalidReceiver(address)
                    "ERC20 Invalid Receiver: " + extractParameter(error, 0);
            case "ea558bdf" -> // ERC20InsufficientAllowance(address, uint256, uint256)
                    "ERC20 Insufficient Allowance: " + extractParameter(error, 0) +
                            " Allowance: " + extractParameter(error, 1)
                            + " Needed: " + extractParameter(error, 2);
            case "94280d62" -> // ERC20InvalidSpender(address)
                    "ERC20 Invalid Spender: " + extractParameter(error, 0);
            case "118cdaa7" -> // OwnableUnauthorizedAccount(address)
                    "Ownable Unauthorized Account: " + extractParameter(error, 0);
            case "1e4fbdf7" -> // OwnableInvalidOwner(address)
                    "Ownable Invalid Owner: " + extractParameter(error, 0);
            case "ffa4e618" -> // Blacklisted(address)
                    "Blacklisted Account: " + extractParameter(error, 0);
            default -> "Unknown Error: " + errorSignature;
        };
    }

    private static String extractParameter(String errorData, int index) {
        return "0x" + trimLeadingZeros(errorData.substring(10 + 64 * index, 10 + 64 * (index + 1)));
    }

    public static String trimLeadingZeros(String hexString) {
        if (hexString.startsWith("0x")) {
            hexString = hexString.substring(2);
        }

        hexString = hexString.replaceFirst("^0+", "");

        return hexString.isEmpty() ? "0" : hexString;
    }

    private static String extractReturnData(ByteArrayOutputStream byteArrayOutputStream) {
        String output = byteArrayOutputStream.toString();
        String[] lines = output.split("\\r?\\n");
        if (lines.length == 0) {
            logger.warn("No output lines found from EVM execution");
            return "";
        }

        JsonObject jsonObject = JsonParser.parseString(lines[lines.length - 1]).getAsJsonObject();

        String memory = jsonObject.get("memory").getAsString();

        JsonArray stack = jsonObject.get("stack").getAsJsonArray();
        int offset = Integer.decode(stack.get(stack.size() - 1).getAsString());
        int size = Integer.decode(stack.get(stack.size() - 2).getAsString());

        logger.debug("Extracting return data from memory - offset: {}, size: {}", offset, size);
        return memory.substring(2 + offset * 2, 2 + offset * 2 + size * 2);
    }

    private static int extractIntegerFromReturnData(ByteArrayOutputStream byteArrayOutputStream) {
        String returnData = extractReturnData(byteArrayOutputStream);
        return Integer.decode("0x" + returnData);
    }

    private static String parseEvmOutput(ByteArrayOutputStream byteArrayOutputStream, String calldata) {
        String returnData = extractReturnData(byteArrayOutputStream);
        logger.debug("Parsing EVM output from return data: 0x{}", returnData);
        String returnType = EvmMetadataUtils.getMethodReturnType(calldata);
        if (returnType.equals("bool")) {
            boolean result = extractBooleanFromReturnData(byteArrayOutputStream);
            logger.debug("Return data interpreted as boolean: {}", result);
            return Boolean.toString(result);
        } else {
            int intValue = extractIntegerFromReturnData(byteArrayOutputStream);
            logger.debug("Return data interpreted as integer: {}", intValue);
            return Integer.toString(intValue);
        }

    }

    private static boolean extractBooleanFromReturnData(ByteArrayOutputStream byteArrayOutputStream) {
        // Parse the last byte (in Solidity, booleans are typically the last byte)
        // We'll check if the last byte is non-zero (true) or zero (false)
        String returnData = extractReturnData(byteArrayOutputStream);
        String lastByte = returnData.substring(returnData.length() - 2);
        boolean result = !lastByte.equals("00");
        logger.debug("Extracted boolean value from return data: {}", result);
        return result;
    }

    private boolean isContract(MutableAccount account) {
        return account.getCode() != null && !account.getCode().isEmpty();
    }
}