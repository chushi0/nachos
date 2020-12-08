package nachos.network;

import nachos.machine.Machine;
import nachos.machine.OpenFile;
import nachos.threads.SynchList;

/**
 * 网络层
 */
public class NetworkLayer extends OpenFile {
    private static final int maxContentsLength = DataLinkLayer.maxContentsLength - 2;

    // 超时时间
    private static final int timeoutTime = 10000000;

    private final DataLinkLayer dataLinkLayer;
    private State state;
    private boolean canRead;
    private boolean canWrite;

    private final SynchList readBuffer;
    private NLPacket currentRead;
    private int currentReadOffset;

    private boolean finalize;
    private long finalizeTime;

    public NetworkLayer(DataLinkLayer dataLinkLayer) {
        this.dataLinkLayer = dataLinkLayer;
        this.state = State.INIT;
        canRead = false;
        canWrite = false;

        readBuffer = new SynchList();
    }

    /**
     * 发起连接请求
     */
    public void connect() {
        if (state == State.INIT) {
            dataLinkLayer.sendPacket(buildNLPacket(NLPacket.Type.SYN).toBytes());
            finalize = true;
            finalizeTime = Machine.timer().getTime() + timeoutTime;
        }
    }

    /**
     * 刷新逻辑（从数据链路层读取数据包）
     */
    public void updateLogic() {
        if (state == State.CLOSE) return;
        byte[] data;
        while ((data = dataLinkLayer.nextRecvPacket()) != null) {
            NLPacket packet = new NLPacket();
            packet.type = NLPacket.Type.getByFlag(data[0]);
            packet.fillCount = data[1];
            System.arraycopy(data, 2, packet.data, 0, maxContentsLength);
            switch (packet.type) {
                case DAT: {
                    // 接收到数据
                    readBuffer.add(packet);
                    break;
                }
                case SYN: {
                    // 接收到连接请求
                    if (state == State.INIT) {
                        dataLinkLayer.sendPacket(buildNLPacket(NLPacket.Type.ACP).toBytes());
                        state = State.ACCEPT;
                        finalize = true;
                        finalizeTime = Machine.timer().getTime() + timeoutTime;
                    }
                    break;
                }
                case ACP: {
                    // 服务器同意连接
                    finalize = false;
                    state = State.RUN;
                    dataLinkLayer.sendPacket(buildNLPacket(NLPacket.Type.ACK).toBytes());
                    break;
                }
                case ACK: {
                    // 连接成功
                    finalize = false;
                    state = State.RUN;
                    break;
                }
                case FIN: {
                    // 对端不再发送数据
                    canRead = false;
                    if (!canWrite) {
                        finalize = true;
                        finalizeTime = Machine.timer().getTime() + timeoutTime;
                    }
                }
            }
        }
        if ((finalize && Machine.timer().getTime() > finalizeTime) || dataLinkLayer.isTerminate()) {
            state = State.CLOSE;
        }
    }

    /**
     * 连接是否已经关闭
     *
     * @return 如果已经关闭，则返回 true
     */
    public boolean isClose() {
        return state == State.CLOSE;
    }

    @Override
    public int read(byte[] buf, int offset, int length) {
        int readCount = 0;
        while (readCount < length) {
            if (currentRead == null || currentReadOffset >= currentRead.fillCount) {
                currentRead = (NLPacket) readBuffer.removeFirstOrNull();
                currentReadOffset = 0;
                if (currentRead == null) {
                    break;
                }
            }
            int c = Math.min(length - readCount, currentRead.fillCount - currentReadOffset);
            System.arraycopy(currentRead.data, currentReadOffset, buf, offset + readCount, c);
            currentReadOffset += c;
            readCount += c;
        }
        return readCount;
    }

    @Override
    public int write(byte[] buf, int offset, int length) {
        if (!canWrite) return -1;
        // 分包
        int cacheLength = length;
        while (length > 0) {
            NLPacket packet = new NLPacket();
            packet.type = NLPacket.Type.DAT;
            packet.fillCount = (byte) Math.min(length, maxContentsLength);
            System.arraycopy(buf, offset, packet.data, 0, packet.fillCount);
            dataLinkLayer.sendPacket(packet.toBytes());
            offset += packet.fillCount;
            length -= packet.fillCount;
        }
        return cacheLength;
    }

    @Override
    public void close() {
        canWrite = false;
        dataLinkLayer.sendPacket(buildNLPacket(NLPacket.Type.FIN).toBytes());
        if (!canRead) {
            finalize = true;
            finalizeTime = Machine.timer().getTime() + timeoutTime;
        }
    }

    private NLPacket buildNLPacket(NLPacket.Type type) {
        NLPacket packet = new NLPacket();
        packet.type = type;
        return packet;
    }

    enum State {
        // 初始化
        INIT,
        // 接收
        ACCEPT,
        // 运行
        RUN,
        // 关闭
        CLOSE;
    }

    /**
     * TCP 协议所使用的数据包
     */
    static class NLPacket {
        /**
         * 数据包类型
         * <p>
         * 握手：SYN、ACP、ACK
         * 挥手：FIN、FIN
         * 数据：DAT
         */
        enum Type {
            DAT(0),
            SYN(0x10),
            ACP(0x20),
            ACK(0x30),
            FIN(0x40);

            private final byte flag;

            Type(int flag) {
                this.flag = (byte) flag;
            }

            static Type getByFlag(byte flag) {
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
        byte[] data = new byte[maxContentsLength];

        byte[] toBytes() {
            byte[] data = new byte[maxContentsLength + 2];
            data[0] = type.flag;
            data[1] = (byte) fillCount;
            System.arraycopy(this.data, 0, data, 2, maxContentsLength);
            return data;
        }
    }
}
