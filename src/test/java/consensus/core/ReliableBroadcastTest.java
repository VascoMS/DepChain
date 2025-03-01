package consensus.core;

import consensus.exception.LinkException;
import consensus.util.Process;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

public class ReliableBroadcastTest {

    private static Process aliceProcess;
    private static Process bobProcess;
    private static Process carlProcess;
    private static Process jeffProcess;

    private static Link aliceLink;
    private static Link bobLink;
    private static Link carlLink;
    private static Link jeffLink;

    private static ReliableBroadcast aliceBroadcast;
    private static ReliableBroadcast bobBroadcast;
    private static ReliableBroadcast carlBroadcast;
    private static ReliableBroadcast jeffBroadcast;

    @BeforeAll
    public static void startLinks() throws Exception {
        // Assemble
        aliceProcess = new Process(1, "localhost", 1024, 1024);
        bobProcess = new Process(2, "localhost", 1025, 1025);
        carlProcess = new Process(3, "localhost", 1026, 1026);
        jeffProcess = new Process(4, "localhost", 1027, 1027);

        aliceLink = new Link(
                aliceProcess,
                new Process[]{bobProcess, carlProcess, jeffProcess},
                200
        );

        bobLink = new Link(
                bobProcess,
                new Process[]{aliceProcess, carlProcess, jeffProcess},
                200
        );

        carlLink = new Link(
                carlProcess,
                new Process[]{bobProcess, aliceProcess, jeffProcess},
                200
        );

        jeffLink = new Link(
                jeffProcess,
                new Process[]{bobProcess, carlProcess, aliceProcess},
                200
        );
    }

    @BeforeEach
    public void startBroadcast() throws LinkException {
        aliceBroadcast = new ReliableBroadcast(
                aliceProcess,
                new Process[]{bobProcess, carlProcess, jeffProcess},
                aliceLink,
                1
        );

        bobBroadcast = new ReliableBroadcast(
                bobProcess,
                new Process[]{aliceProcess, carlProcess, jeffProcess},
                bobLink,
                1
        );

        carlBroadcast = new ReliableBroadcast(
                carlProcess,
                new Process[]{bobProcess, aliceProcess, jeffProcess},
                carlLink,
                1
        );

        jeffBroadcast = new ReliableBroadcast(
                jeffProcess,
                new Process[]{bobProcess, carlProcess, aliceProcess},
                jeffLink,
                1
        );
    }

    @Test
    public void simpleBroadcast() throws LinkException {
        // Assemble
        Message aliceMessage = new Message(1, Message.Type.WRITE, "hello.");
        // Act
        aliceBroadcast.broadcast(aliceMessage);
        // Assert
        assertEquals(aliceMessage, aliceBroadcast.collect());
        assertEquals(aliceMessage, bobBroadcast.collect());
        assertEquals(aliceMessage, carlBroadcast.collect());
        assertEquals(aliceMessage, jeffBroadcast.collect());
    }

    @Test
    public void excludedOneByzantineBroadcast() throws LinkException {
        // Assemble
        Message byzantineMessage = new Message(4, Message.Type.SEND, "ha. ha.");

        // Act
        // Only sending to Alice and Bob, Carl doesn't get sent the message.
        jeffLink.send(1, byzantineMessage);
        jeffLink.send(2, byzantineMessage);
        jeffLink.send(4, byzantineMessage);

        // Assert
        assertEquals(byzantineMessage, aliceBroadcast.collect());
        assertEquals(byzantineMessage, bobBroadcast.collect());
        assertEquals(byzantineMessage, carlBroadcast.collect());
        assertEquals(byzantineMessage, jeffBroadcast.collect());
    }

    @Test
    public void differentMessagesByzantineBroadcast() throws LinkException {
        // Assemble
        Message normalMessage = new Message(4, Message.Type.SEND, "smiley face.");
        Message byzantineMessage = new Message(4, Message.Type.SEND, "ha. ha.");

        // Act
        // Sending normalMessage to Alice and Bob, but Carl gets sent another message.
        jeffLink.send(1, normalMessage);
        jeffLink.send(2, normalMessage);
        jeffLink.send(3, byzantineMessage);
        jeffLink.send(4, normalMessage);

        // Assert
        assertEquals(normalMessage, aliceBroadcast.collect());
        assertEquals(normalMessage, bobBroadcast.collect());
        assertEquals(normalMessage, carlBroadcast.collect());
        assertEquals(normalMessage, jeffBroadcast.collect());
    }

    @AfterAll
    public static void stopLinks() {
        aliceLink.close();
        bobLink.close();
        carlLink.close();
        jeffLink.close();
    }
}
