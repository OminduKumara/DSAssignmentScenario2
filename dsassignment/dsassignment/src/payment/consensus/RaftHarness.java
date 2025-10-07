package payment.consensus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class RaftHarness {
    public static void main(String[] args) throws Exception {
        int n = 3;
        List<RaftNode> nodes = new ArrayList<>();
        
        for (int i = 0; i < n; i++) nodes.add(null);
        for (int i = 0; i < n; i++) nodes.set(i, new RaftNode(i, nodes, null));

        
        Thread.sleep(1000);

        
        final RaftNode[] leaderRef = new RaftNode[1];
        for (RaftNode node : nodes) if (node.getRole() == RaftNode.Role.LEADER) leaderRef[0] = node;
        if (leaderRef[0] == null) {
            System.err.println("No leader elected");
            return;
        }
        System.out.println("Leader is: " + leaderRef[0]);

        
        ExecutorService ex = Executors.newFixedThreadPool(8);
        int commands = 200;
        for (int i = 0; i < commands; i++) {
            final int idx = i;
            ex.submit(() -> {
                boolean ok = (boolean) leaderRef[0].appendEntry("PAY " + idx);
                if (!ok) System.err.println("Append failed for " + idx);
            });
        }

        ex.shutdown();
        ex.awaitTermination(10, TimeUnit.SECONDS);

        
        for (int i = 0; i < nodes.size(); i++) {
            System.out.println("Node " + i + " log size=" + nodes.get(i).getLog().size());
        }
        
        System.out.println("Simulating leader crash...");
        leaderRef[0].stop();
        Thread.sleep(500);
        
        Thread.sleep(1000);
        RaftNode newLeader = null;
        for (RaftNode node : nodes) if (node.isActive() && node.getRole() == RaftNode.Role.LEADER) newLeader = node;
        System.out.println("New leader after crash: " + newLeader);
        
        System.out.println("Restoring old leader...");
        leaderRef[0].startNode();
        Thread.sleep(1000);
        for (int i = 0; i < nodes.size(); i++) {
            System.out.println("Node " + i + " final log size=" + nodes.get(i).getLog().size());
        }
    }
}

