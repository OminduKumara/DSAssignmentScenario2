package payment.timesync;

public class main {
    public static void main(String[] args) {
        try {
            
            TimeSync timeSync = new TimeSync("pool.ntp.org", 123);

            
            LogReorderer reorderer = new LogReorderer(2000, timeSync);

            
            reorderer.add(new LogEntry(System.currentTimeMillis() - 1500, "ServerA", "Payment processed"));
            reorderer.add(new LogEntry(System.currentTimeMillis() - 500, "ServerB", "Request received"));
            reorderer.add(new LogEntry(System.currentTimeMillis() - 2500, "ServerC", "Connection established"));

            
            Thread.sleep(3000);

            
            LogEntry[] ordered = reorderer.flush();

            
            System.out.println("=== Reordered Logs ===");
            for (LogEntry entry : ordered) {
                System.out.println(entry);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
