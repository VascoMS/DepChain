package consensus.core.model;

public record Transaction(String id, String clientId, String content, String signature) {}
