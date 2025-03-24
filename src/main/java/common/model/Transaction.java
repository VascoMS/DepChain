package common.model;

import util.SecurityUtil;

import java.security.PublicKey;

public record Transaction(String id, int from, int to, String data, String signature) {
    public String generateHash() {
        return SecurityUtil.generateHash(new byte[][]{this.toString().getBytes()});
    }
    public boolean verifySignature(PublicKey publicKey) throws Exception {
        return SecurityUtil.verifySignature(this, null);
    }
}
