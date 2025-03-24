package server.evm;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import org.hyperledger.besu.evm.fluent.SimpleWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.blockchain.model.BlockWithState;

import java.io.FileReader;
import java.io.IOException;

@Slf4j
public class ExecutionEngine {
    private static final Logger logger = LoggerFactory.getLogger(ExecutionEngine.class);
    SimpleWorld state = new SimpleWorld();

    public ExecutionEngine() {

    }

    public void initState(String genesisFilePath) {
        try (FileReader reader = new FileReader(genesisFilePath)) {
            BlockWithState genesisBlock = new Gson().fromJson(reader, BlockWithState.class);
        } catch (IOException e) {
            logger.error("Error reading genesis file: {}", e.getMessage());
            return;
        }
    }
}
