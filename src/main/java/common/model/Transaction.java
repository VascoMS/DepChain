package common.model;

public record Transaction(String id, int clientId, String content, String signature) {}
