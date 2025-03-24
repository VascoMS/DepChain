package server.blockchain.model;

import common.model.Transaction;
import org.slf4j.Logger;
import util.KeyService;

import java.util.List;

public class Blockchain {
    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(Blockchain.class);
    // TODO: Implement genesis block parsing.
    private final List<Block> blockchain;
    private final KeyService keyService;
    //private final ExecutionEngine executionEngine;

    public Blockchain(List<Block> blockchain, KeyService keyService) {
        this.blockchain = blockchain;
        this.keyService = keyService;
        //this.executionEngine = new ExecutionEngine();
    }

    public boolean validateNextBlock(Block block) {
        if (block == null) {
            logger.error("Cannot validate null block");
            return false;
        }

        if (blockchain.isEmpty()) {
            logger.error("Cannot validate block for empty blockchain");
            return false;
        }

        Block lastBlock = blockchain.get(blockchain.size() - 1);

        // Validate parent hash
        if (!block.getParentHash().equals(lastBlock.getBlockHash())) {
            logger.warn("Invalid parent hash: expected {}, got {}",
                    lastBlock.getBlockHash(), block.getParentHash());
            return false;
        }

        // Validate timestamp
        if (block.getTimestamp() > lastBlock.getTimestamp()) {
            logger.warn("Invalid timestamp: block timestamp {} not greater than parent timestamp {}",
                    block.getTimestamp(), lastBlock.getTimestamp());
            return false;
        }

        // Validate transactions
        if (!validateBlockTransactions(block)) {
            return false;
        }

        // Validate block hash
        String expectedHash = block.generateBlockHash();
        if (!block.getBlockHash().equals(expectedHash)) {
            logger.warn("Invalid block hash: expected {}, got {}",
                    expectedHash, block.getBlockHash());
            return false;
        }

        return true;
    }

    private boolean validateBlockTransactions(Block block) {
        if (block.getTransactions() == null || block.getTransactions().isEmpty()) {
            return true; // No transactions to validate
        }

        for (Transaction transaction : block.getTransactions()) {
            try {
                String publicKeyId = "c" + transaction.from();
                if (!transaction.verifySignature(keyService.loadPublicKey(publicKeyId))) {
                    logger.warn("Invalid signature for transaction from {}", transaction.from());
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

    public synchronized void addBlock(Block block) {
        blockchain.add(block);
    }

    public synchronized Block getLastBlock() {
        return blockchain.get(blockchain.size() - 1);
    }
}