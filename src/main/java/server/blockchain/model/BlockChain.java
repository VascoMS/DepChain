package server.blockchain.model;

import server.consensus.core.primitives.ExecutionEngine;

import java.util.List;

public class BlockChain {
    // TODO: Implement genesis block parsing.
    private final List<Block> blockchain;
    private final ExecutionEngine executionEngine;

    public BlockChain(List<Block> blockchain) {
        this.blockchain = blockchain;
        this.executionEngine = null;
        //this.executionEngine = new ExecutionEngine();
    }
}
