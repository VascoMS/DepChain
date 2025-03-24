package server.blockchain.model;

import common.model.Transaction;
import lombok.Getter;

import java.util.List;

@Getter
public class BlockWithState extends Block{
    private final String state;

    public BlockWithState(String parentHash, List<Transaction> transactions, int timestamp, String state) {
        super(parentHash, transactions, timestamp);
        this.state = state;
    }
}
