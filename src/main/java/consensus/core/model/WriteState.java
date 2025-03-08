package consensus.core.model;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
public class WriteState {
    @Setter
    private WritePair lastestWrite;
    private final List<WritePair> writeSet;

    public WriteState() {
        this.lastestWrite = null;
        this.writeSet = new ArrayList<>();
    }

    public WriteState(WritePair lastestWrite, List<WritePair> writeSet) {
        this.lastestWrite = lastestWrite;
        this.writeSet = writeSet;
    }

    public void addToWriteSet(WritePair writePair) {
        writeSet.add(writePair);
    }
}