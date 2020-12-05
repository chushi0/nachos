package nachos.threads;

import nachos.machine.*;

/**
 * A KThread is a thread that can be used to execute Nachos kernel code. Nachos
 * allows multiple threads to run concurrently.
 * <p>
 * To create a new thread of execution, first declare a class that implements
 * the <tt>Runnable</tt> interface. That class then implements the <tt>run</tt>
 * method. An instance of the class can then be allocated, passed as an
 * argument when creating <tt>KThread</tt>, and forked. For example, a thread
 * that computes pi could be written as follows:
 *
 * <p><blockquote><pre>
 * class PiRun implements Runnable {
 *     public void run() {
 *         // compute pi
 *         ...
 *     }
 * }
 * </pre></blockquote>
 * <p>The following code would then create a thread and start it running:
 *
 * <p><blockquote><pre>
 * PiRun p = new PiRun();
 * new KThread(p).fork();
 * </pre></blockquote>
 */
public class KThread {
    /**
     * Get the current thread.
     *
     * @return the current thread.
     */
    public static KThread currentThread() {
        Lib.assertTrue(currentThread != null);
        return currentThread;
    }

    /**
     * Allocate a new <tt>KThread</tt>. If this is the first <tt>KThread</tt>,
     * create an idle thread as well.
     */
    public KThread() {
        if (currentThread != null) {
            tcb = new TCB();
        } else {
            readyQueue = ThreadedKernel.scheduler.newThreadQueue(false);
            readyQueue.acquire(this);

            currentThread = this;
            tcb = TCB.currentTCB();
            name = "main";
            restoreState();

            createIdleThread();
        }

        boolean intStatus = Machine.interrupt().disable();
        joinThreadQueue.acquire(this);
        Machine.interrupt().restore(intStatus);
    }

    /**
     * Allocate a new KThread.
     *
     * @param target the object whose <tt>run</tt> method is called.
     */
    public KThread(Runnable target) {
        this();
        this.target = target;
    }

    /**
     * Set the target of this thread.
     *
     * @param target the object whose <tt>run</tt> method is called.
     * @return this thread.
     */
    public KThread setTarget(Runnable target) {
        Lib.assertTrue(status == statusNew);

        this.target = target;
        return this;
    }

    /**
     * Set the name of this thread. This name is used for debugging purposes
     * only.
     *
     * @param name the name to give to this thread.
     * @return this thread.
     */
    public KThread setName(String name) {
        this.name = name;
        return this;
    }

    /**
     * Get the name of this thread. This name is used for debugging purposes
     * only.
     *
     * @return the name given to this thread.
     */
    public String getName() {
        return name;
    }

    /**
     * Get the full name of this thread. This includes its name along with its
     * numerical ID. This name is used for debugging purposes only.
     *
     * @return the full name given to this thread.
     */
    public String toString() {
        return (name + " (#" + id + ")");
    }

    /**
     * Deterministically and consistently compare this thread to another
     * thread.
     */
    public int compareTo(Object o) {
        KThread thread = (KThread) o;

        if (id < thread.id)
            return -1;
        else if (id > thread.id)
            return 1;
        else
            return 0;
    }

    /**
     * Causes this thread to begin execution. The result is that two threads
     * are running concurrently: the current thread (which returns from the
     * call to the <tt>fork</tt> method) and the other thread (which executes
     * its target's <tt>run</tt> method).
     */
    public void fork() {
        Lib.assertTrue(status == statusNew);
        Lib.assertTrue(target != null);

        Lib.debug(dbgThread,
                "Forking thread: " + toString() + " Runnable: " + target);

        boolean intStatus = Machine.interrupt().disable();

        tcb.start(new Runnable() {
            public void run() {
                runThread();
            }
        });

        ready();

        Machine.interrupt().restore(intStatus);
    }

    private void runThread() {
        begin();
        target.run();
        finish();
    }

    private void begin() {
        Lib.debug(dbgThread, "Beginning thread: " + toString());

        Lib.assertTrue(this == currentThread);

        restoreState();

        Machine.interrupt().enable();
    }

    /**
     * Finish the current thread and schedule it to be destroyed when it is
     * safe to do so. This method is automatically called when a thread's
     * <tt>run</tt> method returns, but it may also be called directly.
     * <p>
     * The current thread cannot be immediately destroyed because its stack and
     * other execution state are still in use. Instead, this thread will be
     * destroyed automatically by the next thread to run, when it is safe to
     * delete this thread.
     */
    public static void finish() {
        Lib.debug(dbgThread, "Finishing thread: " + currentThread.toString());

        Machine.interrupt().disable();

        Machine.autoGrader().finishingCurrentThread();

        Lib.assertTrue(toBeDestroyed == null);
        toBeDestroyed = currentThread;

        // =============================================================================================================
        // BEGIN CHANGE
        // =============================================================================================================

        // 如果有线程在等待，唤醒线程
        KThread thread;
        while ((thread = currentThread.joinThreadQueue.nextThread()) != null)
            thread.ready();

        // =============================================================================================================
        // END CHANGE
        // =============================================================================================================

        currentThread.status = statusFinished;

        sleep();
    }

