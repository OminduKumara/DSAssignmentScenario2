package payment.consensus;

import java.io.Serializable;

public class RaftLogEntry implements Serializable {
    public final int term;
    public final String command; 

    public RaftLogEntry(int term, String command) {
        this.term = term;
        this.command = command;
    }

    @Override
    public String toString() {
        return String.format("{%d:%s}", term, command);
    }
}
