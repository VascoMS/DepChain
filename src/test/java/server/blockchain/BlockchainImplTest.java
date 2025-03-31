package server.blockchain;

import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import server.blockchain.model.Block;
import server.blockchain.model.GenesisBlock;
import server.evm.core.ExecutionEngine;
import util.KeyService;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BlockchainImplTest {

    @Mock
    private KeyService keyService;

    @Mock
    private ExecutionEngine executionEngine;

    @Mock
    private Block mockBlock;

    @Mock
    private GenesisBlock mockGenesisBlock;

    private BlockchainImpl blockchain;
    private final int MIN_BLOCK_SIZE = 5;
    private Path tempGenesisFile;

    @BeforeEach
    void setUp() throws IOException {
        blockchain = new BlockchainImpl(keyService, executionEngine, MIN_BLOCK_SIZE);

        // Create a temporary file for the genesis block
        tempGenesisFile = Files.createTempFile("genesis", ".json");
        GenesisBlock genesisBlock = new GenesisBlock();
        genesisBlock.setBlockHash("genesis-hash");
        genesisBlock.setTimestamp(Instant.now().getEpochSecond());
        genesisBlock.setState("{}");

        try (FileWriter writer = new FileWriter(tempGenesisFile.toFile())) {
            new Gson().toJson(genesisBlock, writer);
        }
    }

    @Test
    void bootstrap_shouldAddGenesisBlockToBlockchain() {
        // When
        blockchain.bootstrap(tempGenesisFile.toString());

        // Then
        Block lastBlock = blockchain.getLastBlock();
        assertNotNull(lastBlock);
        assertTrue(lastBlock instanceof GenesisBlock);
        verify(executionEngine).initState(any());
    }

    @Test
    void bootstrap_shouldHandleIOException() throws IOException {
        // Given
        String nonExistentFile = "non-existent-file.json";

        // When
        blockchain.bootstrap(nonExistentFile);

        // Then - should not throw exception, just log error
        // We can't directly test logs, but we can verify the blockchain remains empty
        assertThrows(IndexOutOfBoundsException.class, () -> blockchain.getLastBlock());
    }

    @Test
    void validateNextBlock_shouldReturnFalse_whenBlockIsNull() {
        // Given
        blockchain.bootstrap(tempGenesisFile.toString());

        // When
        boolean result = blockchain.validateNextBlock(null);

        // Then
        assertFalse(result);
    }

    @Test
    void validateNextBlock_shouldReturnFalse_whenBlockchainIsEmpty() {
        // When
        boolean result = blockchain.validateNextBlock(mockBlock);

        // Then
        assertFalse(result);
    }

    @Test
    void validateNextBlock_shouldValidateParentHash() {
        // Given
        blockchain.bootstrap(tempGenesisFile.toString());
        Block lastBlock = blockchain.getLastBlock();

        when(mockBlock.getParentHash()).thenReturn("wrong-hash");

        // When
        boolean result = blockchain.validateNextBlock(mockBlock);

        // Then
        assertFalse(result);
        verify(mockBlock).getParentHash();
    }

    @Test
    void validateNextBlock_shouldValidateTimestamp() {
        // Given
        blockchain.bootstrap(tempGenesisFile.toString());
        Block lastBlock = blockchain.getLastBlock();

        when(mockBlock.getParentHash()).thenReturn(lastBlock.getBlockHash());
        when(mockBlock.getTimestamp()).thenReturn(lastBlock.getTimestamp() + 1);

        // When
        boolean result = blockchain.validateNextBlock(mockBlock);

        // Then
        assertFalse(result);
        verify(mockBlock).getTimestamp();
    }

    @Test
    void validateNextBlock_shouldValidateTransactions() {
        // Given
        blockchain.bootstrap(tempGenesisFile.toString());
        Block lastBlock = blockchain.getLastBlock();

        when(mockBlock.getParentHash()).thenReturn(lastBlock.getBlockHash());
        when(mockBlock.getTimestamp()).thenReturn(lastBlock.getTimestamp() - 1);
        when(mockBlock.validateBlockTransactions(keyService, MIN_BLOCK_SIZE)).thenReturn(false);

        // When
        boolean result = blockchain.validateNextBlock(mockBlock);

        // Then
        assertFalse(result);
        verify(mockBlock).validateBlockTransactions(keyService, MIN_BLOCK_SIZE);
    }

    @Test
    void validateNextBlock_shouldValidateBlockHash() {
        // Given
        blockchain.bootstrap(tempGenesisFile.toString());
        Block lastBlock = blockchain.getLastBlock();

        when(mockBlock.getParentHash()).thenReturn(lastBlock.getBlockHash());
        when(mockBlock.getTimestamp()).thenReturn(lastBlock.getTimestamp() - 1);
        when(mockBlock.validateBlockTransactions(keyService, MIN_BLOCK_SIZE)).thenReturn(true);
        when(mockBlock.generateBlockHash()).thenReturn("expected-hash");
        when(mockBlock.getBlockHash()).thenReturn("actual-hash");

        // When
        boolean result = blockchain.validateNextBlock(mockBlock);

        // Then
        assertFalse(result);
        verify(mockBlock).generateBlockHash();
        verify(mockBlock).getBlockHash();
    }

    @Test
    void validateNextBlock_shouldReturnTrue_whenBlockIsValid() {
        // Given
        blockchain.bootstrap(tempGenesisFile.toString());
        Block lastBlock = blockchain.getLastBlock();

        when(mockBlock.getParentHash()).thenReturn(lastBlock.getBlockHash());
        when(mockBlock.getTimestamp()).thenReturn(lastBlock.getTimestamp() - 1);
        when(mockBlock.validateBlockTransactions(keyService, MIN_BLOCK_SIZE)).thenReturn(true);
        when(mockBlock.generateBlockHash()).thenReturn("valid-hash");
        when(mockBlock.getBlockHash()).thenReturn("valid-hash");

        // When
        boolean result = blockchain.validateNextBlock(mockBlock);

        // Then
        assertTrue(result);
    }

    @Test
    void addBlock_shouldAddBlockToBlockchain() {
        // Given
        blockchain.bootstrap(tempGenesisFile.toString());
        when(mockBlock.getBlockHash()).thenReturn("new-block-hash");
        when(mockBlock.getTransactions()).thenReturn(Collections.emptyList());

        // When
        blockchain.addBlock(mockBlock);

        // Then
        assertEquals(mockBlock, blockchain.getLastBlock());
        verify(executionEngine).executeTransactions(any());
    }

    @Test
    void getLastBlock_shouldReturnLastBlock() throws Exception {
        // Given
        blockchain.bootstrap(tempGenesisFile.toString());
        Block genesisBlock = blockchain.getLastBlock();

        when(mockBlock.getBlockHash()).thenReturn("new-block-hash");
        when(mockBlock.getTransactions()).thenReturn(Collections.emptyList());
        blockchain.addBlock(mockBlock);

        // When
        Block lastBlock = blockchain.getLastBlock();

        // Then
        assertEquals(mockBlock, lastBlock);
        assertNotEquals(genesisBlock, lastBlock);
    }

    @Test
    void concurrentAddBlocks_shouldMaintainBlockchainIntegrity() throws Exception {
        // Given
        blockchain.bootstrap(tempGenesisFile.toString());
        int numThreads = 10;
        List<Block> mockBlocks = new ArrayList<>();

        for (int i = 0; i < numThreads; i++) {
            Block block = mock(Block.class);
            when(block.getBlockHash()).thenReturn("block-hash-" + i);
            when(block.getTransactions()).thenReturn(Collections.emptyList());
            mockBlocks.add(block);
        }

        // When
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < numThreads; i++) {
            final int index = i;
            Thread thread = new Thread(() -> blockchain.addBlock(mockBlocks.get(index)));
            threads.add(thread);
            thread.start();
        }

        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }

        // Then
        // Get the blockchain field using reflection
        Field blockchainField = BlockchainImpl.class.getDeclaredField("blockchain");
        blockchainField.setAccessible(true);
        List<Block> internalBlockchain = (List<Block>) blockchainField.get(blockchain);

        // The blockchain should contain the genesis block plus all the mock blocks
        assertEquals(numThreads + 1, internalBlockchain.size());
        verify(executionEngine, times(numThreads)).executeTransactions(any());
    }
}