    /**
     * Relinquish the CPU if any other thread is ready to run. If so, put the
     * current thread on the ready queue, so that it will eventually be
     * rescheuled.
     *
     * <p>
     * Returns immediately if no other thread is ready to run. Otherwise
     * returns when the current thread is chosen to run again by
     * <tt>readyQueue.nextThread()</tt>.
     *
     * <p>
     * Interrupts are disabled, so that the current thread can atomically add
     * itself to the ready queue and switch to the next thread. On return,
     * restores interrupts to the previous state, in case <tt>yield()</tt> was
     * called with interrupts disabled.
     */
    public static void yield() {
        Lib.debug(dbgThread, "Yielding thread: " + currentThread.toString());

        Lib.assertTrue(currentThread.status == statusRunning);

        boolean intStatus = Machine.interrupt().disable();

        currentThread.ready();

        runNextThread();

        Machine.interrupt().restore(intStatus);
    }

    /**
     * Relinquish the CPU, because the current thread has either finished or it
     * is blocked. This thread must be the current thread.
     *
     * <p>
     * If the current thread is blocked (on a synchronization primitive, i.e.
     * a <tt>Semaphore</tt>, <tt>Lock</tt>, or <tt>Condition</tt>), eventually
     * some thread will wake this thread up, putting it back on the ready queue
     * so that it can be rescheduled. Otherwise, <tt>finish()</tt> should have
     * scheduled this thread to be destroyed by the next thread to run.
     */
    public static void sleep() {
        Lib.debug(dbgThread, "Sleeping thread: " + currentThread.toString());

        Lib.assertTrue(Machine.interrupt().disabled());

        if (currentThread.status != statusFinished)
            currentThread.status = statusBlocked;

        runNextThread();
    }

    /**
     * Moves this thread to the ready state and adds this to the scheduler's
     * ready queue.
     */
    public void ready() {
        Lib.debug(dbgThread, "Ready thread: " + toString());

        Lib.assertTrue(Machine.interrupt().disabled());
        Lib.assertTrue(status != statusReady);

        status = statusReady;
        if (this != idleThread)
            readyQueue.waitForAccess(this);

        Machine.autoGrader().readyThread(this);
    }

    /**
     * Waits for this thread to finish. If this thread is already finished,
     * return immediately. This method must only be called once; the second
     * call is not guaranteed to return. This thread must not be the current
     * thread.
     */
    public void join() {
        Lib.debug(dbgThread, "Joining to thread: " + toString());

        Lib.assertTrue(this != currentThread);

        // =============================================================================================================
        // BEGIN CHANGE
        // =============================================================================================================

        // 关中断
        boolean intStatus = Machine.interrupt().disable();

        // 如果线程已经终止，则不阻塞
        if (status == statusFinished) {
            Machine.interrupt().restore(intStatus);
            return;
        }

        joinThreadQueue.waitForAccess(currentThread);
        // 阻塞当前线程
        sleep();

        // 恢复中断
        Machine.interrupt().restore(intStatus);

        // =============================================================================================================
        // END CHANGE
        // =============================================================================================================
    }

    /**
     * Create the idle thread. Whenever there are no threads ready to be run,
     * and <tt>runNextThread()</tt> is called, it will run the idle thread. The
     * idle thread must never block, and it will only be allowed to run when
     * all other threads are blocked.
     *
     * <p>
     * Note that <tt>ready()</tt> never adds the idle thread to the ready set.
     */
    private static void createIdleThread() {
        Lib.assertTrue(idleThread == null);

        idleThread = new KThread(new Runnable() {
            public void run() {
                while (true) yield();
            }
        });
        idleThread.setName("idle");

        Machine.autoGrader().setIdleThread(idleThread);

        idleThread.fork();
    }

    /**
     * Determine the next thread to run, then dispatch the CPU to the thread
     * using <tt>run()</tt>.
     */
    private static void runNextThread() {
        KThread nextThread = readyQueue.nextThread();
        if (nextThread == null)
            nextThread = idleThread;

        nextThread.run();
    }

