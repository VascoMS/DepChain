package common.model;

public record ServerResponse(String requestId, boolean success, String payload) {

    public static ServerResponse timeout(String requestId) {
        return new ServerResponse(requestId, false, "Request timed out");
    }

}
