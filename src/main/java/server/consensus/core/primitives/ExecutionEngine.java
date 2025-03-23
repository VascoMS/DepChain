package server.consensus.core.primitives;

import lombok.Setter;
import server.consensus.core.model.State;
import server.consensus.core.model.StringState;
import common.model.Transaction;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
public class ExecutionEngine {
    // TODO: Handle cases where received transactions don't have the desired precedence (Maybe do it in the consensus module)
    private static final Logger logger = LoggerFactory.getLogger(ExecutionEngine.class);
    private final State state;
    @Getter
    private final Set<String> executedTransactions;
    private final BlockingQueue<Transaction> queue;
    private final ExecutorService executor;

    private final Lock lock;

    @Getter
    private class WaitForExecutionRequest {
        private final Condition condition;
        @Setter
        private boolean done;

        private WaitForExecutionRequest(Condition condition) {
            this.condition = condition;
            this.done = false;
        }
    }

    private final HashMap<String, List<WaitForExecutionRequest>> requests;

    public ExecutionEngine(BlockingQueue<Transaction> transactionQueue) {
        this.state = new StringState();
        this.queue = transactionQueue;
        this.executedTransactions = new HashSet<>();
        this.executor = Executors.newSingleThreadExecutor();
        this.lock = new ReentrantLock();
        this.requests = new HashMap<>();
    }

    public ExecutionEngine(BlockingQueue<Transaction> transactionQueue, State state) {
        this.queue = transactionQueue;
        this.state = state;
        this.executedTransactions = new HashSet<>();
        this.executor = Executors.newSingleThreadExecutor();
        this.lock = new ReentrantLock();
        this.requests = new HashMap<>();
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
                    lock.lock();
                    state.applyTransaction(transaction);
                    executedTransactions.add(transaction.id());
                    List<WaitForExecutionRequest> waitingForTransactionRequests =
                            requests.getOrDefault(transaction.id(), new ArrayList<>());
                    for(WaitForExecutionRequest request: waitingForTransactionRequests) {
                        request.done = true;
                        request.getCondition().signal();
                    }
                } catch (InterruptedException e) {
                    logger.error("Error while executing transaction: ", e);
                } finally {
                    lock.unlock();
                }
            }
        });
    }

    public void waitForTransaction(String transactionId) throws InterruptedException {
        lock.lock();
        if(executedTransactions.contains(transactionId)) {
            lock.unlock();
            return;
        }
        WaitForExecutionRequest request = new WaitForExecutionRequest(lock.newCondition());
        requests.putIfAbsent(transactionId, new ArrayList<>());
        requests.get(transactionId).add(request);
        while(true) {
            try {
                request.condition.await();
                if(request.done) {
                    lock.unlock();
                    return;
                }
            } catch (InterruptedException e) {
                if(request.done) {
                    Thread.currentThread().interrupt();
                    lock.unlock();
                    return;
                } else { lock.unlock(); throw e; }
            }
        }
    }
}
