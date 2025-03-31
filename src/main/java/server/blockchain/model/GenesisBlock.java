package server.blockchain.model;

import com.google.gson.JsonObject;
import common.model.Transaction;
import lombok.Getter;

import java.util.List;

@Getter
public class GenesisBlock extends Block{
    private final JsonObject state;

    public GenesisBlock(String parentHash, List<Transaction> transactions, int timestamp, JsonObject state) {
        super(parentHash, transactions, timestamp);
        this.state = state;
    }
}
