package server.blockchain.model;

import lombok.Getter;

@Getter
public class GenesisConfig {
    private final String parentHash;
    private final String stateRoot;
    private final int timestamp;

    public GenesisConfig(String genesisFilePath) {
        this.parentHash = null;

    }
}
