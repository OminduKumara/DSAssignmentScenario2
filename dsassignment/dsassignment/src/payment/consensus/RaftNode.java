package payment.consensus;

import java.util.*;
import java.util.concurrent.*;

public class RaftNode {

    public int getCurrentTerm() {
        return currentTerm;
    }

    public enum Role {
        FOLLOWER, CANDIDATE, LEADER
    }

    private final int nodeId;
    private final List<RaftNode> cluster;
    private final RaftEventListener eventListener;

    private volatile Role role = Role.FOLLOWER;
    private volatile boolean active = false;
    private volatile boolean electionOngoing = false;

    private int currentTerm = 0;
    private int votedFor = -1;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> electionTimer;
    private final Random random = new Random();

    private final List<RaftLogEntry> log = Collections.synchronizedList(new ArrayList<>());

    public RaftNode(int nodeId, List<RaftNode> cluster, RaftEventListener listener) {
        this.nodeId = nodeId;
        this.cluster = cluster;
        this.eventListener = listener;
    }

    // ===================== Node Control =====================

    public synchronized void startNode() {
    if (active) return;
    active = true;
    scheduler = Executors.newScheduledThreadPool(2);
    role = Role.FOLLOWER;
    logMsg("Node " + nodeId + " started.");
    startElectionTimer();
    }

    public synchronized void stop() {
        if (!active) return;
        active = false;
        role = Role.FOLLOWER;
        electionOngoing = false;
        cancelElectionTimer();
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        logMsg("Node " + nodeId + " stopped.");
    }

    public boolean isActive() {
        return active;
    }

    public int getNodeId() {
        return nodeId;
    }

    public Role getRole() {
        return role;
    }

    // ===================== Election Logic =====================

    private void startElectionTimer() {
        if (scheduler == null || scheduler.isShutdown()) return;
        cancelElectionTimer();
        int timeout = randomElectionTimeout();
        electionTimer = scheduler.schedule(this::onElectionTimeout, timeout, TimeUnit.MILLISECONDS);
    }

    private void cancelElectionTimer() {
        if (electionTimer != null && !electionTimer.isCancelled()) {
            electionTimer.cancel(true);
        }
    }

    private int randomElectionTimeout() {
        return 600 + random.nextInt(600); 
    }

    private void onElectionTimeout() {
    if (!active || role == Role.LEADER) return;
    logMsg("Node " + nodeId + " election timeout — starting election.");
    startElection();
    }

    private synchronized void startElection() {
        if (!active) return;
        if (electionOngoing) return;

        electionOngoing = true;
        role = Role.CANDIDATE;
        currentTerm++;
        votedFor = nodeId;

        int votes = 1; 
        logMsg("Node " + nodeId + " starts election for term " + currentTerm);

        for (RaftNode peer : cluster) {
            if (peer == this) continue;
            if (!peer.isActive()) continue;

            if (peer.receiveVoteRequest(nodeId, currentTerm)) {
                votes++;
            }
        }

        if (votes > cluster.size() / 2) {
            becomeLeader();
        } else {
            electionOngoing = false;
            role = Role.FOLLOWER;
            votedFor = -1;
            startElectionTimer();
        }
    }

    public synchronized boolean receiveVoteRequest(int candidateId, int candidateTerm) {
        if (!active) return false;

        if (candidateTerm > currentTerm) {
            currentTerm = candidateTerm;
            role = Role.FOLLOWER;
            votedFor = -1;
        }

        if ((votedFor == -1 || votedFor == candidateId) && candidateTerm >= currentTerm) {
            votedFor = candidateId;
            resetElectionTimer();
            logMsg("Node " + nodeId + " voted for " + candidateId + " in term " + candidateTerm);
            return true;
        }

        return false;
    }

    private synchronized void becomeLeader() {
        if (!active) return;
        role = Role.LEADER;
        electionOngoing = false;
        cancelElectionTimer();

        logMsg("Node " + nodeId + " becomes LEADER (term " + currentTerm + ")");
        if (eventListener != null) {
            eventListener.onLeaderElected(nodeId, currentTerm);
        }

        sendHeartbeats();
    }

    private void sendHeartbeats() {
        if (!active || scheduler == null || scheduler.isShutdown()) return;
        scheduler.scheduleAtFixedRate(() -> {
            if (!active || role != Role.LEADER) return;
            for (RaftNode peer : cluster) {
                if (peer != this && peer.isActive()) {
                    peer.receiveHeartbeat(nodeId, currentTerm);
                }
            }
        }, 0, 300, TimeUnit.MILLISECONDS); 
    }

    public synchronized void receiveHeartbeat(int leaderId, int term) {
        if (!active) return;
        if (term >= currentTerm) {
            currentTerm = term;
            role = Role.FOLLOWER;
            votedFor = leaderId;
            resetElectionTimer();
        }
    }

    private void resetElectionTimer() {
        cancelElectionTimer();
        startElectionTimer();
    }

    // ===================== Log Replication =====================

    public synchronized boolean appendEntry(String command) {
        if (!active) return false;
        if (role != Role.LEADER) {
            logMsg("Node " + nodeId + " ignored command (not leader).");
            return false;
        }

        RaftLogEntry entry = new RaftLogEntry(currentTerm, command);
        log.add(entry);
        logMsg("Leader " + nodeId + " appended: " + command);

        replicateEntry(entry);
        return true;
    }

    private void replicateEntry(RaftLogEntry entry) {
        for (RaftNode peer : cluster) {
            if (peer == this || !peer.isActive()) continue;
            peer.receiveEntry(entry);
        }
    }

    public synchronized void receiveEntry(RaftLogEntry entry) {
        if (!active) return;
        log.add(entry);
        logMsg("Node " + nodeId + " received log entry: " + entry);
    }


    public synchronized List<RaftLogEntry> getLog() {
        return new ArrayList<>(log);
    }

    public void setEventListener(RaftEventListener listener) {
        throw new UnsupportedOperationException("setEventListener not used — listener set in constructor");
    }

    private void logMsg(String msg) {
        System.out.println(msg);
        if (eventListener != null) {
            eventListener.onLogMessage(nodeId, msg);
        }
    }

    @Override
    public String toString() {
        return "Node{" + nodeId + ", role=" + role + ", term=" + currentTerm + "}";
    }
}
