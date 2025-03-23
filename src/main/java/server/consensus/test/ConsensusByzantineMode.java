package server.consensus.test;

public enum ConsensusByzantineMode {
    // No byzantine behavior.
    NORMAL,
    // Drop all messages received.
    DROP_ALL
}
