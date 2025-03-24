package server.blockchain.model;

import common.model.Transaction;
import lombok.Getter;
import util.MerkleTree;
import util.SecurityUtil;

import java.util.List;

@Getter
public class Block {
    private final String parentHash;
    private final String blockHash;
    private final long timestamp;
    private final List<Transaction> transactions;

    public Block(String parentHash, List<Transaction> transactions, long timestamp) {
        this.parentHash = parentHash;
        this.transactions = transactions;
        this.timestamp = timestamp;
        this.blockHash = generateBlockHash();
    }

    protected String generateBlockHash() {
        List<String> transactionHashes = transactions.stream().map(Transaction::generateHash).toList();
        byte[][] data = {
                parentHash.getBytes(),
                SecurityUtil.longToBytes(timestamp),
                MerkleTree.getMerkleRoot(transactionHashes).getBytes(),
        };
        return SecurityUtil.generateHash(data);
    }
}
