package server.consensus.core.primitives;

import server.consensus.core.model.State;
import server.consensus.core.model.StringState;
import common.model.Transaction;
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
    // TODO: Handle cases where received transactions don't have the desired precedence (Maybe do it in the consensus module)
    private static final Logger logger = LoggerFactory.getLogger(ExecutionModule.class);
    private final State state;
    @Getter
    private final Set<String> executedTransactions;
    private final BlockingQueue<Transaction> queue;
    private final ExecutorService executor;

    public ExecutionModule(BlockingQueue<Transaction> transactionQueue) {
        this.state = new StringState();
        this.queue = transactionQueue;
        this.executedTransactions = new HashSet<>();
        this.executor = Executors.newSingleThreadExecutor();
    }

    public ExecutionModule(BlockingQueue<Transaction> transactionQueue, State state) {
        this.queue = transactionQueue;
        this.state = state;
        this.executedTransactions = new HashSet<>();
        this.executor = Executors.newSingleThreadExecutor();
    }

    public void start() {
        if(queue == null) {
            logger.error("Executor queue is not set");
            return;
        }
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
