package server.blockchain;

import com.google.gson.Gson;
import org.slf4j.Logger;
import server.blockchain.model.Block;
import server.blockchain.model.GenesisBlock;
import server.evm.core.ExecutionEngine;
import util.KeyService;

import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class BlockchainImpl implements Blockchain {
    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(BlockchainImpl.class);
    private final List<Block> blockchain;
    private final KeyService keyService;
    private final ExecutionEngine executionEngine;
    private final int minBlockSize;

    public BlockchainImpl(KeyService keyService, ExecutionEngine executionEngine, int minBlockSize) {
        this.blockchain = new ArrayList<>();
        this.keyService = keyService;
        this.executionEngine = executionEngine;
        this.minBlockSize = minBlockSize;
    }

    public void bootstrap(String genesisFilePath) {
        Gson gson = new Gson();
        try (FileReader reader = new FileReader(genesisFilePath)) {
            GenesisBlock genesisBlock = gson.fromJson(reader, GenesisBlock.class);
            blockchain.add(genesisBlock);
            executionEngine.initState(genesisBlock.getState());
        } catch (IOException e) {
            logger.error("Error reading genesis file: {}", e.getMessage());
        }
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
        String thisParentHash = block.getParentHash();
        String lastParentHash = lastBlock.getParentHash();

        // Validate parent hash
        if (!thisParentHash.equals(lastParentHash)) {
            logger.warn("Invalid parent hash: expected {}, got {}",
                    thisParentHash, lastParentHash);
            return false;
        }

        // Validate timestamp
        if (block.getTimestamp() > lastBlock.getTimestamp()) {
            logger.warn("Invalid timestamp: block timestamp {} not greater than parent timestamp {}",
                    block.getTimestamp(), lastBlock.getTimestamp());
            return false;
        }

        // Validate transactions
        if (!block.validateBlockTransactions(keyService, minBlockSize)) {
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

    public synchronized void addBlock(Block block) {
        logger.info("Adding block to blockchain: {}", block.getBlockHash());
        blockchain.add(block);
        executionEngine.executeTransactions(block.getTransactions());
    }

    public Block getLastBlock() {
        return blockchain.get(blockchain.size() - 1);
    }
}