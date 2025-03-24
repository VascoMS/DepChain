package server.consensus.core.model;

import common.model.Transaction;
import server.blockchain.model.Block;

public record ConsensusOutcomeDto(int id, Block decision) {}
