package common.model;

public record ClientRequest(String id, TransactionType command, Transaction transaction) {}
