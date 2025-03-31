package common.model;

import util.SecurityUtil;

import java.security.PublicKey;

public record Transaction(String id, String from, String to, String data, int value, String signature) {
    public String generateHash() {
        return SecurityUtil.generateHash(new byte[][]{this.toString().getBytes()});
    }
    private boolean verifySignature(PublicKey publicKey) throws Exception {
        return SecurityUtil.verifySignature(this, publicKey);
    }
    public boolean isValid(PublicKey publicKey) throws Exception {
        return (data == null || data.length() >= 8) && verifySignature(publicKey);
    }
}
