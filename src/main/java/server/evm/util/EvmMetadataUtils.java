package server.evm.util;

import java.util.Map;

public class EvmMetadataUtils {
    private static final Map<String, String> METHOD_RETURN_TYPES = Map.ofEntries(
            Map.entry("44337ea1", "bool"),       // addToBlacklist(address) → bool
            Map.entry("dd62ed3e", "uint256"),    // allowance(address,address) → uint256
            Map.entry("095ea7b3", "bool"),       // approve(address,uint256) → bool
            Map.entry("70a08231", "uint256"),    // balanceOf(address) → uint256
            Map.entry("313ce567", "uint8"),      // decimals() → uint8 (small integer, treated as uint256)
            Map.entry("fe575a87", "bool"),       // isBlacklisted(address) → bool
            Map.entry("06fdde03", "string"),     // name() → string
            Map.entry("8da5cb5b", "address"),    // owner() → address
            Map.entry("537df3b6", "bool"),       // removeFromBlacklist(address) → bool
            Map.entry("715018a6", "void"),       // renounceOwnership() → No return value
            Map.entry("95d89b41", "string"),     // symbol() → string
            Map.entry("18160ddd", "uint256"),    // totalSupply() → uint256
            Map.entry("a9059cbb", "bool"),       // transfer(address,uint256) → bool
            Map.entry("23b872dd", "bool"),       // transferFrom(address,address,uint256) → bool
            Map.entry("f2fde38b", "void")        // transferOwnership(address) → No return value
    );

    public static String getMethodReturnType(String calldata) {
        String prefix = calldata.substring(0, 8);
        return METHOD_RETURN_TYPES.getOrDefault(prefix, "unknown");
    }
}
