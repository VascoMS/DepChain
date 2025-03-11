package server.consensus.core.model;

import common.model.Transaction;

public record ConsensusOutcomeDto(int id, Transaction decision) {}
