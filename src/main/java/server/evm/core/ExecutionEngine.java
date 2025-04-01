package server.evm.core;

import com.google.gson.JsonObject;
import common.model.Transaction;
import server.evm.model.TransactionResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface ExecutionEngine {
    void executeTransactions(List<Transaction> transactions);
    CompletableFuture<TransactionResult> getTransactionFuture(String from, long nonce);
    TransactionResult performOffChainOperation(Transaction transaction);
    void initState(JsonObject state);
}
