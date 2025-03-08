package consensus.core.primitives;

import consensus.core.model.State;
import consensus.core.model.Transaction;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class ExecutionModule {
    private static final Logger logger = LoggerFactory.getLogger(ExecutionModule.class);
    private final State state;
    @Getter
    private final Set<String> executedTransactions;
    private final BlockingQueue<Transaction> queue;
    private final ExecutorService executor;

    public ExecutionModule(BlockingQueue<Transaction> transactionQueue) {
        this.state = new State();
        this.queue = transactionQueue;
        this.executedTransactions = new HashSet<>();
        this.executor = Executors.newSingleThreadExecutor();
    }

    public void start() {
        executor.execute(() -> {
            while(true) {
                try {
                    Transaction transaction = queue.take();
                    if(executedTransactions.contains(transaction.id())) {
                        logger.info("Transaction {} already executed", transaction.id());
                        continue;
                    }
                    state.applyTransaction(transaction);
                    executedTransactions.add(transaction.id());
                } catch (InterruptedException e) {
                    logger.error("Error while executing transaction: ", e);
                }
            }
        });
    }
}
