import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.tuweni.bytes.Bytes;

import org.hyperledger.besu.evm.*;
import org.hyperledger.besu.evm.fluent.EVMExecutor;
import org.hyperledger.besu.evm.tracing.StandardJsonTracer;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

public class Main {

    public static void main(String[] args) {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        PrintStream printStream = new PrintStream(byteArrayOutputStream);
        StandardJsonTracer tracer = new StandardJsonTracer(printStream, true, true, true, true);

        var executor = EVMExecutor.evm(EvmSpecVersion.CANCUN);
        executor.tracer(tracer);
        executor.code(Bytes.fromHexString("608060405234801561000f575f80fd5b506004361061003f575f3560e01c806382ab890a14610043578063ef48e0bd1461005f578063f1351b931461007d575b5f80fd5b61005d6004803603810190610058919061017b565b61009b565b005b6100676100f8565b60405161007491906101b5565b60405180910390f35b61008561013c565b60405161009291906101b5565b60405180910390f35b8060015f3373ffffffffffffffffffffffffffffffffffffffff1673ffffffffffffffffffffffffffffffffffffffff1681526020019081526020015f208190555060015f808282546100ee91906101fb565b9250508190555050565b5f60015f3373ffffffffffffffffffffffffffffffffffffffff1673ffffffffffffffffffffffffffffffffffffffff1681526020019081526020015f2054905090565b5f8054905090565b5f80fd5b5f819050919050565b61015a81610148565b8114610164575f80fd5b50565b5f8135905061017581610151565b92915050565b5f602082840312156101905761018f610144565b5b5f61019d84828501610167565b91505092915050565b6101af81610148565b82525050565b5f6020820190506101c85f8301846101a6565b92915050565b7f4e487b71000000000000000000000000000000000000000000000000000000005f52601160045260245ffd5b5f61020582610148565b915061021083610148565b9250828201905080821115610228576102276101ce565b5b9291505056fea26469706673582212200ed788b11510dd78fa61810af7b19d47fdd57af4250f9da0deb53375c3d4ca1164736f6c634300081a0033"));
        executor.callData(Bytes.fromHexString("45773e4e"));

        executor.execute();

        String string = extractStringFromReturnData(byteArrayOutputStream);
        System.out.println("Output string of 'sayHelloWorld():' " + string);
    }

    public static String extractStringFromReturnData(ByteArrayOutputStream byteArrayOutputStream) {
        String[] lines = byteArrayOutputStream.toString().split("\\r?\\n");
        JsonObject jsonObject = JsonParser.parseString(lines[lines.length-1]).getAsJsonObject();

        String memory = jsonObject.get("memory").getAsString();

        JsonArray stack = jsonObject.get("stack").getAsJsonArray();
        int offset = Integer.decode(stack.get(stack.size()-1).getAsString());
        int size = Integer.decode(stack.get(stack.size()-2).getAsString());

        String returnData = memory.substring(2 + offset * 2, 2 + offset * 2 + size * 2);

        int stringOffset = Integer.decode("0x"+returnData.substring(0, 32 * 2));
        int stringLength = Integer.decode("0x"+returnData.substring(stringOffset * 2, stringOffset * 2 + 32 * 2));
        String hexString = returnData.substring(stringOffset * 2 + 32 * 2, stringOffset * 2 + 32 * 2 + stringLength * 2);

        return new String(hexStringToByteArray(hexString), StandardCharsets.UTF_8);
    }

    public static byte[] hexStringToByteArray(String hexString) {
        int length = hexString.length();
        byte[] byteArray = new byte[length / 2];

        for (int i = 0; i < length; i += 2) {
            int value = Integer.parseInt(hexString.substring(i, i + 2), 16);
            byteArray[i / 2] = (byte) value;
        }

        return byteArray;
    }

}