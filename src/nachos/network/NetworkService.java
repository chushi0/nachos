package nachos.network;

import nachos.machine.Lib;
import nachos.machine.Machine;
import nachos.machine.MalformedPacketException;
import nachos.machine.Packet;
import nachos.threads.*;

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
                ThreadedKernel.alarm.waitUntil(500);

                Hashtable<RemoteLink, NetworkLayer> cloneHashtable;

                hashtableLock.acquire();
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
                    byte[] data;
                    while ((data = networkLayer.dataLinkLayer.nextSendPacket()) != null) {
                        Packet packet = new Packet(remoteLink.remotePort, remoteLink.localPort, data);
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
            int remoteAddress = packet.packetBytes[0];
            int remotePort = packet.srcLink;
            int localPort = packet.dstLink;
            RemoteLink remoteLink = new RemoteLink(remoteAddress, remotePort, localPort);
            hashtableLock.acquire();
            if (remoteNetworkLayerHashtable.containsKey(remoteLink)) {
                NetworkLayer networkLayer = remoteNetworkLayerHashtable.get(remoteLink);
                networkLayer.dataLinkLayer.receivePacket(packet.contents);
            } else {
                portListenLock.acquire();
                if (portListening[localPort]) {
                    while (connection != null) {
                        condition.sleep();
                    }
                    DataLinkLayer dataLinkLayer = new DataLinkLayer();
                    NetworkLayer networkLayer = new NetworkLayer(dataLinkLayer);
                    dataLinkLayer.receivePacket(packet.contents);
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
