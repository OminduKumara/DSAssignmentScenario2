# Fault Tolerance demo (ZooKeeper + raw Java)

This small demo shows a simple ZooKeeper-backed fault-tolerant payment system using raw Java (no Maven/Gradle). It includes:

- `payment.PaymentServer` - registers itself under `/payment/nodes` as an ephemeral sequential node containing `host:port` and serves simple TCP payment requests.
- `payment.PaymentClient` - watches `/payment/nodes`, discovers available nodes, and performs automatic failover by trying other nodes when a node fails.

Files are under `src/payment`.

## Prerequisites

- Java JDK 8+ installed
- Apache ZooKeeper running locally (default: `localhost:2181`). You can download ZooKeeper and start it with `bin\zkServer.cmd` on Windows.
- Add ZooKeeper client jar to your Eclipse project's classpath. Look for `zookeeper-<version>.jar` in the ZooKeeper distribution `lib` folder.

## How to import and run in Eclipse

1. Create a Java Project in Eclipse (no build tools).
2. Copy the `src` folder into the project's root so Eclipse sees `src/payment/*.java`.
3. Add the ZooKeeper client JARs to the project's Build Path (Project -> Properties -> Java Build Path -> Libraries -> Add External JARs).
4. Run `payment.PaymentServer` (Run As -> Java Application) with VM arguments: none and program arguments: `localhost:2181 127.0.0.1 9001` (change host/port as needed). Start multiple servers on different ports.
5. Run `payment.PaymentClient` with program arguments: `localhost:2181`. Type `PAY 10 1` to send example payments.

## Design notes

- Redundancy: each `PaymentServer` registers as an ephemeral node in ZooKeeper. Multiple servers provide redundancy. `PaymentClient` reads the children of `/payment/nodes` and uses the `host:port` payload to contact servers.
- Failure detection: ZooKeeper maintains ephemeral nodes bound to the server's session. If a server process or machine fails, its ephemeral node disappears and watchers on `/payment/nodes` receive NodeChildrenChanged events.
- Failover: `PaymentClient` watches for node changes and keeps an up-to-date list of active servers. When sending a payment it rounds through the list; if a node is unreachable the client retries other nodes automatically.
- Recovery: when a failed server restarts it re-registers as a fresh ephemeral node and becomes available again. Clients will see the new node on the next watch callback.

## Part 2 — Data Replication and Consistency

This section describes the replication and consistency approach implemented in the demo and trade-offs made.

1) Replication strategy
- Primary-backup (leader-based) with synchronous replication to followers.
	- Leader election: the node with the lexicographically smallest sequential znode under `/payment/nodes` is the leader.
	- Writes (payments) are forwarded to the leader. The leader applies the write to its local ledger and then synchronously sends a small replication RPC `REPL <id> <payload>` to each follower node.
	- Followers acknowledge (`ACK`) the replication request. The leader returns success to the client only if all followers acknowledge (a simple quorum=all policy in this demo). This provides strong consistency across replicas at the cost of write latency.

2) Consistency model and trade-offs
- The demo implements strong consistency for committed payments: once the leader returns OK, all surviving replicas have applied the entry (assuming the leader had positive ACKs from all followers).
- Trade-offs:
	- Strong consistency ensures clients see a single linearized history of payments and simplifies correctness for financial ledgers.
	- It increases write latency because replication is synchronous. In the demo we wait for all followers; in production you'd usually require a majority quorum (faster and more available) and use leader leases or consensus (e.g., Raft or ZooKeeper recipes) to make leader election robust.

3) Deduplication mechanism
- Each payment request must include a globally unique id (idempotency key). The `Ledger` keeps a persistent list of seen ids in `ids.txt` and a write is only appended if the id hasn't been seen. This handles retries and duplicated deliveries during client failover.
- Deduplication is checked at the leader before persisting and at followers when applying replication. Followers `ACK` even if they had previously seen the id (idempotent apply), so replays are safe.

4) Optimizations for performance while ensuring consistency
- Batch replication: instead of sending one REPL per payment, the leader can batch multiple entries and replicate them in one request (reduces per-entry RPC overhead).
- Quorum-based commit: require a majority of followers to ACK instead of all — improves availability and reduces latency for large clusters.
- Async replication with durable commit: leader can persist locally and return ACK to client, then replicate asynchronously; combine with write-ahead logs and a mechanism to ensure he can re-replicate on follower recovery (improves latency but weakens the strong consistency guarantee unless carefully designed).
- Connection pooling and a binary protocol would reduce CPU and network overhead (current code uses plain sockets and text lines for clarity).

5) Analysis of replication impact (latency & storage)
- Latency: synchronous replication to all followers increases end-to-end write latency approximately by the maximum network round-trip time to a follower plus follower apply time. For N followers, assuming parallel replication, latency ≈ leader local write time + max(replication RTT + follower apply).
- Throughput: throughput is limited by leader's ability to persist and replicate. Batching and parallel replication help; using a majority quorum helps availability and can increase throughput under failures.
- Storage efficiency: each server keeps a full copy of the ledger (replication factor R). Storage overhead is O(R) compared to a single copy. The demo stores a simple append-only file and an ids file; in production you'd compress/compact or snapshot the ledger and store deltas.

