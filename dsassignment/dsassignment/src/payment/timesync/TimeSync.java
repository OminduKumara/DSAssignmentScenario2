package payment.timesync;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;


public class TimeSync {
    private final String server;
    private final int port;

    public TimeSync(String server, int port) {
        this.server = server;
        this.port = port;
    }

    
    public long queryOffsetMillis() throws Exception {
        
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(2000);
            InetAddress addr = InetAddress.getByName(server);

            byte[] buf = new byte[48];
            buf[0] = 0x1B; 
            DatagramPacket request = new DatagramPacket(buf, buf.length, addr, port);

            long t1 = System.currentTimeMillis();
            socket.send(request);

            DatagramPacket response = new DatagramPacket(new byte[48], 48);
            socket.receive(response);
            long t4 = System.currentTimeMillis();

            byte[] data = response.getData();
            
            long seconds = readUnsignedInt(data, 40) - 2208988800L; 
            long fraction = readUnsignedInt(data, 44);
            double fracSeconds = ((double) fraction) / 0x100000000L;
            long serverMillis = (long) ((seconds * 1000L) + (fracSeconds * 1000.0));

            
            long localMid = (t1 + t4) / 2;
            return serverMillis - localMid;
        }
    }

    private long readUnsignedInt(byte[] b, int index) {
        ByteBuffer bb = ByteBuffer.wrap(b, index, 4);
        bb.order(ByteOrder.BIG_ENDIAN);
        return ((long) bb.getInt()) & 0xFFFFFFFFL;
    }
}
