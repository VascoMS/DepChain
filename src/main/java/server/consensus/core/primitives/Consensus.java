package server.consensus.core.primitives;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import common.model.Transaction;
import common.model.Message;
import common.primitives.Link;
import util.KeyService;
import server.consensus.core.model.*;
import util.Process;
import server.consensus.exception.LinkException;
import util.SecurityUtil;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Consensus {
    private static final Logger logger = LoggerFactory.getLogger(Consensus.class);
    @Getter
    private final int id;
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
    private boolean fetchedFromClientQueue;
    private final int currentLeaderId;
    private final int byzantineProcesses;
    private Transaction decision;

    public Consensus(
            int id,
            ConsensusBroker broker,
            Process myProcess,
            Process[] peers,
            KeyService keyService,
            Link link,
            int epoch,
            int byzantineProcesses
    ) {
        this.id = id;
        this.broker = broker;
        this.myProcess = myProcess;
        this.peers = peers;
        this.link = link;
        this.keyService = keyService;
        this.epoch = epoch;
        this.currentLeaderId = getRoundRobinLeader(epoch, peers.length + 1);
        this.myState = new WriteState();
        this.peersStates = new ConcurrentHashMap<>();
        this.peersWrites = new ConcurrentHashMap<>();
        this.peersAccepts = new ConcurrentHashMap<>();
        this.byzantineProcesses = byzantineProcesses;
        this.fetchedFromClientQueue = false;
    }

    private void handleRead(int myId, int senderId, int consensusId) throws LinkException {
        if(senderId == currentLeaderId) {
            logger.info("P{}: Received READ Request. Sending state to leader P{}.",
                    myId, currentLeaderId);
            if(myState.getLatestWrite() == null){
                WritePair writePair = new WritePair(epoch, broker.fetchClientRequest());
                this.fetchedFromClientQueue = true;
                myState.setLatestWrite(writePair);
            }
            ConsensusPayload statePayload = new ConsensusPayload(
                    myId,
                    consensusId,
                    ConsensusPayload.ConsensusType.STATE,
                    new Gson().toJson(myState),
                    keyService
            );
            // Send to leader
            link.send(
                    currentLeaderId,
                    new Message(myId, currentLeaderId, Message.Type.CONSENSUS, new Gson().toJson(statePayload))
            );
        }
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
                    keyService
            );
            sendConsensusMessage(myId, collectedPayload);
        }
    }

    private boolean handleCollected(int myId, ConsensusPayload receivedPayload) throws Exception {
        logger.info("P{}: Received COLLECTED Message from leader P{}.",
                myId, receivedPayload.getSenderId());

        // Parse and verify collected state signatures
        HashMap<Integer, ConsensusPayload> collectedStates = parseCollectedStates(receivedPayload);
        if (!verifyCollectedStates(myId, collectedStates)) {
            return false; // Abort if verification fails
        }

        WriteState leaderState = new Gson().fromJson(
                collectedStates.get(currentLeaderId).getContent(), WriteState.class
        );
        Transaction leaderValue = leaderState.getLatestWrite().value();

        List<WriteState> writeStates = extractWriteStates(collectedStates);
        List<List<WritePair>> writeSets = writeStates.stream()
                .map(WriteState::getWriteSet)
                .toList();
        List<WritePair> writePairs = extractLatestWrites(writeStates);

        Transaction writeValue = decideToWriteValue(myId, writePairs, writeSets, leaderValue);

        if (writeValue != null) {
            sendWriteMessage(myId, receivedPayload.getConsensusId(), writeValue, epoch);
        }
        return true;
    }

    private boolean handleWrite(int myId, ConsensusPayload receivedPayload) throws LinkException {
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
            // Validate observed write
            if(!validateTransaction(collectedWrite.value())) {
                logger.error("P{}: Invalid client transaction signature.", myId);
                return false;
            }
            logger.info("P{}: Sending ACCEPT message, value {}", myId, collectedWrite.value());
            // Return request to queue if it had been fetched and was not chosen in the server.consensus round
            if(fetchedFromClientQueue && myState.getLatestWrite().value() != null && !myState.getLatestWrite().equals(collectedWrite)) {
                broker.addClientRequest(myState.getLatestWrite().value());
                fetchedFromClientQueue = false;
            }
            myState.setLatestWrite(collectedWrite);
            ConsensusPayload collectedPayload = new ConsensusPayload(
                    myId,
                    receivedPayload.getConsensusId(),
                    ConsensusPayload.ConsensusType.ACCEPT,
                    new Gson().toJson(collectedWrite.value()),
                    keyService
            );
            sendConsensusMessage(myId, collectedPayload);
            return true;
        }
        if (peersWrites.size() == peers.length + 1) {
            logger.info("P{}: Could not reach server.consensus, abort.", myId);
            return false;
        }
        return true;
    }

    private boolean validateTransaction(Transaction transaction) {
        try {
            return SecurityUtil.verifySignature(
                    transaction, keyService.loadPublicKey("c" + transaction.clientId())
            );
        } catch (Exception e) {
            logger.error("P{}: Error checking transaction signature: {}", myProcess.getId(), e.getMessage());
            return false;
        }
    }

    private boolean handleAccept(int myId, ConsensusPayload receivedPayload) {
        logger.info("P{}: Received ACCEPT Message from leader P{}.",
                myId, receivedPayload.getSenderId());
        peersAccepts.putIfAbsent(receivedPayload.getSenderId(), receivedPayload.getContent());
        if(peersAccepts.values().stream()
                .filter(m -> m.equals(receivedPayload.getContent()))
                .count() > 2L * byzantineProcesses) {
            logger.info("P{}: Consensus reached. Decided value: {}", myId, receivedPayload.getContent());
            decision = new Gson().fromJson(receivedPayload.getContent(), Transaction.class);
            return true;
        }
        return false;
    }

    public Transaction collect(int consensusId) throws Exception {
        int myId = myProcess.getId();
        boolean delivered = false;
        // Loop to keep receiving messages until delivery can be done.
        while(!delivered) {
            logger.info("P{}: Waiting for message for server.consensus {}...", myProcess.getId(), consensusId);
            ConsensusPayload payload = broker.receiveConsensusMessage(consensusId);
            if (payload == null) continue;
            logger.info("P{}: Collecting Received message {}: {}",
                    myProcess.getId(), payload.getCType(), payload.getContent());
            switch (payload.getCType()) {
                case READ -> handleRead(myId, payload.getSenderId(), payload.getConsensusId());
                case STATE -> handleState(myId, payload);
                case COLLECTED -> {
                    boolean valid = handleCollected(myId, payload);
                    if(!valid) { return null; }
                }
                case WRITE -> {
                    boolean valid = handleWrite(myId, payload);
                    if(!valid) { return null; }
                }
                case ACCEPT -> delivered = handleAccept(myId, payload);
            }
        }
        return decision;
    }

    private HashMap<Integer, ConsensusPayload> parseCollectedStates(ConsensusPayload receivedPayload) {
        Type type = new TypeToken<HashMap<Integer, ConsensusPayload>>() {}.getType();
        return new Gson().fromJson(receivedPayload.getContent(), type);
    }

    private boolean verifyCollectedStates(int myId, HashMap<Integer, ConsensusPayload> collectedStates) {
        try {
            for (ConsensusPayload processState : collectedStates.values()) {
                if (!verifyProcessSignature(myId, processState)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            logger.error("P{}: Exception during signature verification: {}", myId, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private boolean verifyProcessSignature(int myId, ConsensusPayload processState) throws Exception {
        boolean isValid = SecurityUtil.verifySignature(
                processState.getSenderId(),
                processState.getConsensusId(),
                processState.getCType(),
                processState.getContent(),
                processState.getSignature(),
                keyService.loadPublicKey("p" + processState.getSenderId())
        );

        if (!isValid) {
            logger.info("P{}: Invalid signature from P{}, aborting.",
                    myId, processState.getSenderId());
        }

        return isValid;
    }

    private List<WriteState> extractWriteStates(HashMap<Integer, ConsensusPayload> collectedStates) {
        return collectedStates.values().stream()
                .map(state -> new Gson().fromJson(state.getContent(), WriteState.class))
                .toList();
    }

    private List<WritePair> extractLatestWrites(List<WriteState> writeStates) {
        return writeStates.stream()
                .map(WriteState::getLatestWrite)
                .toList();
    }

    private void sendWriteMessage(int myId, int consensusId, Transaction writeValue, int epoch) throws LinkException {
        logger.info("P{}: Sending WRITE message, timestamp {}, value {}", myId, epoch, writeValue);

        WritePair newWrite = new WritePair(epoch, writeValue);
        ConsensusPayload writePayload = new ConsensusPayload(
                myId,
                consensusId,
                ConsensusPayload.ConsensusType.WRITE,
                new Gson().toJson(newWrite),
                keyService
        );

        myState.getWriteSet().add(newWrite);

        sendConsensusMessage(myId, writePayload);
    }


    private Transaction decideToWriteValue(int myId, List<WritePair> writePairs, List<List<WritePair>> writeSets, Transaction leaderValue) {
        WritePair mostRecentWritePair = writePairs.stream()
                .filter(Objects::nonNull)
                .max(Comparator.comparingInt(WritePair::timestamp)).orElse(null);
        if(mostRecentWritePair != null && writeSets.stream().allMatch(List::isEmpty) ||
                writeSets.stream()
                        .filter(writeSetList -> writeSetList.stream()
                                .anyMatch(writePair -> writePair.value().equals(mostRecentWritePair.value())))
                        .count() > byzantineProcesses) {
            logger.info("P{}: Accepted value: {}.", myId, mostRecentWritePair.value());
            return mostRecentWritePair.value();
        } else {
            logger.info("P{}: Value not present in byzantine quorum writesets, following leader", myId);
            return leaderValue;
        }
    }

    private void sendConsensusMessage(int myId, ConsensusPayload collectedPayload) throws LinkException {
        logger.info("P{}: Sending {} message: {}", myId, collectedPayload.getCType(), collectedPayload.getContent());
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

    public void clearEpochState() {
        peersStates.clear();
        peersWrites.clear();
        peersAccepts.clear();
    }


    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        Consensus consensus = (Consensus) o;
        return epoch == consensus.epoch && currentLeaderId == consensus.currentLeaderId && byzantineProcesses == consensus.byzantineProcesses && Objects.equals(broker, consensus.broker) && Objects.equals(myProcess, consensus.myProcess) && Objects.deepEquals(peers, consensus.peers) && Objects.equals(link, consensus.link) && Objects.equals(keyService, consensus.keyService) && Objects.equals(myState, consensus.myState) && Objects.equals(peersStates, consensus.peersStates) && Objects.equals(peersWrites, consensus.peersWrites) && Objects.equals(peersAccepts, consensus.peersAccepts) && Objects.equals(decision, consensus.decision);
    }

    @Override
    public int hashCode() {
        return Objects.hash(broker, myProcess, Arrays.hashCode(peers), link, keyService, myState, epoch, peersStates, peersWrites, peersAccepts, currentLeaderId, byzantineProcesses, decision);
    }
}

