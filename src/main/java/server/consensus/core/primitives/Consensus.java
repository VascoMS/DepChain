package server.consensus.core.primitives;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import common.model.Message;
import common.primitives.AuthenticatedPerfectLink;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.blockchain.model.Block;
import server.consensus.core.model.ConsensusPayload;
import server.consensus.core.model.WritePair;
import server.consensus.core.model.WriteState;
import server.consensus.exception.LinkException;
import server.consensus.test.ConsensusByzantineMode;
import util.KeyService;
import util.Process;
import util.SecurityUtil;

import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static server.consensus.core.model.ConsensusPayload.ConsensusType.READ;

public class Consensus {
    private static final Logger logger = LoggerFactory.getLogger(Consensus.class);
    @Getter
    private final int roundId;
    private final Block proposal;
    private final ConsensusBroker broker;
    private final Process myProcess;
    private final Process[] peers;
    private final AuthenticatedPerfectLink link;
    private final KeyService keyService;
    private final WriteState myState;
    private int epoch;
    private final int epochOffset;
    private final ConcurrentHashMap<String, ConsensusPayload> peersStates;
    private final ConcurrentHashMap<String, WritePair> peersWrites;
    private final ConcurrentHashMap<String, String> peersAccepts;
    private boolean fetchedFromClientQueue;
    private String currentLeaderId;
    private final int byzantineProcesses;
    private long waitingForMessageTimeout;
    private final ConsensusByzantineMode byzantineMode;
    private Block decision;

    public Consensus(
            int id,
            Block proposal,
            ConsensusBroker broker,
            Process myProcess,
            Process[] peers,
            KeyService keyService,
            AuthenticatedPerfectLink link,
            int byzantineProcesses,
            int epochOffset,
            ConsensusByzantineMode byzantineMode
    ) {
        this.roundId = id;
        this.proposal = proposal;
        this.broker = broker;
        this.myProcess = myProcess;
        this.peers = peers;
        this.link = link;
        this.keyService = keyService;
        this.epoch = 0;
        this.epochOffset = epochOffset; // Offset allows leader changes to be reflected in subsequent consensus instances
        this.currentLeaderId = getRoundRobinLeader(epoch, peers.length + 1);
        this.myState = new WriteState();
        this.peersStates = new ConcurrentHashMap<>();
        this.peersWrites = new ConcurrentHashMap<>();
        this.peersAccepts = new ConcurrentHashMap<>();
        this.byzantineProcesses = byzantineProcesses;
        this.fetchedFromClientQueue = false;
        this.waitingForMessageTimeout = 1000;
        this.byzantineMode = byzantineMode;
    }

    private void handleRead(String myId, String senderId, int consensusId) throws LinkException {
        if(senderId.equals(currentLeaderId)) {
            logger.info("{}: Received READ Request. Sending state to leader {}.",
                    myId, currentLeaderId);
            if(myState.getLatestWrite() == null){
                WritePair writePair = new WritePair(epoch, proposal);
                this.fetchedFromClientQueue = true;
                myState.setLatestWrite(writePair);
            }
            ConsensusPayload statePayload = createMyState(myId, consensusId);

            // Send to leader
            link.send(
                    currentLeaderId,
                    new Message(myId, currentLeaderId, Message.Type.CONSENSUS, new Gson().toJson(statePayload))
            );
        } else {
            logger.info("{}: Received READ from non-reader {}, ignoring.", myId, senderId);
        }
    }

