package common.model;

public record Transaction(String id, int from, int to, String data, String signature) {}
