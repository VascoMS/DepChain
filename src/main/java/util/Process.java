package util;

import lombok.Getter;

@Getter
public class Process {
    private final String id;
    private final String host;
    private final int port;

    public Process(String id, String host, int port) {
        this.id = id;
        this.host = host;
        this.port = port;
    }
}
