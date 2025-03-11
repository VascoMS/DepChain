package common.model;


public enum Command {

    APPEND("append"),
    READ("read");

    private final String command;

    Command(String command) {
        this.command = command;
    }

    public String getCommand() {
        return command;
    }
}