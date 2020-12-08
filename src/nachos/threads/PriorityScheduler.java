package nachos.threads;

import nachos.machine.*;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

/**
 * A scheduler that chooses threads based on their priorities.
 *
 * <p>
 * A priority scheduler associates a priority with each thread. The next thread
 * to be dequeued is always a thread with priority no less than any other
 * waiting thread's priority. Like a round-robin scheduler, the thread that is
 * dequeued is, among all the threads of the same (highest) priority, the
 * thread that has been waiting longest.
 *
 * <p>
 * Essentially, a priority scheduler gives access in a round-robin fassion to
 * all the highest-priority threads, and ignores all other threads. This has
 * the potential to
 * starve a thread if there's always a thread waiting with higher priority.
 *
 * <p>
 * A priority scheduler must partially solve the priority inversion problem; in
 * particular, priority must be donated through locks, and through joins.
 */
public class PriorityScheduler extends Scheduler {
    /**
     * Allocate a new priority scheduler.
     */
    public PriorityScheduler() {
    }

    /**
     * Allocate a new priority thread queue.
     *
     * @param transferPriority <tt>true</tt> if this queue should
     *                         transfer priority from waiting threads
     *                         to the owning thread.
     * @return a new priority thread queue.
     */
    public ThreadQueue newThreadQueue(boolean transferPriority) {
        return new PriorityQueue(transferPriority);
    }

    public int getPriority(KThread thread) {
        Lib.assertTrue(Machine.interrupt().disabled());

        return getThreadState(thread).getPriority();
    }

    public int getEffectivePriority(KThread thread) {
        Lib.assertTrue(Machine.interrupt().disabled());

        return getThreadState(thread).getEffectivePriority();
    }

    public void setPriority(KThread thread, int priority) {
        Lib.assertTrue(Machine.interrupt().disabled());

        Lib.assertTrue(priority >= priorityMinimum &&
                priority <= priorityMaximum);

        getThreadState(thread).setPriority(priority);
    }

    public boolean increasePriority() {
        boolean intStatus = Machine.interrupt().disable();

        KThread thread = KThread.currentThread();

        int priority = getPriority(thread);
        if (priority == priorityMaximum)
            return false;

        setPriority(thread, priority + 1);

        Machine.interrupt().restore(intStatus);
        return true;
    }

    public boolean decreasePriority() {
        boolean intStatus = Machine.interrupt().disable();

        KThread thread = KThread.currentThread();

        int priority = getPriority(thread);
        if (priority == priorityMinimum)
            return false;

        setPriority(thread, priority - 1);

        Machine.interrupt().restore(intStatus);
        return true;
    }

    /**
     * The default priority for a new thread. Do not change this value.
     */
    public static final int priorityDefault = 1;
    /**
     * The minimum priority that a thread can have. Do not change this value.
     */
    public static final int priorityMinimum = 0;
    /**
     * The maximum priority that a thread can have. Do not change this value.
     */
    public static final int priorityMaximum = 7;

    /**
     * Return the scheduling state of the specified thread.
     *
     * @param thread the thread whose scheduling state to return.
     * @return the scheduling state of the specified thread.
     */
    protected ThreadState getThreadState(KThread thread) {
        if (thread.schedulingState == null)
            thread.schedulingState = new ThreadState(thread);

        return (ThreadState) thread.schedulingState;
    }

    /**
     * A <tt>ThreadQueue</tt> that sorts threads by priority.
     */
    protected class PriorityQueue extends ThreadQueue {
        PriorityQueue(boolean transferPriority) {
            this.transferPriority = transferPriority;
        }

        public void waitForAccess(KThread thread) {
            Lib.assertTrue(Machine.interrupt().disabled());
            ThreadState threadState = getThreadState(thread);
            threadState.waitForAccess(this);
            threadList.add(threadState);
            if (transferPriority) {
                acquireThread.transferPriority(this, threadState.getEffectivePriority());
            }
        }

        public void acquire(KThread thread) {
            Lib.assertTrue(Machine.interrupt().disabled());
            ThreadState threadState = getThreadState(thread);
            threadState.acquire(this);
            acquireThread = threadState;
        }

        public KThread nextThread() {
            Lib.assertTrue(Machine.interrupt().disabled());
            // implement me
            if (acquireThread != null) {//当前线程退出，重置有效优先级
                acquireThread.exit(this);
            }
            ThreadState threadState = pickNextThread();
            acquireThread = threadState;
            threadList.remove(threadState);
            if (threadState == null) return null;
            threadState.acquire(this);
            return threadState.thread;
        }

        /**
         * Return the next thread that <tt>nextThread()</tt> would return,
         * without modifying the state of this queue.
         *
         * @return the next thread that <tt>nextThread()</tt> would
         * return.
         */
        protected ThreadState pickNextThread() {
            // implement me
            int maxPriority = priorityMinimum - 1;
            ThreadState maxPriorityThread = null;
            for (ThreadState threadState : threadList) {
                if (threadState.getEffectivePriority() > maxPriority) {
                    maxPriority = threadState.getEffectivePriority();
                    maxPriorityThread = threadState;
                }
            }
            return maxPriorityThread;
        }

