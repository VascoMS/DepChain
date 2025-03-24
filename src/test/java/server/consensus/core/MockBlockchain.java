package server.consensus.core;

import server.blockchain.model.Block;
import server.blockchain.model.Blockchain;
import util.KeyService;

import java.util.List;

public class MockBlockchain extends Blockchain {

    public MockBlockchain(KeyService keyService) {
        super(List.of(new Block("", List.of(), 0)), keyService);
    }

}
