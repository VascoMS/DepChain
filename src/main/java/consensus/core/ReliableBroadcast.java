package consensus.core;

import com.google.gson.Gson;
import consensus.util.Process;
import consensus.exception.LinkException;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class ReliableBroadcast {

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
        // Send the message to myself
        link.send(myId, new Message(myId, myId, Message.Type.SEND, messageString));
        // Send the message to everybody else
        for (Process process : peers) {
            int processId = process.getId();
            link.send(process.getId(), new Message(myId, processId, Message.Type.SEND, messageString));
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
                    if (sentEcho.get()) continue;
                    sentEcho.set(true);
                    for (Process process : peers) {
                        int processId = process.getId();
                        link.send(
                                process.getId(),
                                new Message(myId, processId, Message.Type.ECHO, message.getPayload())
                        );
                    }
                }
                case ECHO -> {
                    echos.putIfAbsent(message.getSenderId(), message.getPayload());
                    if (!sentReady.get() &&
                            echos.values().stream()
                                    .filter(m -> m.equals(message.getPayload()))
                                    .count() > (Arrays.stream(peers).count() + 1 + byzantineProcesses) / 2) {
                        sentReady.set(true);
                        for (Process process : peers) {
                            int processId = process.getId();
                            link.send(
                                    process.getId(),
                                    new Message(myId, processId, Message.Type.READY, message.getPayload())
                            );
                        }
                    }
                }
                case READY -> {
                    readys.putIfAbsent(message.getSenderId(), message.getPayload());
                    if (!sentReady.get() &&
                            readys.values().stream()
                                    .filter((m -> m.equals(message.getPayload())))
                                    .count() > byzantineProcesses) {
                        sentReady.set(true);
                        for (Process process : peers) {
                            int processId = process.getId();
                            link.send(
                                    process.getId(),
                                    new Message(myId, processId, Message.Type.READY, message.getPayload())
                            );
                        }
                    } else if(!delivered.get() &&
                            readys.values().stream()
                                    .filter((m -> m.equals(message.getPayload())))
                                    .count() > 2L * byzantineProcesses) {
                        delivered.set(true);
                        return new Gson().fromJson(message.getPayload(), Message.class);
                    }
                }
            }
        }
    }
}
