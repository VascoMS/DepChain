package server.blockchain.model;

import java.util.List;

public class Blockchain {
    // TODO: Implement genesis block parsing.
    private final List<Block> blockchain;
    //private final ExecutionEngine executionEngine;

    public Blockchain(List<Block> blockchain) {
        this.blockchain = blockchain;
        this.executionEngine = null;
        //this.executionEngine = new ExecutionEngine();
    }
}
