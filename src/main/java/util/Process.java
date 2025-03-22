package util;


import javax.crypto.SecretKey;
import lombok.Getter;
import lombok.Setter;

@Getter
public class Process {
    private final int id;
    private final String host;
    private final int port;
    @Setter
    private SecretKey secretKey;

    public Process(int id, String host, int port) {
        this.id = id;
        this.host = host;
        this.port = port;
        this.secretKey = null;
    }
}
