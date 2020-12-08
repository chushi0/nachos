package nachos.network;

import nachos.machine.Lib;
import nachos.machine.Machine;
import nachos.machine.Packet;
import nachos.threads.SynchList;

/**
 * 数据链路层
 * <p>
 * 每一个端口有一个数据链路层对象
 */
public class DataLinkLayer {

    // 数据链路层数据包有效负荷内容大小
    public static final int maxContentsLength = Packet.maxContentsLength - 2 - 2;

    // 双指针缓冲区
    private static final int cacheSize = 16;
    // 超时时间
    private static final int timeoutTime = 10000000;
    // 重传次数
    private static final int tryCount = 10;

    // 接收缓冲区
    private final DLLPacket[] recvCache = new DLLPacket[cacheSize];
    // 指针头（指向下一个要接收的数据包，或者指向第一个被跳过的数据包）
    private int recvStart = 0;
    // 指针尾（指向当前收到的最后一个数据包+1）
    private int recvEnd = 0;
    // 数据包队列
    private final SynchList recvPackets = new SynchList();

    // 发送缓冲区
    private final DLLPacket[] sendCache = new DLLPacket[cacheSize];
    // 指针头（指向下一个要发送的数据包，或者指向第一个尚未回应收到的数据包）
    private int sendStart = 0;
    // 指针尾（指向下一个要发送的数据包+1）
    private int sendEnd = 0;
    // 用户请求发送的数据包队列
    private final SynchList userSendPackets = new SynchList();

    // 要发送的数据包队列
    private final SynchList sendPackets = new SynchList();

    private boolean resetRequest = false;
    private long lastResetRequestTime = 0;
    private int resetRequestTryCount = 0;

    // 因错误终止
    private boolean terminate;

    /**
     * 当底层接收到一个数据包时，将调用此方法，进行数据链路层解包
     *
     * @param data 数据包
     */
    public void receivePacket(byte[] data) {
        if (terminate) return;
        Lib.assertTrue(data.length == Packet.maxContentsLength - 2);
        DLLPacket packet = new DLLPacket();
        packet.packetType = DLLPacket.Type.getFromFlag(data[0]);
        if (packet.packetType == null) {
            // 未知数据包，抛弃
            System.err.println("unknown packet type");
            return;
        }
        packet.packetNumber = data[1] & 0xff;
        System.arraycopy(data, 2, packet.content, 0, maxContentsLength);
        switch (packet.packetType) {
            case DAT: {
                // 如果已经接收到了，则向另一方发送收到消息
                if (recvStart > packet.packetNumber) {
                    sendPackets.add(buildCtrlPacket(DLLPacket.Type.REJ, recvStart));
                    break;
                }
                // 如果缓冲区为空，并且这个数据包是接下来要接收的，则回复收到
                if (recvStart == recvEnd && recvStart == packet.packetNumber) {
                    recvPackets.add(packet);
                    recvStart++;
                    recvEnd++;
                    sendPackets.add(buildCtrlPacket(DLLPacket.Type.RR, packet.packetNumber));
                    break;
                }
                // 如果在双指针之间，留下并回复收到
                if (recvStart < packet.packetNumber && recvEnd > packet.packetNumber) {
                    recvCache[packet.packetNumber % cacheSize] = packet;
                    sendPackets.add(buildCtrlPacket(DLLPacket.Type.RR, packet.packetNumber));
                    break;
                }
                // 如果是第一个等待的未发送包
                if (recvStart == packet.packetNumber) {
                    recvPackets.add(packet);
                    sendPackets.add(buildCtrlPacket(DLLPacket.Type.RR, packet.packetNumber));
                    recvStart++;
                    while (recvStart < recvEnd) {
                        if (recvCache[recvStart % cacheSize] == null) {
                            break;
                        }
                        recvPackets.add(recvCache[recvStart % cacheSize]);
                        recvCache[recvStart % cacheSize] = null;
                        recvStart++;
                    }
                    break;
                }
                // 执行到这里，说明之前已经发生丢包
                sendPackets.add(buildCtrlPacket(DLLPacket.Type.REJ, recvStart));
                // 如果该包可以被缓存，则缓存并回复收到
                // 否则，直接丢弃
                if (packet.packetNumber - recvStart < cacheSize) {
                    sendPackets.add(buildCtrlPacket(DLLPacket.Type.RR, packet.packetNumber));
                    recvCache[packet.packetNumber % cacheSize] = packet;
                    recvEnd = packet.packetNumber + 1;
                }
                break;
            }
            case RESET: {
                // 如果还有未接收的数据包，则不重置编号
                if (recvStart != recvEnd) {
                    sendPackets.add(buildCtrlPacket(DLLPacket.Type.REJ, recvStart));
                    break;
                }
                // 回应重置编号
                sendPackets.add(buildCtrlPacket(DLLPacket.Type.RESETOK, 0));
                recvStart = 0;
                recvEnd = 0;
                break;
            }
            case RR: {
                // 如果不在确认范围内，则忽略
                if (sendStart > packet.packetNumber || sendEnd <= packet.packetNumber) {
                    break;
                }
                // 标记已经确认
                sendCache[packet.packetNumber % cacheSize] = null;
                // 检查到下一个未确认数据包
                while (sendStart < sendEnd) {
                    if (sendCache[sendStart % cacheSize] != null) {
                        break;
                    }
                    sendStart++;
                }
                break;
            }
            case RESETOK: {
                if (resetRequest) {
                    resetRequest = false;
                    resetRequestTryCount = 0;
                    sendStart = 0;
                    sendEnd = 0;
                }
                break;
            }
            case REJ: {
                // 如果不在确认范围内，则忽略
                if (sendStart > packet.packetNumber || sendEnd <= packet.packetNumber) {
                    break;
                }
                // 重发指定数据包
                DLLPacket resendPacket = sendCache[packet.packetNumber % cacheSize];
                resendPacket.sendTime = Machine.timer().getTime();
                sendPackets.add(resendPacket);
                // 如果要求重置的话，重发重置数据包
                if (resetRequest) {
                    sendPackets.add(buildCtrlPacket(DLLPacket.Type.RESET, 0));
                    lastResetRequestTime = Machine.timer().getTime();
                    resetRequestTryCount = 0;
                }
                break;
            }
        }
    }

