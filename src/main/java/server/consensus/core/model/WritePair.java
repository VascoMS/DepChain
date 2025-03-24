package server.consensus.core.model;

import server.blockchain.model.Block;

public record WritePair(int timestamp, Block value) {}
