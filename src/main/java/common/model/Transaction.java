package common.model;

import util.SecurityUtil;

import java.security.PublicKey;

public record Transaction(String from, String to, long nonce, String data, int value, String signature) {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(Transaction.class);

    public String generateHash() {
        return SecurityUtil.generateHash(new byte[][]{this.toString().getBytes()});
    }
    private boolean verifySignature(PublicKey publicKey) throws Exception {
        boolean validSignature = SecurityUtil.verifySignature(this, publicKey);
        logger.info("Transaction signature from {} to {} with nonce {} is {}", from, to, nonce, validSignature ? "valid" : "invalid");
        return validSignature;
    }
    public boolean isValid(PublicKey publicKey) throws Exception {
        return from != null && signature != null && (data == null || data.length() >= 8) && verifySignature(publicKey);
    }
}
