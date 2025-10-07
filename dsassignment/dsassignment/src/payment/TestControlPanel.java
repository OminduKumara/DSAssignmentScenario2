package payment;
import payment.consensus.RaftNode;
import payment.consensus.RaftNode.Role;
import payment.consensus.RaftEventListener;
import payment.faulttolerance.PaymentServer;


import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;

public class TestControlPanel extends JFrame {
    private JTextArea terminalArea;
    private List<JTextArea> nodeTerminals = new ArrayList<>();
    private List<JFrame> nodeTerminalFrames = new ArrayList<>();
    private JTable ledgerTable;
    private DefaultTableModel ledgerModel;
    private DefaultListModel<String> statusModel;
    private JList<String> statusList;
    private String currentLeader = "None";
    private List<RaftNode> raftCluster = new ArrayList<>();

    
    private List<PaymentServer> paymentServers = new ArrayList<>();

    private javax.swing.Timer stateTimer;
    private RaftNode.Role[] lastRoles;
    private boolean[] lastActive;

    
    private java.util.Map<String, String> paymentStatusMap = new java.util.HashMap<>();

    public TestControlPanel() {
        setTitle("Distributed Payment System Test Panel");
        setSize(1500, 650);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        
        JPanel buttonPanel = new JPanel(new GridLayout(2, 4, 10, 10));
        JButton startServers = new JButton("Start Servers");
        JButton startClients = new JButton("Start Clients");
        JButton sendPayment = new JButton("Send Payment");
        JButton queryHistory = new JButton("Query History");
        JComboBox<String> serverSelect = new JComboBox<>(new String[]{"Server 1", "Server 2", "Server 3"});
        JButton killButton = new JButton("Kill");
        JButton recoverButton = new JButton("Recover");

        buttonPanel.add(startServers);
        buttonPanel.add(startClients);
        buttonPanel.add(sendPayment);
        buttonPanel.add(queryHistory);
        buttonPanel.add(serverSelect);
        buttonPanel.add(killButton);
        buttonPanel.add(recoverButton);

       
        ledgerModel = new DefaultTableModel(new Object[]{"Payment ID", "Client", "Amount", "Timestamp", "Processed By"}, 0);
        ledgerTable = new JTable(ledgerModel);

        
        statusModel = new DefaultListModel<>();
        statusList = new JList<>(statusModel);
        statusModel.addElement("Server 1: Stopped");
        statusModel.addElement("Server 2: Stopped");
        statusModel.addElement("Server 3: Stopped");
        statusModel.addElement("Client 1: Stopped");
        statusModel.addElement("Client 2: Stopped");

        
        terminalArea = new JTextArea(12, 80);
        terminalArea.setEditable(false);
        terminalArea.setFont(new Font("Monospaced", Font.PLAIN, 13));
        JScrollPane terminalScroll = new JScrollPane(terminalArea);
        terminalScroll.setBorder(BorderFactory.createTitledBorder("Terminal"));

        JPanel centerPanel = new JPanel(new GridLayout(1, 2, 10, 10));
        centerPanel.add(new JScrollPane(ledgerTable));
        centerPanel.add(new JScrollPane(statusList));

        add(buttonPanel, BorderLayout.NORTH);
        add(centerPanel, BorderLayout.CENTER);
        add(terminalScroll, BorderLayout.SOUTH);

        
        startServers.addActionListener(e -> {
            Objects.requireNonNull(e);
            startServersCluster(3);
        });

        killButton.addActionListener(e -> {
            int idx = serverSelect.getSelectedIndex();
            if (idx >= 0 && idx < raftCluster.size()) {
                RaftNode node = raftCluster.get(idx);
                if (node != null && node.isActive()) {
                    node.stop();
                    statusModel.set(idx, "Server " + (idx + 1) + ": Stopped");
                    terminal("[ACTION] Killed Server " + (idx + 1));
                    if (idx < nodeTerminals.size()) {
                        JTextArea ta = nodeTerminals.get(idx);
                        ta.append("[ACTION] Node killed.\n");
                        ta.setCaretPosition(ta.getDocument().getLength());
                    }
                } else {
                    terminal("[WARN] Server " + (idx + 1) + " is already stopped.");
                }
            }
        });
        recoverButton.addActionListener(e -> {
            int idx = serverSelect.getSelectedIndex();
            if (idx >= 0 && idx < raftCluster.size()) {
                RaftNode node = raftCluster.get(idx);
                if (node != null && !node.isActive()) {
                    node.startNode();
                    statusModel.set(idx, "Server " + (idx + 1) + ": Running");
                    terminal("[ACTION] Recovered Server " + (idx + 1));
                    if (idx < nodeTerminals.size()) {
                        JTextArea ta = nodeTerminals.get(idx);
                        ta.append("[ACTION] Node restarted.\n");
                        ta.setCaretPosition(ta.getDocument().getLength());
                    }
                } else {
                    terminal("[WARN] Server " + (idx + 1) + " is already running.");
                }
            }
        });

        startClients.addActionListener(e -> {
            Objects.requireNonNull(e);
            statusModel.set(3, "Client 1: Running");
            statusModel.set(4, "Client 2: Running");
            terminal("INFO  Clients are now active and can send payments.");
        });
        sendPayment.addActionListener(e -> {
            Objects.requireNonNull(e);
            String id = "P" + (ledgerModel.getRowCount() + 1);
            String client = "Client " + ((ledgerModel.getRowCount() % 2) + 1);
            String amount = "$" + (100 + ledgerModel.getRowCount() * 10);
            String timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());

            String processedBy = currentLeader.equals("None") ? "Unknown" : currentLeader;
            ledgerModel.addRow(new Object[]{id, client, amount, timestamp, processedBy});
            terminal("PAYMENT  " + id + " by " + client + " for " + amount + " | Processed by: " + processedBy);
            if (!currentLeader.equals("None")) {
                terminal("INFO  Payment " + id + " sent to " + currentLeader + ".");
                
                try {
                    
                    int leaderIdx = -1;
                    if (currentLeader.startsWith("Server ")) {
                        leaderIdx = Integer.parseInt(currentLeader.substring(7)) - 1;
                    }
                    if (leaderIdx >= 0 && leaderIdx < paymentServers.size()) {
                        int port = 9001 + leaderIdx;
                        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port);
                             java.io.PrintWriter out = new java.io.PrintWriter(socket.getOutputStream(), true);
                             java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()))) {
                            String payCmd = "PAY " + amount.replace("$", "") + " " + id;
                            out.println(payCmd);
                            String resp = in.readLine();
                            terminal("SERVER RESPONSE: " + resp);
                        }
                    } else {
                        terminal("ERROR: Could not determine leader PaymentServer to send payment.");
                    }
                } catch (Exception ex) {
                    terminal("ERROR sending payment: " + ex.getMessage());
                }
            } else {
                terminal("WARN  Payment " + id + " received, but no leader is currently elected.");
            }
        });
        queryHistory.addActionListener(e -> {
            Objects.requireNonNull(e);
            terminal("QUERY  Payment history requested by client.");
            for (int i = 0; i < ledgerModel.getRowCount(); i++) {
                String id = (String) ledgerModel.getValueAt(i, 0);
                String status;
                synchronized (paymentStatusMap) {
                    status = paymentStatusMap.getOrDefault(id, "unknown");
                }
                String statusLabel = "";
                if (status.equals("committed")) statusLabel = "[COMMITTED] ";
                else if (status.equals("failed")) statusLabel = "[FAILED] ";
                else if (status.equals("duplicate")) statusLabel = "[DUPLICATE] ";
                else statusLabel = "[PENDING] ";
                terminal(statusLabel + "ID: " + id
                        + ", Client: " + ledgerModel.getValueAt(i, 1)
                        + ", Amount: " + ledgerModel.getValueAt(i, 2)
                        + ", Time: " + ledgerModel.getValueAt(i, 3)
                        + ", Processed By: " + ledgerModel.getValueAt(i, 4));
            }
        });
    }

    
    private void startServersCluster(int n) {
        try {
            
            ensureNodeTerminals(n);

            raftCluster.clear();
            for (int i = 0; i < n; i++) raftCluster.add(null);
            paymentServers.clear();
           
            int basePort = 9001;
            String zkConnect = "localhost:2181";
            String host = "127.0.0.1";
            for (int i = 0; i < n; i++) {
                int port = basePort + i;
                PaymentServer ps = new PaymentServer(zkConnect, host, port);
                final int nodeIdx = i;
                ps.setNodeLogListener((nodePort, message) -> {
                    int idx = nodePort - basePort;
                    if (idx >= 0 && idx < nodeTerminals.size()) {
                        JTextArea ta = nodeTerminals.get(idx);
                        String lower = message.toLowerCase();
                        String prefix = "[PAYMENT] ";
                        String status = null;
                        String paymentId = null;
                        
                        if (lower.contains("payment committed")) {
                            prefix = "[COMMITTED] ";
                            status = "committed";
                            paymentId = extractPaymentId(message);
                        } else if (lower.contains("replication failed") || lower.contains("err replicationfailed")) {
                            prefix = "[FAILED] ";
                            status = "failed";
                            paymentId = extractPaymentId(message);
                        } else if (lower.contains("duplicate payment")) {
                            prefix = "[DUPLICATE] ";
                            status = "duplicate";
                            paymentId = extractPaymentId(message);
                        }
                        String ts = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new java.util.Date());
                        ta.append("[" + ts + "] " + prefix + message + "\n");
                        ta.setCaretPosition(ta.getDocument().getLength());
                        
                        if (paymentId != null && status != null) {
                            synchronized (paymentStatusMap) {
                                paymentStatusMap.put(paymentId, status);
                            }
                        }
                    }
                });
                paymentServers.add(ps);
                new Thread(() -> {
                    try { ps.start(); } catch (Exception ex) {
                        SwingUtilities.invokeLater(() -> {
                            JTextArea ta = nodeTerminals.get(nodeIdx);
                            ta.append("[PAYMENT] ERROR: " + ex.getMessage() + "\n");
                            ta.setCaretPosition(ta.getDocument().getLength());
                        });
                    }
                }, "PaymentServer-" + port).start();
            }
            
            while (statusModel.size() < n) statusModel.addElement("Server " + (statusModel.size() + 1) + ": Stopped");
            for (int i = 0; i < n; i++) statusModel.set(i, "Server " + (i + 1) + ": Starting");

            
            RaftEventListener listener = new RaftEventListener() {
                @Override
                public void onLeaderElected(int nodeId, int term) {
                    SwingUtilities.invokeLater(() -> {
                        currentLeader = "Server " + (nodeId + 1);
                        terminal("ELECTION  Node " + (nodeId + 1) + " became leader for term " + term);
                        
                        for (int j = 0; j < n; j++) {
                            if (j == nodeId) statusModel.set(j, "Server " + (j + 1) + ": Leader (Term " + term + ")");
                            else if (!statusModel.get(j).contains("Failed")) statusModel.set(j, "Server " + (j + 1) + ": Running");
                        }
                        
                        for (int j = 0; j < nodeTerminals.size(); j++) {
                            nodeTerminals.get(j).append("[ELECTION] Node " + (nodeId + 1) + " became leader for term " + term + "\n");
                            nodeTerminals.get(j).setCaretPosition(nodeTerminals.get(j).getDocument().getLength());
                        }
                        if (nodeId >= 0 && nodeId < nodeTerminals.size()) {
                            nodeTerminals.get(nodeId).append("[LEADER] Node " + (nodeId + 1) + " is now LEADER for term " + term + "\n");
                            nodeTerminals.get(nodeId).setCaretPosition(nodeTerminals.get(nodeId).getDocument().getLength());
                        }
                    });
                }
                @Override
                public void onLogMessage(int nodeId, String message) {
                    SwingUtilities.invokeLater(() -> {
                        if (nodeId >= 0 && nodeId < nodeTerminals.size()) {
                            nodeTerminals.get(nodeId).append(message + "\n");
                            nodeTerminals.get(nodeId).setCaretPosition(nodeTerminals.get(nodeId).getDocument().getLength());
                        }
                        terminal("NODE " + (nodeId + 1) + ": " + message);
                    });
                }
            };

            
            for (int i = 0; i < n; i++) {
                RaftNode node = new RaftNode(i, raftCluster, listener);
                
                raftCluster.set(i, node);
            }
            
            for (RaftNode node : raftCluster) node.startNode();

           
            lastRoles = new RaftNode.Role[n];
            lastActive = new boolean[n];
            for (int i = 0; i < n; i++) {
                lastRoles[i] = null;
                lastActive[i] = false;
            }

            
            startStateMonitor();

            for (int i = 0; i < n; i++) {
                statusModel.set(i, "Server " + (i + 1) + ": Running");
            }
            terminal("INFO  All servers are running. Awaiting leader election...");
        } catch (Exception ex) {
            terminal("ERROR  Failed to start servers: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

   
    private void ensureNodeTerminals(int n) {
        while (nodeTerminals.size() < n) {
            int idx = nodeTerminals.size();
            JTextArea ta = new JTextArea(15, 40);
            ta.setEditable(false);
            ta.setFont(new Font("Monospaced", Font.PLAIN, 12));
            nodeTerminals.add(ta);

            JFrame f = new JFrame("Node " + (idx + 1) + " Terminal");
            f.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
            f.add(new JScrollPane(ta));
            f.pack();
            f.setLocation(100 + idx * 50, 100 + idx * 50);
            f.setAlwaysOnTop(true); 
            f.setVisible(true);
            f.toFront();
            nodeTerminalFrames.add(f);
           
            terminal("[INFO] Node terminal window created for Node " + (idx + 1));
        }
        
        for (JFrame f : nodeTerminalFrames) {
            f.setVisible(true);
            f.toFront();
        }
    }

    
    private void startStateMonitor() {
        stopStateMonitor();
        stateTimer = new javax.swing.Timer(700, evt -> {
            if (raftCluster.isEmpty()) return;

           
            int leaderIdx = findLeaderIndex();
            if (leaderIdx >= 0) {
                String leaderStr = "Server " + (leaderIdx + 1);
                if (!Objects.equals(currentLeader, leaderStr)) {
                    currentLeader = leaderStr;
                    terminal("INFO  New leader detected: " + leaderStr);
                }
            } else {
                if (!Objects.equals(currentLeader, "None")) {
                    currentLeader = "None";
                    terminal("WARN  No leader currently elected.");
                }
            }

            
            for (int i = 0; i < raftCluster.size(); i++) {
                RaftNode node = raftCluster.get(i);
                if (node == null) continue;
                boolean active = node.isActive();
                Role role = node.getRole();
                
                if (lastRoles[i] != role || lastActive[i] != active) {
                    lastRoles[i] = role;
                    lastActive[i] = active;
                    JTextArea ta = nodeTerminals.get(i);
                    String line = String.format("[STATE] Node %d (%s): %s %s%s%n",
                        i + 1,
                        role,
                        role,
                        active ? "(Active)" : "(Stopped)",
                        role == RaftNode.Role.LEADER ? " [LEADER]" : "");

                    ta.append(line);
                    ta.setCaretPosition(ta.getDocument().getLength());
                }

                
                String status;
                String nodeLabel = "Node " + (i + 1);
                if (!active) status = nodeLabel + ": Stopped";
                else if (role == RaftNode.Role.LEADER) status = nodeLabel + ": Leader (Term " + node.getCurrentTerm() + ")";
                else status = nodeLabel + ": Running";
                statusModel.set(i, status);



            }
        });
        stateTimer.start();
    }

    private void stopStateMonitor() {
        if (stateTimer != null) {
            stateTimer.stop();
            stateTimer = null;
        }
    }

    
    private int findLeaderIndex() {
        for (int i = 0; i < raftCluster.size(); i++) {
            RaftNode node = raftCluster.get(i);
            if (node != null && node.isActive() && node.getRole() == RaftNode.Role.LEADER) {
                return i;
            }
        }
        return -1;
    }

   
    private void terminal(String message) {
        if (terminalArea != null) {
            String ts = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new java.util.Date());
            terminalArea.append("[" + ts + "] " + message + "\n");
            terminalArea.setCaretPosition(terminalArea.getDocument().getLength());
        }
    }

    
    private String extractPaymentId(String message) {
        
        int idx = message.indexOf("id=");
        if (idx >= 0) {
            int end = message.indexOf(',', idx);
            if (end < 0) end = message.length();
            return message.substring(idx + 3, end).trim();
        }
        return null;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new TestControlPanel().setVisible(true));
    }
}