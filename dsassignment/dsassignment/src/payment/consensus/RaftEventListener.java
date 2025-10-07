package payment.consensus;

public interface RaftEventListener {
    void onLeaderElected(int nodeId, int term);
    default void onLogMessage(int nodeId, String message) {}
}