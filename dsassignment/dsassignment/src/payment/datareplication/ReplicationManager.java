package payment.datareplication;

import org.apache.zookeeper.ZooKeeper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


public class ReplicationManager {
    private final ZooKeeper zk;
    private final String myZnodeName;

    public ReplicationManager(ZooKeeper zk, String myZnodeName) {
        this.zk = zk;
        this.myZnodeName = myZnodeName;
    }

    public String getLeaderNode() {
        try {
            List<String> children = zk.getChildren("/payment/nodes", false);
            if (children.isEmpty()) return null;
            Collections.sort(children);
            String leaderChild = children.get(0);
            byte[] data = zk.getData("/payment/nodes/" + leaderChild, false, null);
            return new String(data, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    public List<String> getOtherNodes() {
        try {
            List<String> children = zk.getChildren("/payment/nodes", false);
            List<String> res = new ArrayList<>();
            for (String child : children) {
                if (child.equals(myZnodeName)) continue;
                try {
                    byte[] data = zk.getData("/payment/nodes/" + child, false, null);
                    res.add(new String(data, StandardCharsets.UTF_8));
                } catch (Exception e) {
                    // ignore
                }
            }
            return res;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    
    public int replicateAndCount(int seq, String id, String payload) {
        List<String> followers = getOtherNodes();
        int acks = 1; 

        for (String node : followers) {
            if (!isAlive(node)) {
                System.err.println("Skipping dead follower: " + node);
                continue;
            }
            String[] hp = node.split(":");
            String fh = hp[0];
            int fp = Integer.parseInt(hp[1]);
            try (Socket rs = new Socket(fh, fp);
                 PrintWriter rout = new PrintWriter(new OutputStreamWriter(rs.getOutputStream(), StandardCharsets.UTF_8), true);
                 BufferedReader rin = new BufferedReader(new InputStreamReader(rs.getInputStream(), StandardCharsets.UTF_8))) {
                rout.println("REPLSEQ " + seq + " " + id + " " + payload);
                String ack = rin.readLine();
                if (ack != null && ack.equals("ACK")) {
                    acks++;
                }
            } catch (Exception e) {
                System.err.println("Replication to " + node + " failed: " + e.getMessage());
            }
        }
        return acks;
    }

   
    public boolean isAlive(String node) {
        try {
            String[] hp = node.split(":");
            try (Socket s = new Socket()) {
                s.connect(new java.net.InetSocketAddress(hp[0], Integer.parseInt(hp[1])), 1000);
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    
    public String[] fetchWalFrom(String node, int fromIndex) {
        try {
            String[] hp = node.split(":");
            try (Socket s = new Socket(hp[0], Integer.parseInt(hp[1]));
                 PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
                 BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
                out.println("WALGET " + fromIndex);
                java.util.List<String> lines = new java.util.ArrayList<>();
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.equals("END")) break;
                    lines.add(line);
                }
                return lines.toArray(new String[0]);
            }
        } catch (Exception e) {
            System.err.println("Failed to fetch WAL from " + node + ": " + e.getMessage());
            return new String[0];
        }
    }
}