        public void print() {
            Lib.assertTrue(Machine.interrupt().disabled());
            // implement me (if you want)
        }

        //根据等待线程的有效优先级更改当前线程的有效优先级
        protected void changePriority() {
            if (!transferPriority) return;
            int max = priorityMinimum - 1;
            for (ThreadState threadState : threadList) {
                if (threadState.getEffectivePriority() > max) {
                    max = threadState.getEffectivePriority();
                }
            }
            acquireThread.transferPriority(this, max);
        }

        /**
         * <tt>true</tt> if this queue should transfer priority from waiting
         * threads to the owning thread.
         */
        public boolean transferPriority;

        // 等待线程列表
        private final LinkedList<ThreadState> threadList = new LinkedList<>();
        // 持有锁的线程
        private ThreadState acquireThread;
    }

    /**
     * The scheduling state of a thread. This should include the thread's
     * priority, its effective priority, any objects it owns, and the queue
     * it's waiting for, if any.
     *
     * @see KThread#schedulingState
     */
    protected class ThreadState {
        /**
         * Allocate a new <tt>ThreadState</tt> object and associate it with the
         * specified thread.
         *
         * @param thread the thread this state belongs to.
         */
        public ThreadState(KThread thread) {
            this.thread = thread;

            setPriority(priorityDefault);
        }

        /**
         * Return the priority of the associated thread.
         *
         * @return the priority of the associated thread.
         */
        public int getPriority() {
            return priority;
        }

        /**
         * Return the effective priority of the associated thread.
         *
         * @return the effective priority of the associated thread.
         */
        public int getEffectivePriority() {
            // implement me
            return this.effectPriority;
        }

        /**
         * Set the priority of the associated thread to the specified value.
         *
         * @param priority the new priority.
         */
        public void setPriority(int priority) {
            if (this.priority == priority)
                return;

            this.priority = priority;

            // implement me
            if (this.effectPriority < priority) {//改变有效优先级
                changeEffectPriority(priority);
            }
        }

        protected void changeEffectPriority(int priority) {
            this.effectPriority = priority;
            if (waitQueue != null) {//若队列里的有效优先级改变，则会改变当前线程的有效优先级
                waitQueue.changePriority();
            }
        }

        /**
         * Called when <tt>waitForAccess(thread)</tt> (where <tt>thread</tt> is
         * the associated thread) is invoked on the specified priority queue.
         * The associated thread is therefore waiting for access to the
         * resource guarded by <tt>waitQueue</tt>. This method is only called
         * if the associated thread cannot immediately obtain access.
         *
         * @param waitQueue the queue that the associated thread is
         *                  now waiting on.
         * @see ThreadQueue#waitForAccess
         */
        protected void waitForAccess(PriorityQueue waitQueue) {
            // implement me
            this.waitQueue = waitQueue;
        }

        /**
         * Called when the associated thread has acquired access to whatever is
         * guarded by <tt>waitQueue</tt>. This can occur either as a result of
         * <tt>acquire(thread)</tt> being invoked on <tt>waitQueue</tt> (where
         * <tt>thread</tt> is the associated thread), or as a result of
         * <tt>nextThread()</tt> being invoked on <tt>waitQueue</tt>.
         *
         * @see ThreadQueue#acquire
         * @see ThreadQueue#nextThread
         */
        protected void acquire(PriorityQueue waitQueue) {
            // implement me
            Lib.assertTrue(this.waitQueue == null || waitQueue == this.waitQueue);
            //当前线程移除等待队列
            if (waitQueue == this.waitQueue) {
                this.waitQueue = null;
            }
        }

        protected void transferPriority(PriorityQueue waitQueue, int priority) {
            effectPriorities.put(waitQueue, priority);
            recomputeEffectPriority();
        }

        protected void exit(PriorityQueue waitQueue) {
//            Integer priority = effectPriorities.get(waitQueue);
            Integer priority = effectPriorities.remove(waitQueue);
            if (priority == null) return;
            recomputeEffectPriority();
        }

        /**
         * 1、调用waitForAccess方法有新的线程加入的时候有可能调用该方法
         * 2、等待队列中的线程优先级改变(提高)的时候调用
         * 3、调用nextThread方法，当前线程的有效优先级有可能会改变
         */
        protected void recomputeEffectPriority() {
            int max = this.priority;
            for (Map.Entry<PriorityQueue, Integer> entry : effectPriorities.entrySet()) {
                if (entry.getValue() > max) {
                    max = entry.getValue();
                }
            }
            if (max != effectPriority) {
                changeEffectPriority(max);
            }
        }

        /**
         * The thread with which this object is associated.
         */
        protected KThread thread;
        /**
         * The priority of the associated thread.
         */
        protected int priority;

        protected int effectPriority;
        private final HashMap<PriorityQueue, Integer> effectPriorities = new HashMap<>();
        private PriorityQueue waitQueue;
    }
}
