package consensus.core;

import com.google.gson.Gson;
import consensus.util.Process;
import consensus.exception.LinkException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class ReliableBroadcast {

    private static final Logger logger = LoggerFactory.getLogger(ReliableBroadcast.class);
    private final Process myProcess;
    private final Process[] peers;
    private final Link link;
    private final AtomicBoolean sentEcho;
    private final AtomicBoolean sentReady;
    private final AtomicBoolean delivered;
    private final ConcurrentHashMap<Integer, String> echos;
    private final ConcurrentHashMap<Integer, String> readys;
    private final int byzantineProcesses;

    public ReliableBroadcast(Process myProcess, Process[] peers, Link link, int byzantineProcesses) throws LinkException {
        this.myProcess = myProcess;
        this.peers = peers;
        this.link = link;
        this.sentEcho = new AtomicBoolean(false);
        this.sentReady = new AtomicBoolean(false);
        this.delivered = new AtomicBoolean(false);
        this.echos = new ConcurrentHashMap<>();
        this.readys = new ConcurrentHashMap<>();
        this.byzantineProcesses = byzantineProcesses;
    }

    public void broadcast(Message message) throws LinkException {
        int myId = myProcess.getId();
        String messageString = new Gson().toJson(message);
        logger.info("P{}: Broadcasting message with id {}", myProcess.getId(), message.getMessageId());
        // Send the message to myself
        link.send(myId, new Message(myId, myId, Message.Type.SEND, messageString));
        // Send the message to everybody else
        for (Process process : peers) {
            int processId = process.getId();
            link.send(process.getId(), new Message(myId, processId, Message.Type.SEND, messageString));
        }
    }

    private void sendEcho(int myId, Message message) throws LinkException {
        if (sentEcho.get()) return;
        logger.info("P{}: Echoing message with id {}", myProcess.getId(), message.getMessageId());
        sentEcho.set(true);
        link.send(
                myId,
                new Message(myId, myId, Message.Type.ECHO, message.getPayload())
        );
        for (Process process : peers) {
            int processId = process.getId();
            link.send(
                    process.getId(),
                    new Message(myId, processId, Message.Type.ECHO, message.getPayload())
            );
        }
    }

    private void sendReady(
            int myId,
            Message message,
            ConcurrentHashMap<Integer, String> processMessages,
            int sendThreshold
    ) throws LinkException {
        processMessages.putIfAbsent(message.getSenderId(), message.getPayload());
        if (!sentReady.get() &&
                processMessages.values().stream()
                        .filter((m -> m.equals(message.getPayload())))
                        .count() > sendThreshold) {
            sentReady.set(true);
            logger.info("P{}: Readying message with id {}", myProcess.getId(), message.getMessageId());
            link.send(
                    myId,
                    new Message(myId, myId, Message.Type.READY, message.getPayload())
            );
            for (Process process : peers) {
                int processId = process.getId();
                link.send(
                        process.getId(),
                        new Message(myId, processId, Message.Type.READY, message.getPayload())
                );
            }
        }
    }

    public Message collect() throws LinkException {
        int myId = myProcess.getId();
        // Infinite loop to keep receiving messages until delivery can be done.
        while (true) {
            Message message = link.receive();
            if (message == null) continue;
            switch (message.getType()) {
                case SEND -> {
                    sendEcho(myId, message);
                }
                case ECHO -> {
                    sendReady(myId, message, echos, (int) ((Arrays.stream(peers).count() + 1) / 2));
                }
                case READY -> {
                    sendReady(myId, message, readys, byzantineProcesses);
                    if (!delivered.get() &&
                            readys.values().stream()
                                    .filter((m -> m.equals(message.getPayload())))
                                    .count() > 2L * byzantineProcesses) {
                        delivered.set(true);
                        logger.info("P{}: Delivering message with id {}", myProcess.getId(), message.getMessageId());
                        return new Gson().fromJson(message.getPayload(), Message.class);
                    }
                }
            }
        }
    }
}
