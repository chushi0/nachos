package nachos.network;

import nachos.machine.Packet;
import nachos.threads.KThread;

/**
 * 网络层
 * 模拟 TCP 协议（与真实 TCP 协议相似）
 */
public class TCPServer {
    private static final int maxContentLength = DataLinkLayer.maxContentsLength - 2;

    public TCPServer() {
        new KThread(new Runnable() {
            @Override
            public void run() {
                receivePacketThread();
            }
        }).setName("TCP Receiver Service").fork();
    }

    /**
     * 接收数据包线程
     * 该方法负责接收发送到本机的数据包，并转发到指定连接
     * 同时还会负责接收其他消息（如连接申请、挥手协议等）
     * 在没有连接的情况下，该方法会丢弃收到的数据包
     */
    private void receivePacketThread() {

    }

    /**
     * TCP 协议所使用的数据包
     */
    static class TCPPacket {
        /**
         * 数据包类型
         * <p>
         * 握手：SYN、SYN_ACK、ACK
         * 挥手：FIN、ACK、FIN、ACK
         * 数据：DAT
         */
        enum Type {
            DAT(0),
            SYN(0x10),
            ACK(0x20),
            SYN_ACK(0x30),
            FIN(0x40);

            private final byte flag;

            Type(int flag) {
                this.flag = (byte) flag;
            }

            Type getByFlag(byte flag) {
                for (Type type : values()) {
                    if (type.flag == flag) {
                        return type;
                    }
                }
                return null;
            }
        }

        Type type;
        // 填充数量
        byte fillCount;
        byte[] data = new byte[maxContentLength];
    }
}
