package common.model;

public record ServerResponse(String requestId, boolean success, String payload) {}
