package client.app;

enum TokenType {
    DEPCOIN("DEP"), ISTCOIN("IST");

    String symbol;

    TokenType(String symbol) { this.symbol = symbol; }

    static TokenType getTokenTypeFromSymbol(String symbol) {
        switch (symbol) {
            case "DEP" -> { return DEPCOIN; }
            case "IST" -> { return ISTCOIN; }
            default -> { return null; }
        }
    }
}
