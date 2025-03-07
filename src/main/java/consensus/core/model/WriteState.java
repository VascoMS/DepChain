package consensus.core.model;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
public class WriteState {
    @Setter
    private final WritePair lastestWrite;
    private final List<WritePair> writeSet;

    public WriteState(WritePair lastestWrite, List<WritePair> writeSet) {
        this.lastestWrite = lastestWrite;
        this.writeSet = writeSet;
    }

    public void addToWriteSet(WritePair writePair) {
        writeSet.add(writePair);
    }
}