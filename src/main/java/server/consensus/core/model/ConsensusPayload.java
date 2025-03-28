package server.consensus.core.model;

import lombok.Getter;
import lombok.Setter;
import util.KeyService;
import util.SecurityUtil;


@Getter
public class ConsensusPayload {

    @Setter
    private String senderId;
    private final int consensusId;
    @Setter
    private ConsensusType cType;
    private final String content;
    private final String signature;

    public enum ConsensusType {
        READ, STATE, COLLECTED, WRITE, ACCEPT
    }

    public ConsensusPayload(String senderId, int consensusId, ConsensusType cType, String content, KeyService keyService) {
        try {
            this.senderId = senderId;
            this.consensusId = consensusId;
            this.cType = cType;
            this.content = content;
            if(cType == ConsensusType.STATE || cType == ConsensusType.COLLECTED) {
                this.signature = SecurityUtil.signConsensusPayload(
                        senderId, consensusId, cType, content,
                        keyService.loadPrivateKey(senderId)
                );
            } else {
                this.signature = null;
            }
        } catch(Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ConsensusPayload(String senderId, int consensusId, ConsensusType cType, String content, String signature) {
        this.senderId = senderId;
        this.consensusId = consensusId;
        this.cType = cType;
        this.content = content;
        this.signature = signature;
    }
}