6) Practical recommendations for production
- Use a consensus algorithm (Raft, etcd, or ZooKeeper recipes) rather than ad-hoc leader selection and synchronous fan-out for correctness at scale.
- Use majority quorum for commit, persist a durable write-ahead log first, and replicate asynchronously with careful durability semantics for client-visible commits.
- Use idempotent processing and client-supplied idempotency keys for safe retries.
- Deploy ZooKeeper as a highly available ensemble (3+ nodes) and avoid relying on a single ZooKeeper server.

## Part 3 — Time Synchronization, Log Correction and Reordering

This demo includes a simple time synchronization and log reordering component to help produce globally-consistent timestamps across servers.

Files added:
- `src/payment/TimeSync.java` — simplified SNTP-style query to compute local clock offset (serverTime - localTime) in milliseconds.
- `src/payment/LogEntry.java` — a timestamped log entry that stores original and corrected timestamps.
- `src/payment/LogReorderer.java` — buffers log entries for a short window, corrects timestamps using `TimeSync`, and emits entries in corrected-time order.

Design notes
- Time synchronization: the demo uses an SNTP-like single-query approach to estimate an offset. It's simple and lightweight but not as accurate as a full NTP/PTP client. The returned offset is used to correct local timestamps before ordering logs.
- Log reordering: logs are buffered in a small time window (configurable). Entries whose corrected timestamp is older than (now - window) are emitted in order. This reduces reordering caused by clock skew and network delays.
- Timestamp correction: `correctedTs = originalLocalTs + offsetMillis` where offsetMillis is computed by `TimeSync`.

Impact analysis and trade-offs
- Accuracy vs overhead:
	- SNTP single-sample queries are low-overhead but noisy; a production system should run continuous NTP/PTP syncing or use multiple samples + filtering (e.g., min-RTT selection).
	- PTP (hardware timestamping) gives sub-microsecond accuracy but requires support and higher complexity.
- Clock skew impact:
	- If clocks diverge, log ordering by local timestamps becomes unreliable. Our correction + reorder window mitigates this by adjusting timestamps and buffering reordering for a limited time.
	- Large skews or network partitions may still produce incorrect orders; stronger approaches are logical clocks (Lamport/Causal) or vector clocks where ordering is about causality rather than absolute time.
- Overhead:
	- SNTP queries are occasional UDP packets (low network overhead). Buffering logs adds memory and a small latency equal to the buffer window.

How to use in the demo
- Create a `TimeSync` pointing at an NTP server (e.g., `pool.ntp.org`):
	- `TimeSync ts = new TimeSync("pool.ntp.org", 123);`
- Create a `LogReorderer` with a small window (e.g., 500 ms):
	- `LogReorderer reorderer = new LogReorderer(500, ts);`
- When creating log entries, use `new LogEntry(System.currentTimeMillis(), source, message)` and `reorderer.add(entry)`.
- Periodically call `reorderer.flush()` to get ordered entries and write them to your centralized log store.

Next steps for production-grade timestamps
- Run a proper NTP client daemon on each host (chrony/ntpd) or deploy PTP when sub-microsecond accuracy is required.
- Use multiple NTP samples and select the sample with the smallest RTT to reduce error.
- Consider logical or hybrid logical clocks (HLC) for combining causality with physical time for ordering events in distributed systems.


## Evaluation of impact

1) Performance (latency/throughput):
- Adding redundancy increases the chance a request succeeds without long waits: clients can switch to another node if one is slow or down.
- However, maintaining multiple servers does not by itself reduce single-request latency — each request still is handled by a single server. Load distribution can improve effective throughput across the cluster.
- ZooKeeper adds a small amount of coordination overhead (getChildren calls and watches). In this demo the overhead is minimal (a few KBs of metadata per change). For high-frequency membership changes you should batch updates or use ephemeral nodes with care.

2) Storage overhead:
- ZooKeeper stores the ephemeral node data (host:port) in memory on the ZooKeeper ensemble; the storage footprint per node is very small (few bytes plus znode metadata). The main storage overhead is the ZooKeeper ensemble itself which needs to be sized for the number of znodes and watchers.

3) Reliability tradeoffs:
- Ephemeral nodes and a ZooKeeper ensemble provide a robust membership mechanism. Make sure ZooKeeper is itself highly available (use a 3-node or 5-node ensemble in production).

## Next steps / improvements

- Use a real load balancer (e.g., HAProxy) or client-side consistent hashing for better request distribution.
- Add health checks and weight-based routing.
- Persist requests to an external durable queue so in-flight payments are not lost on server failure.
- Implement retries with exponential backoff and idempotency keys to handle duplicate deliveries safely.
