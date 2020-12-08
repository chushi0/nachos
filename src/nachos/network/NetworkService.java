package nachos.network;

import nachos.machine.*;
import nachos.threads.*;

import java.util.Arrays;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.Map;

public class NetworkService {
    // 最大端口号
    public static final int maxPort = 127;

    // 端口是否正在被监听
    private final boolean[] portListening = new boolean[maxPort];
    private final Lock portListenLock = new Lock();
    private final Condition condition = new Condition(portListenLock);
    private volatile int listenPort = -1;
    private volatile NetworkLayer connection;

    // 与端口进行的网络连接
    private final Hashtable<RemoteLink, NetworkLayer> remoteNetworkLayerHashtable = new Hashtable<>();
    private final Lock hashtableLock = new Lock();

    private final Semaphore messageSend = new Semaphore(0);
    private final Semaphore messageReceive = new Semaphore(0);

    /**
     * 初始化操作
     */
    public void initialize() {
        // 中断处理程序
        Runnable receiveHandler = new Runnable() {
            @Override
            public void run() {
                receiveInterrupt();
            }
        };
        Runnable sendHandler = new Runnable() {
            @Override
            public void run() {
                sendInterrupt();
            }
        };
        Machine.networkLink().setInterruptHandlers(receiveHandler, sendHandler);

        // 消息接收线程
        new KThread(new Runnable() {
            @Override
            public void run() {
                receiveThreadDaemon();
            }
        }).fork();
        // 处理数据发送、网络层解包等线程
        new KThread(new Runnable() {
            @Override
            public void run() {
                logicUpdateThreadDaemon();
            }
        }).fork();
    }

    private void logicUpdateThreadDaemon() {
        try {
            //noinspection InfiniteLoopStatement
            while (true) {
                ThreadedKernel.alarm.waitUntil(50);

                Hashtable<RemoteLink, NetworkLayer> cloneHashtable;

                hashtableLock.acquire();
                //noinspection unchecked
                cloneHashtable = (Hashtable<RemoteLink, NetworkLayer>) remoteNetworkLayerHashtable.clone();
                hashtableLock.release();

                for (Map.Entry<RemoteLink, NetworkLayer> entry : cloneHashtable.entrySet()) {
                    RemoteLink remoteLink = entry.getKey();
                    NetworkLayer networkLayer = entry.getValue();

                    networkLayer.updateLogic();
                    if (networkLayer.isClose()) {
                        hashtableLock.acquire();
                        remoteNetworkLayerHashtable.remove(remoteLink, networkLayer);
                        hashtableLock.release();
                        continue;
                    }
                    byte[] sendData = new byte[Packet.maxContentsLength];
                    byte[] data;
                    while ((data = networkLayer.dataLinkLayer.nextSendPacket()) != null) {
                        sendData[0] = (byte) remoteLink.localPort;
                        sendData[1] = (byte) remoteLink.remotePort;
                        System.arraycopy(data, 0, sendData, 2, data.length);
                        Packet packet = new Packet(remoteLink.remoteAddress, Machine.networkLink().getLinkAddress(), sendData);
                        Machine.networkLink().send(packet);
                        messageSend.P();
                    }
                }
            }
        } catch (MalformedPacketException e) {
            throw new RuntimeException(e);
        }
    }

    private void receiveThreadDaemon() {
        //noinspection InfiniteLoopStatement
        while (true) {
            messageReceive.P();
            Packet packet = Machine.networkLink().receive();
            int remoteAddress = packet.srcLink;
            byte[] content = packet.contents;
            int remotePort = content[0];
            int localPort = content[1];
            byte[] data = new byte[Packet.maxContentsLength - 2];
            System.arraycopy(content, 2, data, 0, data.length);
            RemoteLink remoteLink = new RemoteLink(remoteAddress, remotePort, localPort);
            hashtableLock.acquire();
            if (remoteNetworkLayerHashtable.containsKey(remoteLink)) {
                NetworkLayer networkLayer = remoteNetworkLayerHashtable.get(remoteLink);
                networkLayer.dataLinkLayer.receivePacket(data);
            } else {
                portListenLock.acquire();
                if (portListening[localPort]) {
                    while (connection != null) {
                        condition.sleep();
                    }
                    DataLinkLayer dataLinkLayer = new DataLinkLayer();
                    NetworkLayer networkLayer = new NetworkLayer(dataLinkLayer);
                    dataLinkLayer.receivePacket(data);
                    remoteNetworkLayerHashtable.put(remoteLink, networkLayer);
                    portListening[localPort] = false;
                    connection = networkLayer;
                    listenPort = localPort;
                    condition.wakeAll();
                }
                portListenLock.release();
            }
            hashtableLock.release();
        }
    }

