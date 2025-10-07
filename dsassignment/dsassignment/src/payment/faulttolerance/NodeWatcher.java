package payment.faulttolerance;

import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;


public abstract class NodeWatcher implements Watcher {
    @Override
    public void process(WatchedEvent event) {
        handle(event);
    }

    public abstract void handle(WatchedEvent event);
}
