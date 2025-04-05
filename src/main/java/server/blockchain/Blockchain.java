package server.blockchain;

import server.blockchain.exception.BootstrapException;
import server.blockchain.model.Block;

public interface Blockchain {
    boolean validateNextBlock(Block block);
    void bootstrap(String genesisFilePath) throws BootstrapException;
    void addBlock(Block block);
    Block getLastBlock();
    void exportToJson();
}