    /**
     * 接收一个传入连接
     *
     * @param port 端口
     */
    public NetworkLayer accept(int port) {
        portListenLock.acquire();
        portListening[port] = true;
        while (listenPort != port) {
            condition.sleep();
        }
        NetworkLayer networkLayer = connection;
        connection = null;
        listenPort = -1;
        condition.wakeAll();
        portListenLock.release();
        return networkLayer;
    }

    /**
     * 连接到远程主机
     *
     * @param remoteAddress 远程主机地址
     * @param remotePort    远程主机端口
     * @return 连接
     */
    public NetworkLayer connect(int remoteAddress, int remotePort) {
        int port = Lib.random(maxPort + 1);
        int startPort = port;
        boolean valid;
        RemoteLink remoteLink;
        hashtableLock.acquire();
        do {
            remoteLink = new RemoteLink(remoteAddress, remotePort, port);
            valid = !remoteNetworkLayerHashtable.containsKey(remoteLink);
            if (port == maxPort) port = 0;
            else port++;
        } while (port != startPort && !valid);
        if (!valid) {
            hashtableLock.release();
            return null;
        }
        DataLinkLayer dataLinkLayer = new DataLinkLayer();
        NetworkLayer networkLayer = new NetworkLayer(dataLinkLayer);
        remoteNetworkLayerHashtable.put(remoteLink, networkLayer);
        hashtableLock.release();
        networkLayer.connect();
        return networkLayer;
    }

    private void sendInterrupt() {
        messageSend.V();
    }

    private void receiveInterrupt() {
        messageReceive.V();
    }

    public void selfTest() {
        new KThread(new TestServer()).fork();
        new TestClient().run();
    }

    private class TestClient implements Runnable {
        @Override
        public void run() {
            OpenFile file = connect(Machine.networkLink().getLinkAddress(), 100);
            byte[] buf = {2, 17, 91};
            System.out.println("send: " + Arrays.toString(buf));
            file.write(buf, 0, buf.length);
            buf = new byte[buf.length];
            int readCount = 0;
            while (readCount < buf.length) {
                KThread.yield();
                int c = file.read(buf, readCount, buf.length - readCount);
                if (c == -1) {
                    System.out.println("error occurred");
                    break;
                }
                readCount += c;
            }
            System.out.println("readCount: " + readCount);
            System.out.println("read: " + Arrays.toString(buf));
            file.close();
        }
    }

    private class TestServer implements Runnable {

        @Override
        public void run() {
            while (true) {
                OpenFile file = accept(100);
                while (true) {
                    KThread.yield();
                    byte[] buf = new byte[1];
                    int resp = file.read(buf, 0, 1);
                    if (resp == -1) break;
                    if (resp == 0) continue;
                    System.out.println("server read: " + buf[0]);
                    buf[0]++;
                    System.out.println("server send: " + buf[0]);
                    Lib.assertTrue(file.write(buf, 0, 1) == 1);
                }
                file.close();
            }
        }
    }

    private static final class RemoteLink {
        int remoteAddress;
        int remotePort;
        int localPort;

        public RemoteLink(int remoteAddress, int remotePort, int localPort) {
            this.remoteAddress = remoteAddress;
            this.remotePort = remotePort;
            this.localPort = localPort;
        }

        // generate by idea
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            RemoteLink that = (RemoteLink) o;

            if (remoteAddress != that.remoteAddress) return false;
            if (remotePort != that.remotePort) return false;
            return localPort == that.localPort;
        }

        // generate by idea
        @Override
        public int hashCode() {
            int result = remoteAddress;
            result = 31 * result + remotePort;
            result = 31 * result + localPort;
            return result;
        }
    }
}
