package server.evm.model;

public record TransactionResult(Boolean result, String message) {
    public static TransactionResult success() {
        return new TransactionResult(true, null);
    }

    public static TransactionResult fail(String message) {
        return new TransactionResult(false, message);
    }

    public static TransactionResult success(String message) {
        return new TransactionResult(true, message);
    }

    public boolean isSuccess(){
        return result;
    }
}
