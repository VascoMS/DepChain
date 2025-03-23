package server.blockchain.model;

import common.model.Transaction;
import lombok.Getter;

import java.util.List;

@Getter
public class Block {
    private final String parentHash;
    private final String blockHash;
    private final List<Transaction> transactions;
    private final String stateRoot;
    private final int timestamp;

    public Block(String parentHash, List<Transaction> transactions, String stateRoot, int timestamp) {
        this.parentHash = parentHash;
        this.transactions = transactions;
        this.stateRoot = stateRoot;
        this.timestamp = timestamp;
        this.blockHash = "TODO: Implement block hash calculation.";
    }
}
