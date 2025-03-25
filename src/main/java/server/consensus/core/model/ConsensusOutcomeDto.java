package server.consensus.core.model;

import server.blockchain.model.Block;

public record ConsensusOutcomeDto(int id, Block decision) {}