    private void handleState(String myId, ConsensusPayload receivedPayload) throws LinkException {
        logger.info("{}: Received STATE Message from {}, storing.",
                myId, receivedPayload.getSenderId());
        if(!iAmLeader())
            return; // Ignore if not leader
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

    private boolean handleCollected(String myId, ConsensusPayload receivedPayload) throws Exception {
        if(receivedPayload.getSenderId().equals(currentLeaderId)) {
            logger.info("{}: Received COLLECTED Message from leader {}.",
                    myId, receivedPayload.getSenderId());

            // Parse and verify collected state signatures
            HashMap<String, ConsensusPayload> collectedStates = parseCollectedStates(receivedPayload);
            if (!verifyCollectedStates(myId, collectedStates)) {
                return false; // Abort if verification fails
            }

            WriteState leaderState = new Gson().fromJson(
                    collectedStates.get(currentLeaderId).getContent(), WriteState.class
            );
            Block leaderValue = leaderState.getLatestWrite().value();

            List<WriteState> writeStates = extractWriteStates(collectedStates);
            List<List<WritePair>> writeSets = writeStates.stream()
                    .map(WriteState::getWriteSet)
                    .toList();
            List<WritePair> writePairs = extractLatestWrites(writeStates);

            Block writeValue = decideToWriteValue(myId, writePairs, writeSets, leaderValue);

            if (writeValue != null) {
                sendWriteMessage(myId, receivedPayload.getConsensusId(), writeValue, epoch);
            }
        }
        else {
            logger.info("{}: Received COLLECTED message from non-leader {}, ignoring.", myId, receivedPayload.getSenderId());
        }
        return true;
    }

    private boolean handleWrite(String myId, ConsensusPayload receivedPayload) throws LinkException {
        logger.info("{}: Received WRITE Message from {}.",
                myId, receivedPayload.getSenderId());
        WritePair collectedWrite = new Gson().fromJson(
                receivedPayload.getContent(),
                WritePair.class
        );
        peersWrites.putIfAbsent(receivedPayload.getSenderId(), collectedWrite);
        logger.info("{}: Received write number {}.", myId, peersWrites.size());
        if (peersWrites.values().stream()
                        .filter(m -> m.equals(collectedWrite))
                        .count() > 2L * byzantineProcesses) {
            // Validate observed write
            if(!broker.validateBlock(collectedWrite.value())) {
                logger.error("{}: Invalid block.", myId);
                return false;
            }
            logger.info("{}: Sending ACCEPT message, value {}", myId, collectedWrite.value());
            // Return request to queue if it had been fetched and was not chosen in the server.consensus round
            if(fetchedFromClientQueue && myState.getLatestWrite().value() != null && !myState.getLatestWrite().equals(collectedWrite)) {
                broker.returnTransactions(proposal, collectedWrite.value());
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
            logger.info("{}: Could not reach server.consensus while waiting for WRITE, abort.", myId);
            return false;
        }
        return true;
    }

    private boolean handleAccept(String myId, ConsensusPayload receivedPayload) {
        logger.info("{}: Received ACCEPT Message from {}.",
                myId, receivedPayload.getSenderId());
        peersAccepts.putIfAbsent(receivedPayload.getSenderId(), receivedPayload.getContent());
        logger.info("{}: Received accept number {}.", myId, peersAccepts.size());
        if(peersAccepts.values().stream()
                .filter(m -> m.equals(receivedPayload.getContent()))
                .count() > 2L * byzantineProcesses) {
            logger.info("{}: Consensus reached. Decided value: {}", myId, receivedPayload.getContent());
            decision = new Gson().fromJson(receivedPayload.getContent(), Block.class);
            return true;
        }
        if (peersAccepts.size() == peers.length + 1) {
            logger.info("{}: Could not reach server.consensus while waiting for ACCEPT, abort.", myId);
            return false;
        }
        return false;
    }

    public Block collect(int consensusId) throws Exception {
        String myId = myProcess.getId();
        boolean delivered = false;
        // Loop to keep receiving messages until delivery can be done.
        while(!delivered) {
            logger.info("{}: Waiting for message for server.consensus {}...", myProcess.getId(), consensusId);
            try {
                ConsensusPayload payload = broker.receiveConsensusMessage(consensusId, waitingForMessageTimeout);
                // When payload returns null, timeout reached so abort.
                if (payload == null) {
                    logger.info("{}: Consensus aborted due to timeout.", myId);
                    waitingForMessageTimeout *= 2;
                    return null;
                }
                logger.info("{}: Collecting Received message {}: {}",
                        myProcess.getId(), payload.getCType(), payload.getContent());
                if(byzantineMode == ConsensusByzantineMode.DROP_ALL) {
                    logger.info("{}: Byzantine - ignore received message.", myId);
                    if(payload.getCType() == ConsensusPayload.ConsensusType.ACCEPT) return null; else continue;
                }
                switch (payload.getCType()) {
                    case READ -> handleRead(myId, payload.getSenderId(), payload.getConsensusId());
                    case STATE -> handleState(myId, payload);
                    case COLLECTED -> {
                        boolean valid = handleCollected(myId, payload);
                        if (!valid) {
                            return null;
                        }
                    }
                    case WRITE -> {
                        boolean valid = handleWrite(myId, payload);
                        if (!valid) {
                            return null;
                        }
                    }
                    case ACCEPT -> delivered = handleAccept(myId, payload);
                }
            } catch(InterruptedException e) {
                logger.error("{}: Message collection interrupted. {}", myId, e.getMessage());
                return null;
            }
        }
        return decision;
    }

    public Block runAsLeader() throws Exception {
        String myId = myProcess.getId();
        ConsensusPayload cPayload = new ConsensusPayload(myId, roundId, READ, null, keyService);
        String payloadString = new Gson().toJson(cPayload);
        logger.info("{}: Starting server.consensus as leader", myProcess.getId());
        // Send the message to myself
        // link.send(myId, new Message(myId, myId, Message.Type.CONSENSUS, payloadString));
        peersStates.putIfAbsent(myId, createMyState(myId, roundId));
        // Send the message to everybody else
        for (Process process : peers) {
            String processId = process.getId();
            link.send(process.getId(), new Message(myId, processId, Message.Type.CONSENSUS, payloadString));
        }
        return collect(roundId);
    }

    public Block runAsFollower() throws Exception {
        logger.info("{}: Starting server.consensus as follower", myProcess.getId());
        return collect(roundId);
    }

    private ConsensusPayload createMyState(String myId, int consensusId) {
        if(myState.getLatestWrite() == null) {
            WritePair writePair = new WritePair(epoch, proposal);
            this.fetchedFromClientQueue = true;
            myState.setLatestWrite(writePair);
        }
        return new ConsensusPayload(
                myId,
                consensusId,
                ConsensusPayload.ConsensusType.STATE,
                new Gson().toJson(myState),
                keyService
        );
    }

    private HashMap<String, ConsensusPayload> parseCollectedStates(ConsensusPayload receivedPayload) {
        Type type = new TypeToken<HashMap<String, ConsensusPayload>>() {}.getType();
        return new Gson().fromJson(receivedPayload.getContent(), type);
    }

    private boolean verifyCollectedStates(String myId, HashMap<String, ConsensusPayload> collectedStates) {
        try {
            for (ConsensusPayload processState : collectedStates.values()) {
                if (!verifyProcessSignature(myId, processState)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            logger.error("{}: Exception during signature verification: {}", myId, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private boolean verifyProcessSignature(String myId, ConsensusPayload processState) throws Exception {
        boolean isValid = SecurityUtil.verifySignature(
                processState.getSenderId(),
                processState.getConsensusId(),
                processState.getCType(),
                processState.getContent(),
                processState.getSignature(),
                keyService.loadPublicKey(processState.getSenderId())
        );

        if (!isValid) {
            logger.info("{}: Invalid signature from {}, aborting.",
                    myId, processState.getSenderId());
        }

        return isValid;
    }

    private List<WriteState> extractWriteStates(HashMap<String, ConsensusPayload> collectedStates) {
        return collectedStates.values().stream()
                .map(state -> new Gson().fromJson(state.getContent(), WriteState.class))
                .toList();
    }

    private List<WritePair> extractLatestWrites(List<WriteState> writeStates) {
        return writeStates.stream()
                .map(WriteState::getLatestWrite)
                .toList();
    }

    private void sendWriteMessage(String myId, int consensusId, Block writeValue, int epoch) throws LinkException {
        logger.info("{}: Sending WRITE message, timestamp {}, value {}", myId, epoch, writeValue);

        WritePair newWrite = new WritePair(epoch, writeValue);
        ConsensusPayload writePayload = new ConsensusPayload(
                myId,
                consensusId,
                ConsensusPayload.ConsensusType.WRITE,
                new Gson().toJson(newWrite),
                keyService
        );

        myState.addToWriteSet(newWrite);

        sendConsensusMessage(myId, writePayload);
    }


    private Block decideToWriteValue(String myId, List<WritePair> writePairs, List<List<WritePair>> writeSets, Block leaderValue) {
        WritePair mostRecentWritePair = writePairs.stream()
                .filter(Objects::nonNull)
                .max(Comparator.comparingInt(WritePair::timestamp)).orElse(null);
        if(mostRecentWritePair != null && writeSets.stream().allMatch(List::isEmpty) ||
                writeSets.stream()
                        .filter(writeSetList -> writeSetList.stream()
                                .anyMatch(writePair -> writePair.value().equals(mostRecentWritePair.value())))
                        .count() > byzantineProcesses) {
            logger.info("{}: Accepted value: {}.", myId, mostRecentWritePair.value());
            return mostRecentWritePair.value();
        } else {
            logger.info("{}: Value not present in byzantine quorum writesets, following leader", myId);
            return leaderValue;
        }
    }

    private void sendConsensusMessage(String myId, ConsensusPayload collectedPayload) throws LinkException {
        logger.info("{}: Sending {} message: {}", myId, collectedPayload.getCType(), collectedPayload.getContent());
        String payloadToSend = new Gson().toJson(collectedPayload);
        // Sending accept to myself
        link.send(
                myId,
                new Message(myId, myId, Message.Type.CONSENSUS, payloadToSend)
        );
        // Sending accept to all peers
        for (Process process : peers) {
            String processId = process.getId();
            link.send(
                    process.getId(),
                    new Message(myId, processId, Message.Type.CONSENSUS, payloadToSend)
            );
        }
    }

    public String getRoundRobinLeader(int epoch, int numNodes){
        return "p" + ((epoch + epochOffset) % numNodes);
    }

    public boolean iAmLeader() {
        return myProcess.getId().equals(currentLeaderId);
    }


    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        Consensus consensus = (Consensus) o;
        return epoch == consensus.epoch && currentLeaderId.equals(consensus.currentLeaderId) && byzantineProcesses == consensus.byzantineProcesses && Objects.equals(broker, consensus.broker) && Objects.equals(myProcess, consensus.myProcess) && Objects.deepEquals(peers, consensus.peers) && Objects.equals(link, consensus.link) && Objects.equals(keyService, consensus.keyService) && Objects.equals(myState, consensus.myState) && Objects.equals(peersStates, consensus.peersStates) && Objects.equals(peersWrites, consensus.peersWrites) && Objects.equals(peersAccepts, consensus.peersAccepts) && Objects.equals(decision, consensus.decision);
    }

    @Override
    public int hashCode() {
        return Objects.hash(broker, myProcess, Arrays.hashCode(peers), link, keyService, myState, epoch, peersStates, peersWrites, peersAccepts, currentLeaderId, byzantineProcesses, decision);
    }
}

