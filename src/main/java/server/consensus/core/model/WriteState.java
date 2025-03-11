package server.consensus.core.model;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
public class WriteState {
    @Setter
    private WritePair latestWrite;
    private final List<WritePair> writeSet;

    public WriteState() {
        this.latestWrite = null;
        this.writeSet = new ArrayList<>();
    }

    public WriteState(WritePair lastestWrite, List<WritePair> writeSet) {
        this.latestWrite = lastestWrite;
        this.writeSet = writeSet;
    }

    public void addToWriteSet(WritePair writePair) {
        writeSet.add(writePair);
    }

    public void clear() {
        latestWrite = null;
        writeSet.clear();
    }
}