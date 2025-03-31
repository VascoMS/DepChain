package server.blockchain.model;

import common.model.Transaction;
import lombok.Getter;

import java.util.List;

@Getter
public class GenesisBlock extends Block{
    private final String state;

    public GenesisBlock(String parentHash, List<Transaction> transactions, int timestamp, String state) {
        super(parentHash, transactions, timestamp);
        this.state = state;
    }
}
