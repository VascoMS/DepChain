package server.blockchain.model;

import common.model.Transaction;
import lombok.Getter;
import util.KeyService;
import util.MerkleTree;
import util.SecurityUtil;

import java.util.List;

@Getter
public class Block {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(Block.class);
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

    public String generateBlockHash() {
        List<String> transactionHashes = transactions.stream().map(Transaction::generateHash).toList();
        String merkleRoot = MerkleTree.getMerkleRoot(transactionHashes);
        byte[][] data = {
                parentHash.getBytes(),
                SecurityUtil.longToBytes(timestamp),
                merkleRoot != null ? merkleRoot.getBytes() : null,
        };
        return SecurityUtil.generateHash(data);
    }

    public boolean validateBlockTransactions(KeyService keyService, int minBlockSize) {
        if (transactions == null || transactions.size() < minBlockSize) {
            return false;
        }

        for (Transaction transaction : transactions) {
            try {
                String publicKeyId = transaction.from();
                if (!transaction.isValid(keyService.loadPublicKey(publicKeyId))) {
                    logger.warn("Invalid transaction from {}", transaction.from());
                    return false;
                }
            } catch (Exception e) {
                logger.error("Error verifying transaction signature for sender {}: {}",
                        transaction.from(), e.getMessage());
                return false;
            }
        }

        return true;
    }

    @Override
    public boolean equals(Object obj) {
        if(obj == this) return true;
        if(!(obj instanceof Block block)) return false;
        return block.blockHash.equals(this.blockHash);
    }
}
