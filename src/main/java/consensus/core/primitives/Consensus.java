package consensus.core.primitives;

import com.google.gson.Gson;
import consensus.core.KeyService;
import consensus.core.model.ConsensusPayload;
import consensus.core.model.Message;
import consensus.core.model.WritePair;
import consensus.core.model.WriteState;
import consensus.util.Process;
import consensus.exception.LinkException;
import consensus.util.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class Consensus {

    private static final Logger logger = LoggerFactory.getLogger(Consensus.class);
    private final ConsensusBroker broker;
    private final Process myProcess;
    private final Process[] peers;
    private final Link link;
    private final KeyService keyService;
    private final WriteState myState;
    private final int epoch;
    private final ConcurrentHashMap<Integer, ConsensusPayload> peersStates;
    private final ConcurrentHashMap<Integer, WritePair> peersWrites;
    private final ConcurrentHashMap<Integer, String> peersAccepts;
    private final int byzantineProcesses;

    public Consensus(
            ConsensusBroker broker,
            Process myProcess,
            Process[] peers,
            KeyService keyService,
            Link link,
            int epoch,
            WriteState myState,
            int byzantineProcesses
    ) {
        this.broker = broker;
        this.myProcess = myProcess;
        this.peers = peers;
        this.link = link;
        this.keyService = keyService;
        this.epoch = epoch;
        this.myState = myState;
        this.peersStates = new ConcurrentHashMap<>();
        this.peersWrites = new ConcurrentHashMap<>();
        this.peersAccepts = new ConcurrentHashMap<>();
        this.byzantineProcesses = byzantineProcesses;
    }

    private void handleRead(int myId, String consensusId) throws LinkException {
        int leaderId = getRoundRobinLeader(epoch, peers.length + 1);
        logger.info("P{}: Received READ Request. Sending state to leader P{}.",
                myId, leaderId);
        ConsensusPayload statePayload = new ConsensusPayload(
                myId,
                consensusId,
                ConsensusPayload.ConsensusType.STATE,
                new Gson().toJson(myState),
                myId,
                keyService
        );
        // Send to leader
        link.send(
                myId,
                new Message(myId, leaderId, Message.Type.CONSENSUS, new Gson().toJson(statePayload))
        );
    }

    private void handleState(int myId, ConsensusPayload receivedPayload) throws LinkException {
        logger.info("P{}: Received STATE Message from P{}, storing.",
                myId, receivedPayload.getSenderId());
        peersStates.putIfAbsent(receivedPayload.getSenderId(), receivedPayload);
        if (peersStates.size() > 2 * byzantineProcesses) {
            ConsensusPayload collectedPayload = new ConsensusPayload(
                    myId,
                    receivedPayload.getConsensusId(),
                    ConsensusPayload.ConsensusType.COLLECTED,
                    new Gson().toJson(peersStates),
                    myId,
                    keyService
            );
            sendConsensusMessage(myId, collectedPayload);
        }
    }

    private void handleCollected(int myId, ConsensusPayload receivedPayload) throws Exception {
        logger.info("P{}: Received COLLECTED Message from leader P{}.",
                myId, receivedPayload.getSenderId());
        HashMap<Integer, ConsensusPayload> collectedStates = new Gson().fromJson(
                receivedPayload.getContent(),
                HashMap.class
        );
        collectedStates.forEach((processId, processState) -> {
            try {
                if (!SecurityUtil.verifySignature(
                        processState.getSenderId(),
                        processState.getConsensusId(),
                        processState.getCType(),
                        processState.getContent(),
                        processState.getSignature(),
                        keyService.loadPublicKey("p" + processState.getSenderId())
                )) {
                    logger.info("P{}: Invalid signature from P{}, aborting.",
                            myId, processState.getSenderId());
                    // TODO: Handle invalid signatures.
                }
            } catch(Exception e) {
                throw new RuntimeException(e);
            }
        });

        List<WriteState> writeStates = collectedStates.values().stream()
                .map((state) -> new Gson().fromJson(state.getContent(), WriteState.class))
                .toList();

        List<WritePair> writePairs = writeStates.stream()
                .map(WriteState::getLastestWrite)
                .toList();

        String writeValue = decideToWriteValue(writePairs, writeStates);

        if(writeValue != null) {
            logger.info("P{}: Sending WRITE message, timestamp {}, value {}", myId, epoch, writeValue);
            WritePair newWrite = new WritePair(epoch, writeValue);
            ConsensusPayload writePayload = new ConsensusPayload(
                    myId,
                    receivedPayload.getConsensusId(),
                    ConsensusPayload.ConsensusType.WRITE,
                    new Gson().toJson(newWrite),
                    myId,
                    keyService
            );

            myState.getWriteSet().add(newWrite);

            sendConsensusMessage(myId, writePayload);
        }

    };

    private String decideToWriteValue(List<WritePair> writePairs, List<WriteState> writeStates) {
        String mostRecentValue = writePairs.stream().max(Comparator.comparingInt(WritePair::timestamp)).get().value();

        if(writeStates.stream().anyMatch(writeState -> {
            return Objects.equals(writeState.getLastestWrite().value(), mostRecentValue);
        })) {
            return mostRecentValue;
        } else {
            return null;
        }
    }

    private void handleWrite(int myId, ConsensusPayload receivedPayload) throws LinkException {
        logger.info("P{}: Received WRITE Message from leader P{}.",
                myId, receivedPayload.getSenderId());
        WritePair collectedWrite = new Gson().fromJson(
                receivedPayload.getContent(),
                WritePair.class
        );
        peersWrites.putIfAbsent(receivedPayload.getSenderId(), collectedWrite);
        if (peersWrites.values().stream()
                        .filter(m -> m.equals(collectedWrite))
                        .count() > 2L * byzantineProcesses) {
            ConsensusPayload collectedPayload = new ConsensusPayload(
                    myId,
                    receivedPayload.getConsensusId(),
                    ConsensusPayload.ConsensusType.ACCEPT,
                    collectedWrite.value(),
                    myId,
                    keyService
            );
            sendConsensusMessage(myId, collectedPayload);
        }
    }

    // TODO: Update the state
    private String handleAccept(int myId, ConsensusPayload receivedPayload) throws LinkException {
        logger.info("P{}: Received ACCEPT Message from leader P{}.",
                myId, receivedPayload.getSenderId());
        peersAccepts.putIfAbsent(receivedPayload.getSenderId(), receivedPayload.getContent());
        if(peersAccepts.values().stream()
                .filter(m -> m.equals(receivedPayload.getContent()))
                .count() > 2L * byzantineProcesses) {
            logger.info("P{}: Consensus reached. Decided value: {}",
                    myId, receivedPayload.getContent());
            return receivedPayload.getContent();
        }
        return null;
    }

    // TODO: Handle the decided message returned in "handleAccept"
    public String collect(String consensusId) throws Exception {
        int myId = myProcess.getId();
        // Infinite loop to keep receiving messages until delivery can be done.
        while(true) {
            logger.info("P{}: Waiting for message for consensus {}...", myProcess.getId(), consensusId);
            ConsensusPayload payload = broker.receiveConsensusMessage(consensusId);
            if (payload == null) continue;
            logger.info("P{}: Collecting Received message: {}", myProcess.getId(), payload.getContent());
            switch (payload.getCType()) {
                case READ -> handleRead(myId, payload.getConsensusId());
                case STATE -> handleState(myId, payload);
                case COLLECTED -> handleCollected(myId, payload);
                case WRITE -> handleWrite(myId, payload);
                case ACCEPT -> handleAccept(myId, payload);
            }
        }
    }

    private void sendConsensusMessage(int myId, ConsensusPayload collectedPayload) throws LinkException {
        String payloadToSend = new Gson().toJson(collectedPayload);
        // Sending accept to myself
        link.send(
                myId,
                new Message(myId, myId, Message.Type.CONSENSUS, payloadToSend)
        );
        // Sending accept to all peers
        for (Process process : peers) {
            int processId = process.getId();
            link.send(
                    process.getId(),
                    new Message(myId, processId, Message.Type.CONSENSUS, payloadToSend)
            );
        }
    }

    public static int getRoundRobinLeader(int epoch, int numNodes){
        return epoch % numNodes;
    }

}

