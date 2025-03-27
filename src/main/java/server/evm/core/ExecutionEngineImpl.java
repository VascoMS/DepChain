package server.evm.core;

import com.google.gson.*;
import common.model.Transaction;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.fluent.EVMExecutor;
import org.hyperledger.besu.evm.fluent.SimpleWorld;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.tracing.StandardJsonTracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.evm.model.TransactionResult;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
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

    private final ConcurrentHashMap<String, CompletableFuture<TransactionResult>> transactionFutures;

    public static String ALICE_ADDRESS = "deaddeaddeaddeaddeaddeaddeaddeaddeaddead";
    public static String BOB_ADDRESS = "beefbeefbeefbeefbeefbeefbeefbeefbeefbeef";
    public static String BLACKLIST_ADDRESS = "1234567891234567891234567891234567891234";
    public static String ISTCOIN_ADDRESS = "9876543219876543219876543219876543219876";

    private final Set<String> readFunctionIdentifiers;

    public ExecutionEngineImpl() {
        this.state = new SimpleWorld();

        this.evmExecutor = EVMExecutor.evm(EvmSpecVersion.CANCUN);
        readFunctionIdentifiers = Set.of("70a08231", "313ce567", "06fdde03", "95d89b41", "18160ddd");
        this.executionOutputStream = new ByteArrayOutputStream();
        this.transactionFutures = new ConcurrentHashMap<>();
        PrintStream printStream = new PrintStream(executionOutputStream);
        StandardJsonTracer tracer = new StandardJsonTracer(printStream, true, true, true, true);
        evmExecutor.tracer(tracer);
        evmExecutor.worldUpdater(state.updater());
        evmExecutor.commitWorldState();
    }

    public void initState(String state) {
        JsonObject json = new Gson().fromJson(state, JsonObject.class);
        parseAndApplyState(json);
    }

    private void parseAndApplyState(JsonObject json) {
        for (String address : json.keySet()) {
            try {
                processAccountState(address, json.getAsJsonObject(address));
            } catch (Exception e) {
                logger.error("Error processing account for address {}", address, e);
            }
        }
    }

    private void processAccountState(String addressHex, JsonObject accountJson) {
        Address accountAddress = Address.fromHexString(addressHex);

        int balance = extractBalance(accountJson);

        state.createAccount(accountAddress, BASE_NONCE, Wei.fromEth(balance));

        MutableAccount account = state.getAccount(accountAddress);

        extractAndSetCode(accountJson, account);

        extractAndSetStorage(accountJson, account);
    }

    private int extractBalance(JsonObject accountJson) {
        return accountJson.has("balance")
                ? accountJson.get("balance").getAsInt()
                : 0;
    }

    private void extractAndSetCode(JsonObject accountJson, MutableAccount account) {
        if (accountJson.has("code")) {
            String code = accountJson.get("code").getAsString();
            account.setCode(Bytes.fromHexString(code));
        }
    }

    private void extractAndSetStorage(JsonObject accountJson, MutableAccount account) {
        if (accountJson.has("storage")) {
            JsonObject storageJson = accountJson.getAsJsonObject("storage");

            for (String slotMapping : storageJson.keySet()) {
                try {
                    UInt256 slot = UInt256.fromHexString(slotMapping);
                    UInt256 value = parseStorageValue(storageJson.get(slotMapping));
                    account.setStorageValue(slot, value);
                } catch (Exception e) {
                    logger.error("Error setting storage for slot {}", slotMapping, e);
                }
            }
        }
    }

    private UInt256 parseStorageValue(JsonElement valueElement) {
        if (valueElement.isJsonPrimitive()) {
            if (valueElement.getAsJsonPrimitive().isNumber()) {
                return UInt256.valueOf(valueElement.getAsLong());
            } else if (valueElement.getAsJsonPrimitive().isString()) {
                return UInt256.fromHexString(valueElement.getAsString());
            }
        }
        throw new IllegalArgumentException("Unsupported storage value type");
    }

    public void executeTransactions(List<Transaction> transactions) {
        for (Transaction transaction : transactions) {
            TransactionResult result = executeTransaction(transaction);
            getTransactionFuture(transaction.id()).complete(result);
        }
    }

    public CompletableFuture<TransactionResult> getTransactionFuture(String transactionId) {
        transactionFutures.putIfAbsent(transactionId, new CompletableFuture<>());
        return transactionFutures.get(transactionId);
    }

    public TransactionResult performOffChainOperation(Transaction transaction){
        String callDataPrefix = transaction.data().substring(0, 8);
        if(readFunctionIdentifiers.contains(callDataPrefix)) {
            return null; //TODO

        }
        return TransactionResult.success(); //TODO: Add read result
    }

    private TransactionResult executeTransaction(Transaction transaction) {
        logger.info("Executing transaction: {}", transaction);
        Address sender = Address.fromHexString(transaction.from());
        Address receiver = Address.fromHexString(transaction.to());
        Bytes callData = Bytes.fromHexString(transaction.data());
        Account contractAccount = state.getAccount(receiver);
        if (contractAccount == null) {
            return TransactionResult.fail("Contract account not found");
        }
        Bytes code = contractAccount.getCode();
        evmExecutor.sender(sender);
        evmExecutor.receiver(receiver);
        evmExecutor.code(code);
        evmExecutor.contract(receiver);
        evmExecutor.messageFrameType(MessageFrame.Type.MESSAGE_CALL);
        evmExecutor.callData(callData);
        evmExecutor.execute();

        String error = getError(executionOutputStream);
        if(error != null) {
            logger.info("Error executing transaction: {}", error);
            String errorMessage = parseError(error);
            return TransactionResult.fail(errorMessage);
        }
        return TransactionResult.success();
    }

    public static String getError(ByteArrayOutputStream byteArrayOutputStream) {
        String[] lines = byteArrayOutputStream.toString().split("\\r?\\n");
        JsonObject jsonObject = JsonParser.parseString(lines[lines.length - 1]).getAsJsonObject();
        return jsonObject.get("error").getAsString();
    }

    public static String parseError(String error) {
        String errorSignature = error.substring(0, 8);
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

}
