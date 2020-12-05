package nachos.threads;

/**
 * A <i>communicator</i> allows threads to synchronously exchange 32-bit
 * messages. Multiple threads can be waiting to <i>speak</i>,
 * and multiple threads can be waiting to <i>listen</i>. But there should never
 * be a time when both a speaker and a listener are waiting, because the two
 * threads can be paired off at this point.
 */
public class Communicator {

    private final Lock lock;
    private final Condition condition;
    private boolean speakIn, listenIn;
    private int bufferWord;

    /**
     * Allocate a new communicator.
     */
    public Communicator() {
        lock = new Lock();
        condition = new Condition(lock);
    }

    /**
     * Wait for a thread to listen through this communicator, and then transfer
     * <i>word</i> to the listener.
     *
     * <p>
     * Does not return until this thread is paired up with a listening thread.
     * Exactly one listener should receive <i>word</i>.
     *
     * @param word the integer to transfer.
     */
    public void speak(int word) {

        // 防止多个 speak 线程进入临界区
        while (true) {
            lock.acquire();
            if (speakIn) {
                lock.release();
                KThread.yield();
            } else {
                speakIn = true;
                break;
            }
        }

        // 设置交换的内容
        bufferWord = word;
        // listen 线程还未进入，等待
        if (!listenIn) {
            condition.sleep();
        } else {

            // 唤醒 listen 线程
            condition.wake();
        }
        // 解锁
        lock.release();
    }

    /**
     * Wait for a thread to speak through this communicator, and then return
     * the <i>word</i> that thread passed to <tt>speak()</tt>.
     *
     * @return the integer transferred.
     */
    public int listen() {
        int word;

        // 防止多个 listen 线程进入临界区
        while (true) {
            lock.acquire();
            if (listenIn) {
                lock.release();
                KThread.yield();
            } else {
                listenIn = true;
                break;
            }
        }

        // speak 线程已经进入，唤醒 speak 线程
        if (speakIn) {
            condition.wake();
        } else {

            // 等待 speak 线程设置交换内容
            condition.sleep();
        }
        // 读取
        word = bufferWord;

        // 设置标记，speak 和 listen 已经退出
        // 需在 listen 接收完数据后，才能进行下一组数据交换
        speakIn = false;
        listenIn = false;

        // 开锁
        lock.release();

        // 返回
        return word;
    }
}
