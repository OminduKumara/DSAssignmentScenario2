package payment.timesync;

import java.util.PriorityQueue;
import java.util.Queue;


public class LogReorderer {
    private final long windowMs;
    private final Queue<LogEntry> pq = new PriorityQueue<>();
    private final TimeSync timeSync;
    private volatile long offsetMillis = 0;

    public LogReorderer(long windowMs, TimeSync timeSync) {
        this.windowMs = windowMs;
        this.timeSync = timeSync;
        try { offsetMillis = timeSync.queryOffsetMillis(); } catch (Exception e) { offsetMillis = 0; }
    }

    public synchronized void add(LogEntry entry) {
        entry.setCorrectedTsMillis(entry.getOriginalTsMillis() + offsetMillis);
        pq.offer(entry);
    }

    public synchronized LogEntry[] flush() {
        long now = System.currentTimeMillis() + offsetMillis;
        try { offsetMillis = timeSync.queryOffsetMillis(); } catch (Exception ignored) {}

        long cutoff = now - windowMs;
        java.util.List<LogEntry> out = new java.util.ArrayList<>();
        while (!pq.isEmpty()) {
            LogEntry head = pq.peek();
            if (head.getCorrectedTsMillis() <= cutoff) {
                out.add(pq.poll());
            } else break;
        }
        return out.toArray(new LogEntry[0]);
    }
}
