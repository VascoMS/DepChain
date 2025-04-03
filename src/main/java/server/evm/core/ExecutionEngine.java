package server.evm.core;

import com.google.gson.JsonObject;
import common.model.Transaction;
import server.evm.model.TransactionResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface ExecutionEngine {
    void executeTransactions(List<Transaction> transactions);
    TransactionResult getTransactionResult(String from, long nonce) throws Exception;
    TransactionResult performOffChainOperation(Transaction transaction);
    boolean validateTransactionNonce(Transaction transaction);
    void initState(JsonObject state);
}
