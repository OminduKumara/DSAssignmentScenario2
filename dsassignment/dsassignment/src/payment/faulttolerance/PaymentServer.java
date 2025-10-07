package payment.faulttolerance;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.io.File;
import java.io.FileOutputStream;
import payment.datareplication.ReplicationManager;
import payment.datareplication.Ledger;
import payment.timesync.LogEntry;
import payment.timesync.LogReorderer;
import payment.timesync.TimeSync;
import payment.NodeLogListener;


public class PaymentServer {
    private NodeLogListener nodeLogListener = null;
    private final String zkConnect;
    private final String host;
    private final int port;
    private ZooKeeper zk;
    private ServerSocket serverSocket;
    private ThreadPoolExecutor pool;
    private String myZnodeName;
    private volatile boolean isLeader = false;
    private Ledger ledger;
    private ReplicationManager repl;
    private LogReorderer reorderer;
    private ScheduledExecutorService background;
    private final Map<String, Boolean> followerAlive = new HashMap<>();

    public PaymentServer(String zkConnect, String host, int port) {
        this.zkConnect = zkConnect;
        this.host = host;
        this.port = port;
    }

    public void setNodeLogListener(NodeLogListener listener) {
        this.nodeLogListener = listener;
    }

    private void logToNodeTerminal(String msg) {
        System.out.println(msg);
        if (nodeLogListener != null) {
            nodeLogListener.onNodeLog(port, msg); 
        }
    }

