package common.primitives;

public enum LinkType {
    // I am server and my peers are servers.
    SERVER_TO_SERVER,
    // I am client and my peers are servers.
    CLIENT_TO_SERVER,
    // I am server and my peers are clients.
    SERVER_TO_CLIENT
}
