package payment.faulttolerance;

public class main {

public static void main(String[] args) throws Exception {
    // Example ZooKeeper connection string (adjust for your local setup)
    String zkConnect = "localhost:2181";

    // Start 3 server nodes in separate threads
    Thread s1 = new Thread(() -> {
        try {
            new PaymentServer(zkConnect, "localhost", 9001).start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }, "Server-9001");

    Thread s2 = new Thread(() -> {
        try {
            new PaymentServer(zkConnect, "localhost", 9002).start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }, "Server-9002");

    Thread s3 = new Thread(() -> {
        try {
            new PaymentServer(zkConnect, "localhost", 9003).start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }, "Server-9003");

    s1.start();
    s2.start();
    s3.start();

    // Give servers time to register and elect leader
    Thread.sleep(5000);

    // Start a client
    PaymentClient client = new PaymentClient(zkConnect);
    client.start();

    // Send some payments automatically
    System.out.println("\n=== Sending test payments ===");
    client.sendPayment("PAY 100 txn1");
    client.sendPayment("PAY 200 txn2");
    client.sendPayment("PAY 300 txn3");

    // Wait a bit for processing
    Thread.sleep(3000);

    // Stop client
    client.stop();

    System.out.println("\n=== Test complete ===");
    System.exit(0);
}


}