    public void start() throws Exception {
        
    logToNodeTerminal("[SERVER] Connecting to ZooKeeper at " + zkConnect);
        zk = new ZooKeeper(zkConnect, 3000, new Watcher() {
            @Override
            public void process(WatchedEvent event) {
                logToNodeTerminal("[SERVER] ZooKeeper event: " + event);
                if (event.getType() == Watcher.Event.EventType.NodeChildrenChanged && event.getPath() != null && event.getPath().equals("/payment/nodes")) {
                    try {
                        updateLeaderStatus();
                    } catch (Exception e) {
                        System.err.println("Error updating leader status: " + e.getMessage());
                    }
                }
            }
        });

        
        ledger = new Ledger("data/server-" + port);
        
        try {
            TimeSync ts = new TimeSync("pool.ntp.org", 123);
            reorderer = new LogReorderer(500, ts);
        } catch (Exception e) {
            reorderer = null;
        }

        background = Executors.newSingleThreadScheduledExecutor();
        
        background.scheduleAtFixedRate(() -> {
            try { ledger.compactIds(); } catch (Exception ignored) {}
        }, 60, 60, TimeUnit.SECONDS);
        
        background.scheduleAtFixedRate(() -> {
            try {
                if (reorderer != null) {
                    LogEntry[] entries = reorderer.flush();
                    if (entries.length > 0) {
                        File out = new File("data/server-" + port + "/ordered.log");
                        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(out, true), StandardCharsets.UTF_8), true)) {
                            for (LogEntry e : entries) pw.println(e.toString());
                        }
                    }
                }
            } catch (Exception ignored) {}
        }, 1, 1, TimeUnit.SECONDS);

        
        String base = "/payment/nodes";
        try {
            if (zk.exists("/payment", false) == null) {
                zk.create("/payment", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }
        } catch (KeeperException.NodeExistsException e) {
            
        }
        try {
            if (zk.exists(base, false) == null) {
                zk.create(base, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }
        } catch (KeeperException.NodeExistsException e) {
            
        }

    String path = zk.create(base + "/node-",
        (host + ":" + port).getBytes(StandardCharsets.UTF_8),
        ZooDefs.Ids.OPEN_ACL_UNSAFE,
        CreateMode.EPHEMERAL_SEQUENTIAL);

    logToNodeTerminal("Registered node: " + path);
    
    myZnodeName = path.substring(path.lastIndexOf('/') + 1);
    
    repl = new ReplicationManager(zk, myZnodeName);
    updateLeaderStatus();

    
    background.scheduleAtFixedRate(() -> {
        try {
            List<String> others = repl.getOtherNodes();
            for (String n : others) {
                boolean alive = repl.isAlive(n);
                followerAlive.put(n, alive);
            }
        } catch (Exception ignored) {}
    }, 2, 2, TimeUnit.SECONDS);

        serverSocket = new ServerSocket(port);
        pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(8);

    logToNodeTerminal("PaymentServer listening on " + host + ":" + port);

        while (!serverSocket.isClosed()) {
            Socket client = serverSocket.accept();
            pool.submit(() -> handleClient(client));
        }
    }

    public boolean isFollowerAlive(String node) {
        Boolean b = followerAlive.get(node);
        return b != null && b;
    }

    private void handleClient(Socket client) {
        try (Socket s = client;
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true)) {

            String line = in.readLine();
            logToNodeTerminal("[SERVER] Connection from " + s.getRemoteSocketAddress() + ", received: " + line);
            
            if (line == null) {
                out.println("ERR Empty");
                logToNodeTerminal("[SERVER] Sent: ERR Empty");
                return;
            }

            if (line.startsWith("REPLSEQ ")) {
               
                String[] parts = line.split(" ", 4);
                if (parts.length < 4) {
                    out.println("ERR");
                    logToNodeTerminal("[SERVER] Sent: ERR (bad REPLSEQ)");
                } else {
                    try {
                        int seq = Integer.parseInt(parts[1]);
                        String id = parts[2];
                        String payload = parts[3];
                        boolean ok = ledger.appendFollower(seq, id, payload);
                        out.println(ok ? "ACK" : "ACK");
                        logToNodeTerminal("[SERVER] Replication (REPLSEQ) for id=" + id + ", seq=" + seq + ", payload=" + payload + ", result=ACK");
                    } catch (NumberFormatException nfe) {
                        out.println("ERR");
                        logToNodeTerminal("[SERVER] Sent: ERR (bad seq in REPLSEQ)");
                    }
                }
                return;
            }

            if (line.startsWith("REPL ")) {
                
                String[] parts = line.split(" ", 3);
                if (parts.length < 3) {
                    out.println("ERR");
                    logToNodeTerminal("[SERVER] Sent: ERR (bad REPL)");
                } else {
                    String id = parts[1];
                    String payload = parts[2];
                    int seq = ledger.getWalFrom(0).length; 
                    try {
                        boolean ok = ledger.appendFollower(seq, id, payload);
                        out.println(ok ? "ACK" : "ACK");
                        logToNodeTerminal("[SERVER] Replication (REPL) for id=" + id + ", seq=" + seq + ", payload=" + payload + ", result=ACK");
                    } catch (Exception e) {
                        out.println("ERR");
                        logToNodeTerminal("[SERVER] Sent: ERR (exception in REPL)");
                    }
                }
                return;
            }

            if (line.startsWith("WALGET ")) {
                String[] parts = line.split(" ", 2);
                int from = 0;
                try { from = Integer.parseInt(parts[1]); } catch (Exception ignored) {}
                String[] entries = ledger.getWalFrom(from);
                for (String e : entries) out.println(e);
                out.println("END");
                logToNodeTerminal("[SERVER] WALGET from=" + from + ", sent " + entries.length + " entries");
                return;
            }

            if (line.startsWith("PAY")) {
                String[] parts = line.split(" ", 3);
                if (parts.length < 3) {
                    out.println("ERR BadFormat");
                    logToNodeTerminal("[SERVER] Sent: ERR BadFormat");
                    return;
                }
                String amount = parts[1];
                String id = parts[2];
                String payload = amount; 

                if (isLeader) {
                    
                    int seq = ledger.appendLeader(id, payload);
                    if (seq < 0) {
                        
                        out.println("OK");
                        logToNodeTerminal("[SERVER] Duplicate payment id=" + id + ", sent OK");
                        return;
                    }
                    
                    int ackCount = repl.replicateAndCount(seq, id, payload);
                    int total = repl.getOtherNodes().size() + 1;
                    int needed = total / 2 + 1;
                    logToNodeTerminal("[SERVER] Replicated payment id=" + id + ", seq=" + seq + ", ackCount=" + ackCount + ", needed=" + needed);
                    if (ackCount >= needed) {
                        
                        ledger.advanceCommitIndex(seq);
                        out.println("OK");
                        logToNodeTerminal("[SERVER] Payment committed id=" + id + ", seq=" + seq + ", sent OK");
                    } else {
                        
                        List<String> followers = repl.getOtherNodes();
                        for (String node : followers) {
                            try {
                                followerCatchup(node);
                            } catch (Exception e) {
                                System.err.println("Catchup failed for " + node + ": " + e.getMessage());
                            }
                        }
                        out.println("ERR ReplicationFailed");
                        logToNodeTerminal("[SERVER] Replication failed for id=" + id + ", sent ERR ReplicationFailed");
                    }
                } else {
                    
                    String leader = repl.getLeaderNode();
                    if (leader == null) {
                        out.println("ERR NoLeader");
                        logToNodeTerminal("[SERVER] Not leader, no leader found, sent ERR NoLeader");
                        return;
                    }
                    String[] hp = leader.split(":");
                    try (Socket ls = new Socket(hp[0], Integer.parseInt(hp[1]));
                         PrintWriter lout = new PrintWriter(new OutputStreamWriter(ls.getOutputStream(), StandardCharsets.UTF_8), true);
                         BufferedReader lin = new BufferedReader(new InputStreamReader(ls.getInputStream(), StandardCharsets.UTF_8))) {
                        lout.println(line);
                        String resp = lin.readLine();
                        out.println(resp == null ? "ERR" : resp);
                        logToNodeTerminal("[SERVER] Forwarded PAY to leader " + leader + ", got response: " + resp);
                    }
                }
                return;
            }

            out.println("ERR Unknown request");
            logToNodeTerminal("[SERVER] Sent: ERR Unknown request");

        } catch (Exception e) {
            System.err.println("Error handling client: " + e.getMessage());
            logToNodeTerminal("[SERVER] Exception: " + e.getMessage());
        }
    }

    private void followerCatchup(String node) {
        
        String[] remote = repl.fetchWalFrom(node, 0);
        int remoteSize = remote.length;
        int localSize = ledger.getWalFrom(0).length;
        if (remoteSize < localSize) {
            
            String[] missing = ledger.getWalFrom(remoteSize);
            for (String m : missing) {
                
                String[] parts = m.split(" ", 3);
                if (parts.length < 3) continue;
                int mseq = Integer.parseInt(parts[0]);
                String mid = parts[1];
                String mpayload = parts[2];
                
                try (Socket rs = new Socket(node.split(":")[0], Integer.parseInt(node.split(":")[1]));
                     PrintWriter rout = new PrintWriter(new OutputStreamWriter(rs.getOutputStream(), StandardCharsets.UTF_8), true);
                     BufferedReader rin = new BufferedReader(new InputStreamReader(rs.getInputStream(), StandardCharsets.UTF_8))) {
                    rout.println("REPLSEQ " + mseq + " " + mid + " " + mpayload);
                    String ack = rin.readLine();
                    String logMsg = "[SERVER] Sent missing entry to " + node + ": seq=" + mseq + ", id=" + mid + ", payload=" + mpayload + ", ack=" + ack;
                    logToNodeTerminal(logMsg);
                    if (ack == null || !ack.equals("ACK")) {
                        System.err.println("Failed to replicate missing entry to " + node + ": " + m);
                        logToNodeTerminal("[SERVER] Failed to replicate missing entry to " + node + ": " + m);
                    }
                } catch (Exception e) {
                    System.err.println("Error sending missing entry to " + node + ": " + e.getMessage());
                    logToNodeTerminal("[SERVER] Error sending missing entry to " + node + ": " + e.getMessage());
                }
            }
        }
    }

    

    private void updateLeaderStatus() throws KeeperException, InterruptedException {
        List<String> children = zk.getChildren("/payment/nodes", false);
        if (children.isEmpty()) {
            isLeader = false;
            return;
        }
        Collections.sort(children);
        String leaderChild = children.get(0);
        boolean prev = isLeader;
        isLeader = leaderChild.equals(myZnodeName);
    if (isLeader != prev) logToNodeTerminal("Leader status changed: isLeader=" + isLeader);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: java payment.PaymentServer <zkConnect> <host> <port>");
            System.exit(2);
        }
        String zk = args[0];
        String host = args[1];
        int port = Integer.parseInt(args[2]);
        PaymentServer server = new PaymentServer(zk, host, port);
        server.start();
    }
}