    /**
     * Dispatch the CPU to this thread. Save the state of the current thread,
     * switch to the new thread by calling <tt>TCB.contextSwitch()</tt>, and
     * load the state of the new thread. The new thread becomes the current
     * thread.
     *
     * <p>
     * If the new thread and the old thread are the same, this method must
     * still call <tt>saveState()</tt>, <tt>contextSwitch()</tt>, and
     * <tt>restoreState()</tt>.
     *
     * <p>
     * The state of the previously running thread must already have been
     * changed from running to blocked or ready (depending on whether the
     * thread is sleeping or yielding).
     *
     * @param finishing <tt>true</tt> if the current thread is
     *                  finished, and should be destroyed by the new
     *                  thread.
     */
    private void run() {
        Lib.assertTrue(Machine.interrupt().disabled());

        Machine.yield();

        currentThread.saveState();

        Lib.debug(dbgThread, "Switching from: " + currentThread.toString()
                + " to: " + toString());

        currentThread = this;

        tcb.contextSwitch();

        currentThread.restoreState();
    }

    /**
     * Prepare this thread to be run. Set <tt>status</tt> to
     * <tt>statusRunning</tt> and check <tt>toBeDestroyed</tt>.
     */
    protected void restoreState() {
        Lib.debug(dbgThread, "Running thread: " + currentThread.toString());

        Lib.assertTrue(Machine.interrupt().disabled());
        Lib.assertTrue(this == currentThread);
        Lib.assertTrue(tcb == TCB.currentTCB());

        Machine.autoGrader().runningThread(this);

        status = statusRunning;

        if (toBeDestroyed != null) {
            toBeDestroyed.tcb.destroy();
            toBeDestroyed.tcb = null;
            toBeDestroyed = null;
        }
    }

    /**
     * Prepare this thread to give up the processor. Kernel threads do not
     * need to do anything here.
     */
    protected void saveState() {
        Lib.assertTrue(Machine.interrupt().disabled());
        Lib.assertTrue(this == currentThread);
    }

    private static class PingTest implements Runnable {
        PingTest(int which) {
            this.which = which;
        }

        public void run() {
            for (int i = 0; i < 5; i++) {
                System.out.println("*** thread " + which + " looped "
                        + i + " times");
                KThread.yield();
            }
        }

        private final int which;
    }

