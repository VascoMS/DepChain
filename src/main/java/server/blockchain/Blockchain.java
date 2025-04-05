package server.blockchain;

import server.blockchain.model.Block;

public interface Blockchain {
    boolean validateNextBlock(Block block);
    void bootstrap(String genesisFilePath);
    void addBlock(Block block);
    Block getLastBlock();
    void exportToJson();
}
