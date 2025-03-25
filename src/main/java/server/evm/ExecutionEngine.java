package server.evm;

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
import org.hyperledger.besu.evm.*;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.tracing.StandardJsonTracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.consensus.exception.EvmExecutorException;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Set;

@Slf4j
public class ExecutionEngine {
    private static final Logger logger = LoggerFactory.getLogger(ExecutionEngine.class);
    private static final int BASE_NONCE = 0;
    private final SimpleWorld state;
    private final EVMExecutor evmExecutor;
    private final ByteArrayOutputStream executionOutputStream;

    public static String ALICE_ADDRESS = "deaddeaddeaddeaddeaddeaddeaddeaddeaddead";
    public static String BOB_ADDRESS = "beefbeefbeefbeefbeefbeefbeefbeefbeefbeef";
    public static String BLACKLIST_ADDRESS = "1234567891234567891234567891234567891234";
    public static String ISTCOIN_ADDRESS = "9876543219876543219876543219876543219876";

    private final Set<String> readFunctionIdentifiers;

    public ExecutionEngine() {
        this.state = new SimpleWorld();
        this.evmExecutor = new EVMExecutor(EvmSpecVersion.CANCUN);
        readFunctionIdentifiers = Set.of("70a08231", "313ce567", "06fdde03", "95d89b41", "18160ddd");
        this.executionOutputStream = new ByteArrayOutputStream();
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

    public boolean executeTransactions(List<Transaction> transactions) {
        for (Transaction transaction : transactions) {
            try {
                executeTransaction(transaction);
            } catch (EvmExecutorException e) {
                logger.error("Error executing transaction {} with calldata {}", transaction.id(), transaction.data(), e);
                return false;
            }
        }
        return true;
    }

    public String performOffChainOperation(Transaction transaction) {
        return null; //TODO
    }

    private boolean executeTransaction(Transaction transaction) throws EvmExecutorException {
        Address sender = Address.fromHexString(transaction.from());
        Address receiver = Address.fromHexString(transaction.to());
        Bytes callData = Bytes.fromHexString(transaction.data());
        Account contractAccount = state.getAccount(receiver);
        if (contractAccount == null) {
            throw new EvmExecutorException("Contract account not found for address: " + receiver);
        }
        Bytes code = contractAccount.getCode();
        evmExecutor.sender(sender);
        evmExecutor.receiver(receiver);
        evmExecutor.code(code);
        evmExecutor.contract(receiver);
        evmExecutor.messageFrameType(MessageFrame.Type.MESSAGE_CALL);
        evmExecutor.callData(callData);
        evmExecutor.execute();
        return extractBooleanFromReturnData(executionOutputStream);
    }

    private static String extractReturnData(ByteArrayOutputStream byteArrayOutputStream) {
        String[] lines = byteArrayOutputStream.toString().split("\\r?\\n");
        JsonObject jsonObject = JsonParser.parseString(lines[lines.length - 1]).getAsJsonObject();

        String memory = jsonObject.get("memory").getAsString();

        JsonArray stack = jsonObject.get("stack").getAsJsonArray();
        int offset = Integer.decode(stack.get(stack.size() - 1).getAsString());
        int size = Integer.decode(stack.get(stack.size() - 2).getAsString());

        return memory.substring(2 + offset * 2, 2 + offset * 2 + size * 2);
    }

    private static int extractIntegerFromReturnData(ByteArrayOutputStream byteArrayOutputStream) {
        String returnData = extractReturnData(byteArrayOutputStream);
        return Integer.decode("0x" + returnData);
    }

    private static boolean extractBooleanFromReturnData(ByteArrayOutputStream byteArrayOutputStream) {
        // Parse the last byte (in Solidity, booleans are typically the last byte)
        // We'll check if the last byte is non-zero (true) or zero (false)
        String returnData = extractReturnData(byteArrayOutputStream);
        String lastByte = returnData.substring(returnData.length() - 2);
        return !lastByte.equals("00");
    }
}
