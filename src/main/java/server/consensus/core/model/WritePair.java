package server.consensus.core.model;

import common.model.Transaction;

public record WritePair(int timestamp, Transaction value) {}
