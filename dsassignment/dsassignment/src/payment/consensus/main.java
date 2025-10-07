
package payment.consensus;

import java.util.*;
import java.util.concurrent.*;

public class main{

    public static void main(String[] args) throws Exception {
        int n = 3; 
        List<RaftNode> nodes = new ArrayList<>();

        
        for (int i = 0; i < n; i++) nodes.add(null);

        
        for (int i = 0; i < n; i++) {
            RaftNode node = new RaftNode(i, nodes, new RaftEventListener() {
                @Override
                public void onLeaderElected(int nodeId, int term) {
                    System.out.println("[EVENT] Leader elected: Node " + nodeId + " (Term " + term + ")");
                }

                @Override
                public void onLogMessage(int nodeId, String message) {
                    
                }
            });
            nodes.set(i, node);
        }

        
        for (RaftNode node : nodes) node.startNode();

       
        Thread.sleep(2000);

        RaftNode leader = null;
        for (RaftNode node : nodes) {
            if (node.getRole() == RaftNode.Role.LEADER) {
                leader = node;
                break;
            }
        }

        if (leader == null) {
            System.err.println(" No leader elected.");
            return;
        }
        System.out.println(" Leader elected: " + leader);

        
        ExecutorService executor = Executors.newFixedThreadPool(5);
        int numCommands = 10;
        final RaftNode finalLeader = leader;
        for (int i = 0; i < numCommands; i++) {
            final int cmdId = i;
            executor.submit(() -> {
                boolean ok = finalLeader.appendEntry("PAY " + cmdId);
                if (!ok) System.err.println("Append failed for command " + cmdId);
            });
        }
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        
        for (int i = 0; i < nodes.size(); i++) {
            System.out.println("Node " + i + " log size = " + nodes.get(i).getLog().size());
        }

        
        System.out.println("\n Simulating leader crash: " + leader.getNodeId());
        leader.stop();

        Thread.sleep(2000); 

        RaftNode newLeader = null;
        for (RaftNode node : nodes) {
            if (node.isActive() && node.getRole() == RaftNode.Role.LEADER) {
                newLeader = node;
                break;
            }
        }

        System.out.println("New leader elected after crash: " + newLeader);

       
        System.out.println("\nRestarting old leader...");
        leader.startNode();

        Thread.sleep(2000);

        
        for (int i = 0; i < nodes.size(); i++) {
            System.out.println("Node " + i + " final log size = " + nodes.get(i).getLog().size());
        }

        System.out.println("\n✅ Simulation complete.");
    }
}