    /**
     * Tests whether this module is working.
     */
    public static void selfTest() {
        // =============================================================================================================
        // BEGIN CHANGE
        // =============================================================================================================
        Lib.debug(dbgThread, "Enter KThread.selfTest");

        System.out.println("==== join() 测试 ====");
        KThread t1 = new KThread(new PingTest(1)).setName("KThread 1");
        KThread t2 = new KThread(new PingTest(2)).setName("KThread 2");

        t1.fork();
        t2.fork();

        t1.join();
        System.out.println("T1 stop");
        t2.join();
        System.out.println("T2 stop");

        System.out.println("==== 条件变量 测试 ====");
        Lock lock = new Lock();
        Condition2 condition2 = new Condition2(lock);

        KThread t3 = new KThread(new Runnable() {
            @Override
            public void run() {
                System.out.println("*** thread 3 尝试获取锁");
                lock.acquire();
                System.out.println("*** thread 3 获取到锁");
                KThread.yield();
                System.out.println("*** thread 3 休眠");
                condition2.sleep();
                System.out.println("*** thread 3 被唤醒并得到执行");
                KThread.yield();
                System.out.println("*** thread 3 释放锁");
                lock.release();
                KThread.yield();
            }
        }).setName("Thread 3");
        KThread t4 = new KThread(new Runnable() {
            @Override
            public void run() {
                System.out.println("*** thread 4 尝试获取锁");
                lock.acquire();
                System.out.println("*** thread 4 获取到锁");
                KThread.yield();
                System.out.println("*** thread 4 ping pong");
                new PingTest(4).run();
                System.out.println("*** thread 4 ping pong 结束");
                KThread.yield();
                System.out.println("*** thread 4 唤醒其他线程");
                condition2.wake();
                System.out.println("*** thread 4 已发送唤醒请求");
                KThread.yield();
                System.out.println("*** thread 4 释放锁");
                lock.release();
            }
        }).setName("Thread 4");

        t3.fork();
        t4.fork();

        t3.join();
        t4.join();

        System.out.println("==== waitUntil() 测试 ====");

        KThread t5 = new KThread(new Runnable() {
            @Override
            public void run() {
                System.out.println("获取第一个时间");
                System.out.println("当前时间" + Machine.timer().getTime());
                System.out.println("调用waitUntil方法");
                ThreadedKernel.alarm.waitUntil(50);
                System.out.println("获取第二个时间");
                System.out.println("当前时间" + Machine.timer().getTime());
            }
        });
        t5.fork();
        t5.join();

        System.out.println("==== communicator 测试 ====");

        Communicator communicator = new Communicator();
        KThread listenerOne = new KThread(new ListenThread(1, communicator));
        KThread listenerTwo = new KThread(new ListenThread(2, communicator));
        KThread speakerOne = new KThread(new SpeakThread(1, communicator));
        KThread speakerTwo = new KThread(new SpeakThread(2, communicator));
        System.out.println("开始运行");
        System.out.println("运行两个listener");
        listenerOne.fork();
        listenerTwo.fork();
        System.out.println("运行两个speaker");
        speakerOne.fork();
        speakerTwo.fork();

        listenerOne.join();
        listenerTwo.join();
        speakerOne.join();
        speakerTwo.join();

        System.out.println("==== 优先级队列测试 ====");

        Machine.interrupt().disable();

        KThread t6 = new KThread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 5; i++) {
                    Machine.interrupt().disable();
                    System.out.println("PING - t6 - " + ThreadedKernel.scheduler.getEffectivePriority());
                    Machine.interrupt().enable();
                    KThread.yield();
                }
            }
        });
        ThreadedKernel.scheduler.setPriority(t6, 2);
        t6.fork();

        KThread t7 = new KThread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 5; i++) {
                    Machine.interrupt().disable();
                    System.out.println("PING - t7 - " + ThreadedKernel.scheduler.getEffectivePriority());
                    Machine.interrupt().enable();
                    KThread.yield();
                }
            }
        });
        ThreadedKernel.scheduler.setPriority(t7, 4);
        t7.fork();

        KThread t8 = new KThread(new Runnable() {
            @Override
            public void run() {
                t6.join();
                for (int i = 0; i < 5; i++) {
                    Machine.interrupt().disable();
                    System.out.println("PING - t8 - " + ThreadedKernel.scheduler.getEffectivePriority());
                    Machine.interrupt().enable();
                    KThread.yield();
                }
            }
        });
        ThreadedKernel.scheduler.setPriority(t8, 6);
        t8.fork();

        Machine.interrupt().enable();

        t6.join();
        t7.join();
        t8.join();

        System.out.println("==== 原始测试 ====");

        // =============================================================================================================
        // END CHANGE
        // =============================================================================================================

        new KThread(new PingTest(1)).setName("forked thread").fork();
        new PingTest(0).run();
    }

    private static class ListenThread implements Runnable {

        int which;
        Communicator communicator;

        ListenThread(int which, Communicator communicator) {
            this.which = which;
            this.communicator = communicator;
        }

        @Override
        public void run() {
            System.out.println("listener " + which + " 准备倾听");
            int word = communicator.listen();
            System.out.println("listener " + which + " 已听到 - " + word);
        }
    }

    private static class SpeakThread implements Runnable {

        int which;
        Communicator communicator;

        SpeakThread(int which, Communicator communicator) {
            this.which = which;
            this.communicator = communicator;
        }

        @Override
        public void run() {
            System.out.println("speaker " + which + " 准备发言 - " + which);
            communicator.speak(which);
            System.out.println("speaker " + which + " 完成发言 - " + which);
        }
    }

    private static final char dbgThread = 't';

    /**
     * Additional state used by schedulers.
     *
     * @see nachos.threads.PriorityScheduler.ThreadState
     */
    public Object schedulingState = null;

    private static final int statusNew = 0;
    private static final int statusReady = 1;
    private static final int statusRunning = 2;
    private static final int statusBlocked = 3;
    private static final int statusFinished = 4;

    /**
     * The status of this thread. A thread can either be new (not yet forked),
     * ready (on the ready queue but not running), running, or blocked (not
     * on the ready queue and not running).
     */
    private int status = statusNew;
    private String name = "(unnamed thread)";
    private Runnable target;
    private TCB tcb;

    /**
     * Unique identifer for this thread. Used to deterministically compare
     * threads.
     */
    private int id = numCreated++;
    /**
     * Number of times the KThread constructor was called.
     */
    private static int numCreated = 0;

    private static ThreadQueue readyQueue = null;
    private static KThread currentThread = null;
    private static KThread toBeDestroyed = null;
    private static KThread idleThread = null;

    // =================================================================================================================
    // BEGIN CHANGE
    // =================================================================================================================

    // 在当前线程上等待的线程
    private ThreadQueue joinThreadQueue = ThreadedKernel.scheduler.newThreadQueue(true);

    // =================================================================================================================
    // END CHANGE
    // =================================================================================================================
}