    /**
     * 获取下一个要发送的数据包
     * 如果没有要发送的数据包，则返回 null
     *
     * @return 下一个要发送的数据包
     */
    public byte[] nextSendPacket() {
        if (terminate) return null;
        // 从要发送的数据包队列中获取
        DLLPacket packet = (DLLPacket) sendPackets.removeFirstOrNull();
        if (packet != null) {
            return packet.toBytes();
        }
        if (!resetRequest) {
            // 重发已经超时的数据包
            for (int i = sendStart; i < sendEnd; i++) {
                if (sendCache[i % cacheSize] != null && sendCache[i % cacheSize].sendTime + timeoutTime < Machine.timer().getTime()) {
                    sendCache[i % cacheSize].sendTime = Machine.timer().getTime();
                    sendCache[i % cacheSize].resendCount++;
                    if (sendCache[i % cacheSize].resendCount > tryCount) {
                        terminate = true;
                        return null;
                    }
                    return sendCache[i % cacheSize].toBytes();
                }
            }
            // 发送编号重置请求
            if (sendStart == sendEnd && ((sendEnd > 200 && userSendPackets.isEmpty()) || sendEnd == 255)) {
                resetRequest = true;
                lastResetRequestTime = Machine.timer().getTime();
                return buildCtrlPacket(DLLPacket.Type.RESET, 0).toBytes();
            }
            // 发送新的数据包
            if (sendEnd - sendStart < cacheSize && sendEnd < 255) {
                byte[] data = (byte[]) userSendPackets.removeFirstOrNull();
                if (data != null) {
                    packet = new DLLPacket();
                    packet.packetType = DLLPacket.Type.DAT;
                    packet.packetNumber = sendEnd;
                    packet.content = data;
                    sendCache[sendEnd % cacheSize] = packet;
                    packet.sendTime = Machine.timer().getTime();
                    sendEnd++;
                    return packet.toBytes();
                }
            }
        }
        // 重置超时
        if (resetRequest && lastResetRequestTime + timeoutTime < Machine.timer().getTime()) {
            resetRequestTryCount++;
            if (resetRequestTryCount > tryCount) {
                terminate = true;
                return null;
            }
            return buildCtrlPacket(DLLPacket.Type.RESET, 0).toBytes();
        }
        return null;
    }

    /**
     * 获取下一个接收到的有效数据包
     * 如果目前没有接收到有效数据包，则返回 null
     *
     * @return 下一个接收到的有效数据包
     */
    public byte[] nextRecvPacket() {
        DLLPacket packet = (DLLPacket) recvPackets.removeFirstOrNull();
        if (packet == null) {
            return null;
        }
        return packet.content;
    }

    /**
     * 异步发送数据包
     *
     * @param data 数据
     */
    public void sendPacket(byte[] data) {
        if (terminate) return;
        Lib.assertTrue(data.length == maxContentsLength);
        userSendPackets.add(data);
    }

    public boolean isTerminate() {
        return terminate;
    }

    private DLLPacket buildCtrlPacket(DLLPacket.Type type, int number) {
        DLLPacket packet = new DLLPacket();
        packet.packetType = type;
        packet.packetNumber = (byte) number;
        return packet;
    }

    /**
     * 数据链路层数据包
     */
    private static class DLLPacket {
        enum Type {
            // 要求重发，同时暗示该数据包之前的数据均已接收
            // 参数：重发的数据包编号
            REJ(0x10),
            // 收到确认
            // 参数：收到的数据包编号
            RR(0x20),
            // 申请重置编号（发送端请求）
            RESET(0x30),
            // 收到重置请求
            RESETOK(0x40),
            // 数据
            DAT(0);

            final byte flag;

            Type(int header) {
                this.flag = (byte) header;
            }

            static Type getFromFlag(int flag) {
                for (Type type : values()) {
                    if (type.flag == flag) {
                        return type;
                    }
                }
                return null;
            }
        }

        // 数据包类型
        Type packetType;
        // 数据包编号（无符号数）
        int packetNumber;
        // 内容
        byte[] content = new byte[maxContentsLength];

        // 发送时的时钟时间
        long sendTime;
        // 重传次数
        int resendCount;

        byte[] toBytes() {
            byte[] data = new byte[maxContentsLength + 2];
            data[0] = packetType.flag;
            data[1] = (byte) packetNumber;
            System.arraycopy(content, 0, data, 2, maxContentsLength);
            return data;
        }
    }
}
