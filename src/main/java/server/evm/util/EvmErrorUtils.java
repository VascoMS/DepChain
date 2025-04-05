package server.evm.util;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

public class EvmErrorUtils {

    public enum ErrorCode {
        ERC20_INSUFFICIENT_BALANCE("e450d38c", "ERC20 Insufficient Balance for: %s Balance: %s Needed: %s", 3),
        ERC20_INVALID_SENDER("96c6fd1e", "ERC20 Invalid Sender: %s", 1),
        ERC20_INVALID_RECEIVER("ec442f05", "ERC20 Invalid Receiver: %s", 1),
        ERC20_INSUFFICIENT_ALLOWANCE("fb8f41b2", "ERC20 Insufficient Allowance: %s Allowance: %s Needed: %s", 3),
        ERC20_INVALID_SPENDER("94280d62", "ERC20 Invalid Spender: %s", 1),
        OWNABLE_UNAUTHORIZED_ACCOUNT("118cdaa7", "Ownable Unauthorized Account: %s", 1),
        OWNABLE_INVALID_OWNER("1e4fbdf7", "Ownable Invalid Owner: %s", 1),
        BLACKLISTED("ffa4e618", "Blacklisted Account: %s", 1);

        private final String signature;
        private final String messageTemplate;
        private final int parameterCount;

        ErrorCode(String signature, String messageTemplate, int parameterCount) {
            this.signature = signature;
            this.messageTemplate = messageTemplate;
            this.parameterCount = parameterCount;
        }

        public String getSignature() {
            return signature;
        }

        public String getMessageTemplate() {
            return messageTemplate;
        }

        public int getParameterCount() {
            return parameterCount;
        }

        public static ErrorCode fromSignature(String signature) {
            return Arrays.stream(values())
                    .filter(code -> code.signature.equals(signature))
                    .findFirst()
                    .orElse(null);
        }
    }

    public static String parseError(String error) {
        if (error == null || error.length() < 10) {
            return "Invalid Error Format";
        }

        String errorSignature = error.substring(2, 10);
        ErrorCode errorCode = ErrorCode.fromSignature(errorSignature);

        if (errorCode == null) {
            return "Unknown Error: " + errorSignature;
        }

        return switch (errorCode) {
            case ERC20_INSUFFICIENT_BALANCE, ERC20_INSUFFICIENT_ALLOWANCE -> String.format(
                    errorCode.getMessageTemplate(),
                    extractParameter(error, 0),
                    extractParameter(error, 1),
                    extractParameter(error, 2)
            );
            case ERC20_INVALID_SENDER,
                    ERC20_INVALID_RECEIVER,
                    ERC20_INVALID_SPENDER,
                    OWNABLE_UNAUTHORIZED_ACCOUNT,
                    OWNABLE_INVALID_OWNER,
                    BLACKLISTED -> String.format(
                    errorCode.getMessageTemplate(),
                    extractParameter(error, 0)
            );
        };
    }

    private static String extractParameter(String errorData, int index) {
        return "0x" + trimLeadingZeros(errorData.substring(10 + 64 * index, 10 + 64 * (index + 1)));
    }

    public static String getError(ByteArrayOutputStream byteArrayOutputStream) {
        String output = byteArrayOutputStream.toString();
        String[] lines = output.split("\\r?\\n");
        if (lines.length == 0) {
            return null;
        }

        JsonObject jsonObject = JsonParser.parseString(lines[lines.length - 1]).getAsJsonObject();
        return jsonObject.get("error") != null ? jsonObject.get("error").getAsString() : null;
    }



    public static String trimLeadingZeros(String hexString) {
        if (hexString.startsWith("0x")) {
            hexString = hexString.substring(2);
        }

        hexString = hexString.replaceFirst("^0+", "");

        return hexString.isEmpty() ? "0" : hexString;
    }
}