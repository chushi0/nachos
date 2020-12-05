package nachos.threads;

import nachos.machine.*;

import java.util.LinkedList;

/**
 * An implementation of condition variables that disables interrupt()s for
 * synchronization.
 *
 * <p>
 * You must implement this.
 *
 * @see nachos.threads.Condition
 */
public class Condition2 {
    /**
     * Allocate a new condition variable.
     *
     * @param conditionLock the lock associated with this condition
     *                      variable. The current thread must hold this
     *                      lock whenever it uses <tt>sleep()</tt>,
     *                      <tt>wake()</tt>, or <tt>wakeAll()</tt>.
     */
    public Condition2(Lock conditionLock) {
        this.conditionLock = conditionLock;
        waitList = new LinkedList<>();
    }

    /**
     * Atomically release the associated lock and go to sleep on this condition
     * variable until another thread wakes it using <tt>wake()</tt>. The
     * current thread must hold the associated lock. The thread will
     * automatically reacquire the lock before <tt>sleep()</tt> returns.
     */
    public void sleep() {
        Lib.assertTrue(conditionLock.isHeldByCurrentThread());

        // 关中断
        boolean intStatus = Machine.interrupt().disable();
        // 加入等待队列
        waitList.add(KThread.currentThread());

        // 释放锁
        conditionLock.release();
        // 挂起
        KThread.sleep();
        // 重新获得锁
        conditionLock.acquire();

        // 恢复中断
        Machine.interrupt().restore(intStatus);
    }

    /**
     * Wake up at most one thread sleeping on this condition variable. The
     * current thread must hold the associated lock.
     */
    public void wake() {
        Lib.assertTrue(conditionLock.isHeldByCurrentThread());

        // 关中断
        boolean intState = Machine.interrupt().disable();

        // 非空的话，唤醒一个线程
        if (!waitList.isEmpty())
            waitList.removeFirst().ready();

        // 恢复中断
        Machine.interrupt().restore(intState);
    }

    /**
     * Wake up all threads sleeping on this condition variable. The current
     * thread must hold the associated lock.
     */
    public void wakeAll() {
        Lib.assertTrue(conditionLock.isHeldByCurrentThread());

        // 循环唤醒线程，直到队列非空
        while (!waitList.isEmpty())
            wake();
    }

    private final Lock conditionLock;
    private final LinkedList<KThread> waitList;
}
