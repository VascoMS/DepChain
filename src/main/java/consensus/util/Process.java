package consensus.util;



public class Process {
    private int id;
    private String host;
    private int port;
    private int clientPort;
    // private String publicKeyPath;
    // private String privateKeyPath;

    public Process(int id, String host, int port, int clientPort) {
        this.id = id;
        this.host = host;
        this.port = port;
        this.clientPort = clientPort;
    }

    public int getId() {
        return id;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public int getClientPort() {
        return clientPort;
    }
}
