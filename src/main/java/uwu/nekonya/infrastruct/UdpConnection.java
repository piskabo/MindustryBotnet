package uwu.nekonya.infrastruct;

import arc.net.NetSerializer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;

public class UdpConnection {
    final ByteBuffer readBuffer, writeBuffer;
    private final NetSerializer serialization;
    private final Object writeLock = new Object();
    InetSocketAddress connectedAddress;
    DatagramChannel datagramChannel;
    int keepAliveMillis = 19000;
    private SelectionKey selectionKey;
    private long lastCommunicationTime;

    public UdpConnection(NetSerializer serialization, int bufferSize) {
        this.serialization = serialization;
        readBuffer = ByteBuffer.allocate(bufferSize);
        writeBuffer = ByteBuffer.allocateDirect(bufferSize);
    }

    public void bind(Selector selector, InetSocketAddress localPort) throws IOException {
        close();
        readBuffer.clear();
        writeBuffer.clear();
        try {
            datagramChannel = selector.provider().openDatagramChannel();
            datagramChannel.socket().bind(localPort);
            datagramChannel.configureBlocking(false);
            selectionKey = datagramChannel.register(selector, SelectionKey.OP_READ);

            lastCommunicationTime = System.currentTimeMillis();
        } catch (IOException ex) {
            close();
            throw ex;
        }
    }

    public void connect(Selector selector, InetSocketAddress remoteAddress) throws IOException {
        close();
        readBuffer.clear();
        writeBuffer.clear();
        try {
            datagramChannel = selector.provider().openDatagramChannel();
            datagramChannel.socket().bind(null);
            datagramChannel.socket().connect(remoteAddress);
            datagramChannel.configureBlocking(false);

            selectionKey = datagramChannel.register(selector, SelectionKey.OP_READ);

            lastCommunicationTime = System.currentTimeMillis();

            connectedAddress = remoteAddress;
        } catch (IOException ex) {
            close();
            throw new IOException("Unable to connect to: " + remoteAddress, ex);
        }
    }

    public InetSocketAddress readFromAddress() throws IOException {
        DatagramChannel datagramChannel = this.datagramChannel;
        if (datagramChannel == null)
            throw new SocketException("Connection is closed.");
        lastCommunicationTime = System.currentTimeMillis();

        if (!datagramChannel.isConnected())
            return (InetSocketAddress) datagramChannel.receive(readBuffer); //always null on Android >= 5.0
        datagramChannel.read(readBuffer);
        return connectedAddress;
    }

    public Object readObject() {
        readBuffer.flip();
        try {
            try {
                return serialization.read(readBuffer);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        } finally {
            readBuffer.clear();
        }
        return null;
    }

    /**
     * This method is thread safe.
     */
    public int send(Object object, SocketAddress address) throws IOException {
        DatagramChannel datagramChannel = this.datagramChannel;
        if (datagramChannel == null)
            throw new SocketException("Connection is closed.");
        synchronized (writeLock) {
            try {
                try {
                    serialization.write(writeBuffer, object);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
                writeBuffer.flip();
                int length = writeBuffer.limit();
                datagramChannel.send(writeBuffer, address);

                lastCommunicationTime = System.currentTimeMillis();

                boolean wasFullWrite = !writeBuffer.hasRemaining();
                return wasFullWrite ? length : -1;
            } finally {
                writeBuffer.clear();
            }
        }
    }

    public void close() {
        connectedAddress = null;
        try {
            if (datagramChannel != null) {
                datagramChannel.close();
                datagramChannel = null;
                if (selectionKey != null)
                    selectionKey.selector().wakeup();
            }
        } catch (IOException ignored) {
        }
    }

    public boolean needsKeepAlive(long time) {
        return connectedAddress != null && keepAliveMillis > 0
                && time - lastCommunicationTime > keepAliveMillis;
    }
}
