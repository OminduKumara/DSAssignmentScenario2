package payment.timesync;


public class LogEntry implements Comparable<LogEntry> {
    private final long originalTsMillis;
    private long correctedTsMillis;
    private final String source;
    private final String message;

    public LogEntry(long originalTsMillis, String source, String message) {
        this.originalTsMillis = originalTsMillis;
        this.correctedTsMillis = originalTsMillis;
        this.source = source;
        this.message = message;
    }

    public long getOriginalTsMillis() { return originalTsMillis; }
    public long getCorrectedTsMillis() { return correctedTsMillis; }
    public void setCorrectedTsMillis(long ts) { this.correctedTsMillis = ts; }
    public String getSource() { return source; }
    public String getMessage() { return message; }

    @Override
    public int compareTo(LogEntry o) {
        return Long.compare(this.correctedTsMillis, o.correctedTsMillis);
    }

    @Override
    public String toString() {
        return String.format("%d [%s] %s", correctedTsMillis, source, message);
    }
}
