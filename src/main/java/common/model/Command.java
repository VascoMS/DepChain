package common.model;


public enum Command {

    APPEND("append"),
    READ("read"),
    BALANCE("balance"),
    TRANSFER("transfer"),
    ADD_TO_BLACKLIST("add-blacklist"),
    REMOVE_FROM_BLACKLIST("remove-blacklist");

    private final String command;

    Command(String command) {
        this.command = command;
    }

    public String getCommand() {
        return command;
    }
}