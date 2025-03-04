package consensus.core.primitives;

import com.google.gson.Gson;
import consensus.core.model.BroadcastPayload;
import consensus.core.model.Message;
import consensus.util.Process;
import consensus.exception.LinkException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static consensus.core.model.BroadcastPayload.BroadcastType.*;

public class ReliableBroadcast {

    private static final Logger logger = LoggerFactory.getLogger(ReliableBroadcast.class);
    private final BroadcastBroker broker;
    private final Process myProcess;
    private final Process[] peers;
    private final Link link;
    private final AtomicBoolean sentEcho;
    private final AtomicBoolean sentReady;
    private final AtomicBoolean delivered;
    private final ConcurrentHashMap<Integer, String> echos;
    private final ConcurrentHashMap<Integer, String> readys;
    private final int byzantineProcesses;

    public ReliableBroadcast(BroadcastBroker broker, Process myProcess, Process[] peers, Link link, int byzantineProcesses) {
        this.broker = broker;
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

    private void sendEcho(int myId, BroadcastPayload payload) throws LinkException {
        if (sentEcho.get()) return;
        payload.setSenderId(myId);
        payload.setBType(ECHO);
        String bPayloadString = new Gson().toJson(payload);
        logger.info("P{}: Echoing message: {}", myProcess.getId(), payload.getContent());
        sentEcho.set(true);
        link.send(
                myId,
                new Message(myId, myId, Message.Type.BROADCAST, bPayloadString)
        );
        for (Process process : peers) {
            int processId = process.getId();
            link.send(
                    process.getId(),
                    new Message(myId, processId, Message.Type.BROADCAST, bPayloadString)
            );
        }
    }

    private void sendReady(
            int myId,
            BroadcastPayload payload,
            ConcurrentHashMap<Integer, String> processMessages,
            int sendThreshold
    ) throws LinkException {
        processMessages.putIfAbsent(payload.getSenderId(), payload.getContent());
        if (!sentReady.get() &&
                processMessages.values().stream()
                        .filter((m -> m.equals(payload.getContent())))
                        .count() > sendThreshold) {
            sentReady.set(true);
            
            payload.setSenderId(myId);
            payload.setBType(READY);
            String bPayloadString = new Gson().toJson(payload);
            logger.info("P{}: Readying message: {}", myProcess.getId(), payload.getContent());
            link.send(
                    myId,
                    new Message(myId, myId, Message.Type.BROADCAST, bPayloadString)
            );
            for (Process process : peers) {
                int processId = process.getId();
                link.send(
                        process.getId(),
                        new Message(myId, processId, Message.Type.BROADCAST, bPayloadString)
                );
            }
        }
    }

    public String collect(String broadcastId) throws LinkException {
        int myId = myProcess.getId();
        // Infinite loop to keep receiving messages until delivery can be done.
        while(true) {
            BroadcastPayload payload = broker.receiveBroadcastMessage(broadcastId);
            if (payload == null) continue;
            switch (payload.getBType()) {
                case SEND -> sendEcho(myId, payload);
                case ECHO -> sendReady(myId, payload, echos, (int) ((Arrays.stream(peers).count() + 1) / 2));
                case READY -> {
                    sendReady(myId, payload, readys, byzantineProcesses);
                    if (!delivered.get() &&
                            readys.values().stream()
                                    .filter((m -> m.equals(payload.getContent())))
                                    .count() > 2L * byzantineProcesses) {
                        delivered.set(true);
                        logger.info("P{}: Delivering message: {}", myProcess.getId(), payload.getContent());
                        return payload.getContent();
                    }
                }
            }
        }
    }
}
