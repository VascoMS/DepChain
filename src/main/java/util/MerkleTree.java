package util;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

public class MerkleTree {

    public static String getMerkleRoot(List<String> txnLists) {
        if(txnLists.isEmpty()){
            return null;
        }
        List<String> merkleTree = merkleTree(txnLists);
        return merkleTree.get(0); // return root
    }

    private static List<String> merkleTree(List<String> hashList) {
        //Return the Merkle Root
        if(hashList.size() == 1){
            return hashList;
        }
        ArrayList<String> parentHashList=new ArrayList<>();
        //Hash the leaf transaction pair to get parent transaction
        for(int i = 0; i < hashList.size(); i += 2) {
            if(i == hashList.size() - 1) {
                String lastHash= hashList.get(hashList.size()-1);
                String hashedString = getSHA(lastHash.concat(lastHash));
                parentHashList.add(hashedString);
            } else {
                String hashedString = getSHA(hashList.get(i).concat(hashList.get(i+1)));
                parentHashList.add(hashedString);
            }
        }
        return merkleTree(parentHashList);
    }

    public static String getSHA(String input) {
        try {

            // Static getInstance method is called with hashing SHA
            MessageDigest md = MessageDigest.getInstance("SHA-256");

            // digest() method called
            // to calculate message digest of an input
            // and return array of byte
            byte[] messageDigest = md.digest(input.getBytes());

            // Convert byte array into signum representation
            BigInteger no = new BigInteger(1, messageDigest);

            // Convert message digest into hex value
            String hashtext = no.toString(16);

            while (hashtext.length() < 32) {
                hashtext = "0" + hashtext;
            }

            return hashtext;
        }

        // For specifying wrong message digest algorithms
        catch (NoSuchAlgorithmException e) {
            System.out.println("Exception thrown"
                    + " for incorrect algorithm: " + e);

            return null;
        }
    }

}