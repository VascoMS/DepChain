package consensus.util;


import lombok.Getter;

@Getter
public class Process {
    private int id;
    private String host;
    private int port;
    private int clientPort;
    private String publicKeyPath;

    public Process(int id, String host, int port, int clientPort) {
        this.id = id;
        this.host = host;
        this.port = port;
        this.clientPort = clientPort;
        this.publicKeyPath = "src/main/java/consensus/util/keys/" + id + ".pubkey";
    }
}
