package payment.datareplication;

import java.io.File;
import java.util.Arrays;

public class main {
    public static void main(String[] args) {
        try {
            // Create a data directory for test
            String dataDir = "testdata";
            new File(dataDir).mkdirs();

            // Initialize a Ledger instance
            Ledger ledger = new Ledger(dataDir);

            System.out.println("=== Initial Ledger State ===");
            System.out.println("Commit Index: " + ledger.getCommitIndex());
            System.out.println("Seen IDs: " + ledger.seen("tx1"));

            // Append entries as a leader
            System.out.println("\n=== Leader Appending Entries ===");
            int seq1 = ledger.appendLeader("tx1", "PAYMENT_100");
            int seq2 = ledger.appendLeader("tx2", "PAYMENT_200");
            int seq3 = ledger.appendLeader("tx3", "PAYMENT_300");

            System.out.println("Appended sequences: " + seq1 + ", " + seq2 + ", " + seq3);
            System.out.println("Commit Index before: " + ledger.getCommitIndex());

            // Commit up to seq2
            ledger.advanceCommitIndex(seq2);
            System.out.println("Commit Index after advance: " + ledger.getCommitIndex());

            // Display WAL contents
            System.out.println("\n=== WAL Contents ===");
            String[] wal = ledger.getWalFrom(0);
            for (String line : wal) {
                System.out.println(line);
            }

            // Simulate a follower catching up from WAL
            System.out.println("\n=== Simulating Follower Append ===");
            Ledger follower = new Ledger("followerdata");
            for (String entry : wal) {
                // Parse: seq id payload
                String[] parts = entry.split(" ", 3);
                int seq = Integer.parseInt(parts[0]);
                String id = parts[1];
                String payload = parts[2];
                follower.appendFollower(seq, id, payload);
            }

            System.out.println("Follower WAL:");
            System.out.println(Arrays.toString(follower.getWalFrom(0)));

            // Compact IDs and snapshot if needed
            ledger.compactIds();
            System.out.println("\nIDs compacted successfully.");

            // Simulate snapshot threshold test
            System.out.println("\n=== Snapshot Simulation ===");
            for (int i = 4; i < 110; i++) {
                ledger.appendLeader("tx" + i, "PAYMENT_" + (i * 100));
            }
            ledger.advanceCommitIndex(105);
            System.out.println("Snapshot test done, commitIndex=" + ledger.getCommitIndex());

            System.out.println("\n=== Test Completed Successfully ===");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
