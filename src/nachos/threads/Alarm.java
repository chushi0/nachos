package nachos.threads;

import nachos.machine.Machine;

import java.util.LinkedList;

/**
 * Uses the hardware timer to provide preemption, and to allow threads to sleep
 * until a certain time.
 */
public class Alarm {
    /**
     * Allocate a new Alarm. Set the machine's timer interrupt handler to this
     * alarm's callback.
     *
     * <p><b>Note</b>: Nachos will not function correctly with more than one
     * alarm.
     */
    public Alarm() {
        waitStructList = new LinkedList<>();
        Machine.timer().setInterruptHandler(new Runnable() {
            public void run() {
                timerInterrupt();
            }
        });
    }

    /**
     * The timer interrupt handler. This is called by the machine's timer
     * periodically (approximately every 500 clock ticks). Causes the current
     * thread to yield, forcing a context switch if there is another thread
     * that should be run.
     */
    public void timerInterrupt() {
        // 当前时间
        long time = Machine.timer().getTime();
        // 使用循环，处理需要唤醒多个线程的情况
        while (!waitStructList.isEmpty()) {
            WaitStruct waitStruct = waitStructList.getFirst();
            // 如果这个线程不需要唤醒，则后面的也不需要。退出循环
            if (waitStruct.wakeTime > time) {
                break;
            }
            // 唤醒这个线程
            waitStruct.thread.ready();
            waitStructList.removeFirst();
        }
        KThread.yield();
    }

    /**
     * Put the current thread to sleep for at least <i>x</i> ticks,
     * waking it up in the timer interrupt handler. The thread must be
     * woken up (placed in the scheduler ready set) during the first timer
     * interrupt where
     *
     * <p><blockquote>
     * (current time) >= (WaitUntil called time)+(x)
     * </blockquote>
     *
     * @param x the minimum number of clock ticks to wait.
     * @see nachos.machine.Timer#getTime()
     */
    public void waitUntil(long x) {
        // 计算时间
        long wakeTime = Machine.timer().getTime() + x;
        // 构造结构体
        WaitStruct waitStruct = new WaitStruct();
        waitStruct.wakeTime = wakeTime;
        waitStruct.thread = KThread.currentThread();

        // 关中断
        boolean intStatus = Machine.interrupt().disable();

        // 插入队列，同时保证队列依旧有序
        boolean insert = false;
        for (int i = 0, size = waitStructList.size(); i < size; i++) {
            if (waitStructList.get(i).wakeTime > wakeTime) {
                waitStructList.add(i, waitStruct);
                insert = true;
                break;
            }
        }
        if (!insert) {
            waitStructList.add(waitStruct);
        }

        // 挂起线程
        KThread.sleep();

        // 恢复中断
        Machine.interrupt().restore(intStatus);
    }

    // 等待队列（有序）
    private final LinkedList<WaitStruct> waitStructList;

    // 描述唤醒时间和等待线程的结构体
    private static class WaitStruct {
        // 唤醒时间（绝对时间）
        long wakeTime;
        // 关联的线程
        KThread thread;
    }
}
