package payment.faulttolerance;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooKeeper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;


public class PaymentClient {
    private final String zkConnect;
    private volatile ZooKeeper zk;
    
    private final AtomicReference<List<String>> nodes = new AtomicReference<>(Collections.emptyList());
    private volatile String leader = null;

    public PaymentClient(String zkConnect) {
        this.zkConnect = zkConnect;
    }

    public void start() throws Exception {
        connectZkAndWatch();
    }

    private synchronized void connectZkAndWatch() throws Exception {
        if (zk != null) {
            try { zk.close(); } catch (Exception ignored) {}
        }
        System.out.println("[CLIENT] Connecting to ZooKeeper at " + zkConnect);
        zk = new ZooKeeper(zkConnect, 3000, new Watcher() {
            @Override
            public void process(WatchedEvent event) {
                System.out.println("[CLIENT] ZooKeeper event: " + event);
                if (event.getState() == KeeperState.Expired) {
                    System.err.println("ZooKeeper session expired in client - reconnecting");
                    try {
                        connectZkAndWatch();
                    } catch (Exception e) {
                        System.err.println("Failed to reconnect ZooKeeper client: " + e.getMessage());
                        e.printStackTrace();
                    }
                } else if (event.getType() == EventType.NodeChildrenChanged && "/payment/nodes".equals(event.getPath())) {
                    try {
                        watchNodes();
                    } catch (Exception e) {
                        System.err.println("Error while re-watching nodes: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            }
        });

        watchNodes();
    }

    private void watchNodes() throws KeeperException, InterruptedException {
        List<String> children = zk.getChildren("/payment/nodes", new Watcher() {
            @Override
            public void process(WatchedEvent event) {
                if (event.getType() == EventType.NodeChildrenChanged) {
                    try {
                        watchNodes();
                    } catch (Exception e) {
                        System.err.println("Error re-watching nodes: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            }
        });

        List<String> updated = new ArrayList<>();
        for (String child : children) {
            try {
                byte[] data = zk.getData("/payment/nodes/" + child, false, null);
                String s = new String(data, StandardCharsets.UTF_8);
                updated.add(s);
            } catch (Exception e) {
               
                System.err.println("Skipped child while reading data: " + child + " err: " + e.getMessage());
            }
        }

       
        nodes.set(Collections.unmodifiableList(updated));

        
        try {
            List<String> childrenNames = zk.getChildren("/payment/nodes", false);
            Collections.sort(childrenNames);
            if (!childrenNames.isEmpty()) {
                byte[] data = zk.getData("/payment/nodes/" + childrenNames.get(0), false, null);
                leader = new String(data, StandardCharsets.UTF_8);
            } else {
                leader = null;
            }
        } catch (Exception e) {
            leader = null;
            System.err.println("Error computing leader: " + e.getMessage());
        }
        System.out.println("[CLIENT] Discovered nodes: " + nodes.get() + " leader=" + leader);
    }

    
    public boolean sendPayment(String payload) {
        System.out.println("[CLIENT] Sending payment: " + payload);
        
        if (leader != null) {
            System.out.println("[CLIENT] Trying leader: " + leader);
            if (trySendToHostPort(leader, payload)) return true;
        }

        
        List<String> snapshot = new ArrayList<>(nodes.get());
        for (String node : snapshot) {
            if (leader != null && node.equals(leader)) continue;
            System.out.println("[CLIENT] Trying node: " + node);
            if (trySendToHostPort(node, payload)) return true;
        }

        System.err.println("[CLIENT] All nodes failed for payload: " + payload);
        return false;
    }

    private boolean trySendToHostPort(String hostPort, String payload) {
        String[] parts = hostPort.split(":");
        if (parts.length < 2) return false;
        String host = parts[0];
        int port;
        try { port = Integer.parseInt(parts[1]); } catch (NumberFormatException nfe) { return false; }

        try (Socket s = new Socket()) {
            System.out.println("[CLIENT] Connecting to server " + hostPort);
            s.connect(new InetSocketAddress(host, port), 2000); 
            s.setSoTimeout(3000); 
            try (PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
                 BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {

                out.println(payload);
                System.out.println("[CLIENT] Sent: " + payload);
                String resp = in.readLine();
                System.out.println("[CLIENT] Received: " + resp);
                if (resp != null && resp.equals("OK")) {
                    System.out.println("[CLIENT] Payment accepted by " + hostPort);
                    return true;
                } else {
                    System.err.println("[CLIENT] Node rejected/failed: " + hostPort + " resp=" + resp);
                }
            }
        } catch (Exception e) {
            System.err.println("[CLIENT] Error contacting node " + hostPort + ": " + e.getMessage());
            
        }
        return false;
    }

    public void stop() {
        try { if (zk != null) zk.close(); } catch (Exception ignored) {}
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java payment.faulttolerance.PaymentClient <zkConnect>");
            System.exit(2);
        }
        PaymentClient client = new PaymentClient(args[0]);
        client.start();

       
        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("Type PAY <amount> <id> to send payments, or quit");
        String line;
        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.equalsIgnoreCase("quit") || line.equalsIgnoreCase("exit")) break;
            if (line.startsWith("PAY")) {
                client.sendPayment(line);
            } else {
                System.out.println("Unknown command");
            }
        }
        client.stop();
        System.out.println("Client exiting");
    }
}
