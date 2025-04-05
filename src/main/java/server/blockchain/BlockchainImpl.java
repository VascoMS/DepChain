package server.blockchain;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import server.blockchain.exception.BootstrapException;
import server.blockchain.model.Block;
import server.blockchain.model.GenesisBlock;
import server.evm.core.ExecutionEngine;
import util.KeyService;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class BlockchainImpl implements Blockchain { // TODO: Persist blockchain
    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(BlockchainImpl.class);
    private final List<Block> blockchain;
    private final KeyService keyService;
    private final ExecutionEngine executionEngine;
    private final int minBlockSize;
    private final String persistenceFilePath;

    public BlockchainImpl(KeyService keyService, ExecutionEngine executionEngine, int minBlockSize, String persistenceFilePath) {
        this.blockchain = new ArrayList<>();
        this.keyService = keyService;
        this.executionEngine = executionEngine;
        this.minBlockSize = minBlockSize;
        this.persistenceFilePath = persistenceFilePath;
    }

    public void bootstrap(String genesisFilePath) throws BootstrapException {
        Gson gson = new Gson();
        try (FileReader reader = new FileReader(genesisFilePath)) {
            GenesisBlock genesisBlock = gson.fromJson(reader, GenesisBlock.class);
            blockchain.add(genesisBlock);
            executionEngine.initState(genesisBlock.getState());
        } catch (IOException e) {
            logger.error("Error reading genesis file: {}", e.getMessage());
            throw new BootstrapException("Error reading genesis file", e);
        }
        Path blockchainPath = Paths.get(persistenceFilePath);
        if(!Files.exists(blockchainPath)){
            try {
                Files.createFile(blockchainPath);
                exportToJson();
            } catch (IOException e) {
                logger.error("Error creating blockchain file: {}", e.getMessage());
                throw new BootstrapException("Error creating blockchain file", e);
            }
        } else {
            List<Block> blocks = importFromJson().subList(1, blockchain.size()); // skip genesis block since it is already added
            for (Block block : blocks) {
                if (!validateNextBlock(block)) {
                    logger.error("Invalid block found in blockchain file: {}", block);
                    throw new BootstrapException("Invalid block found in blockchain file");
                }
                addBlock(block);
            }
        }
    }

    public boolean validateNextBlock(Block block) {
        if (block == null) {
            logger.error("Cannot validate null block");
            return false;
        }

        if (blockchain.isEmpty()) {
            logger.error("Cannot validate block for empty blockchain, bootstrap first");
            return false;
        }

        Block lastBlock = blockchain.get(blockchain.size() - 1);
        String thisParentHash = block.getParentHash();
        String lastBlockHash = lastBlock.getBlockHash();

        // Validate parent hash
        if (!thisParentHash.equals(lastBlockHash)) {
            logger.warn("Invalid parent hash: expected {}, got {}",
                    lastBlockHash, thisParentHash);
            return false;
        }

        // Validate timestamp
        if (block.getTimestamp() <= lastBlock.getTimestamp()) {
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
        logger.info("Block added successfully, persisting: {}", block.getBlockHash());
        exportToJson();
    }

    public void exportToJson(){
        Gson gson = new Gson();
        try (FileWriter writer = new FileWriter(persistenceFilePath)) {
            gson.toJson(blockchain, writer);
        } catch (IOException e) {
            logger.error("Error writing to JSON file: {}", e.getMessage());
        }
    }

    private List<Block> importFromJson() {
        Gson gson = new Gson();
        try (FileReader reader = new FileReader(persistenceFilePath)) {
            Type blockListType = new TypeToken<List<Block>>() {}.getType();
            return gson.fromJson(reader, blockListType);
        } catch (IOException e) {
            logger.error("Error reading from JSON file: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    public Block getLastBlock() {
        return blockchain.get(blockchain.size() - 1);
    }
}