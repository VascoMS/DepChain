package server.blockchain.model;

import common.model.Transaction;
import lombok.Getter;
import util.MerkleTree;
import util.SecurityUtil;

import java.util.List;

@Getter
public class Block {
    private final String parentHash;
    private final String blockHash;
    private final long timestamp;
    private final List<Transaction> transactions;
    //private String signature;

    public Block(String parentHash, List<Transaction> transactions, long timestamp) {
        this.parentHash = parentHash;
        this.transactions = transactions;
        this.timestamp = timestamp;
        this.blockHash = generateBlockHash();
    }

    public String generateBlockHash() {
        List<String> transactionHashes = transactions.stream().map(Transaction::generateHash).toList();
        String merkleRoot = MerkleTree.getMerkleRoot(transactionHashes);
        byte[][] data = {
                parentHash.getBytes(),
                SecurityUtil.longToBytes(timestamp),
                merkleRoot != null ? merkleRoot.getBytes() : null,
        };
        return SecurityUtil.generateHash(data);
    }

    /*protected void signBlock(PrivateKey privateKey) throws Exception {
        this.signature = SecurityUtil.signString(this.blockHash, privateKey);
    }

    protected boolean verifyBlockSignature(PublicKey publicKey) throws Exception {
        return SecurityUtil.verifySignature(this.blockHash, this.signature, publicKey);
    }*/
}
