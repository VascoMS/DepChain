package consensus.core;

import com.google.gson.Gson;
import consensus.util.Process;
import consensus.exception.LinkException;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class ReliableBroadcast implements AutoCloseable {

    private Process myProcess;
    private Process[] peers;
    private Link link;
    private AtomicBoolean sentEcho;
    private AtomicBoolean sentReady;
    private AtomicBoolean delivered;
    private ConcurrentHashMap<Integer, String> echos;
    private ConcurrentHashMap<Integer, String> readys;

    public ReliableBroadcast(Process myProcess, Process[] peers) throws LinkException {
        this.myProcess = myProcess;
        this.peers = peers;
        this.link = new Link(myProcess, peers, 200);
        this.sentEcho = new AtomicBoolean(false);
        this.sentReady = new AtomicBoolean(false);
        this.delivered = new AtomicBoolean(false);
        this.echos = new ConcurrentHashMap<>();
        this.readys = new ConcurrentHashMap<>();
    }

    public void broadcast(SignedMessage message) throws LinkException {
        int myId = myProcess.getId();
        String messageString = new Gson().toJson(message);
        // Send the message to myself
        link.send(myId, new Message(myId, myId, Message.Type.SEND, messageString));
        // Send the message to everybody else
        for(Process process : peers) {
            int processId = process.getId();
            link.send(process.getId(), new Message(myId, processId, Message.Type.SEND, messageString));
        }
    }

    public Message collect() throws LinkException {
        int myId = myProcess.getId();
        while(true) {
            Message message = link.receive();
            switch (message.getType()) {
                case SEND -> {
                    if (sentEcho.get()) continue;
                    sentEcho.set(true);
                    for (Process process: peers) {
                        int processId = process.getId();
                        link.send(
                                process.getId(),
                                new Message(myId, processId, Message.Type.ECHO, message.getPayload())
                        );
                    }
                }
                case ECHO -> {
                    echos.putIfAbsent(message.getSenderId(), message.getPayload());
                    if(!sentEcho.get() &&
                            echos.values().stream()
                                    .filter(m -> m.equals(message.getPayload()))
                                    .count() > 1) { // este 2
                        sentEcho.set(true);
                        for (Process process: peers) {
                            int processId = process.getId();
                            link.send(
                                    process.getId(),
                                    new Message(myId, processId, Message.Type.READY, message.getPayload())
                            );
                        }
                    }
                }
                case READY -> readys.putIfAbsent(message.getSenderId(), message.getPayload());
            }
        }
    }

    @Override
    public void close() {
        link.close();
    }
}
