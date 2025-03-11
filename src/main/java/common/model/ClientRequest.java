package common.model;

public record ClientRequest(String id, Command command, Transaction transaction) {}
