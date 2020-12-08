package nachos.threads;

import nachos.machine.*;

import java.util.*;

/**
 * A scheduler that chooses threads using a lottery.
 *
 * <p>
 * A lottery scheduler associates a number of tickets with each thread. When a
 * thread needs to be dequeued, a random lottery is held, among all the tickets
 * of all the threads waiting to be dequeued. The thread that holds the winning
 * ticket is chosen.
 *
 * <p>
 * Note that a lottery scheduler must be able to handle a lot of tickets
 * (sometimes billions), so it is not acceptable to maintain state for every
 * ticket.
 *
 * <p>
 * A lottery scheduler must partially solve the priority inversion problem; in
 * particular, tickets must be transferred through locks, and through joins.
 * Unlike a priority scheduler, these tickets add (as opposed to just taking
 * the maximum).
 */
public class LotteryScheduler extends PriorityScheduler {
    /**
     * Allocate a new lottery scheduler.
     */
    public LotteryScheduler() {
    }

    /**
     * Allocate a new lottery thread queue.
     *
     * @param transferPriority <tt>true</tt> if this queue should
     *                         transfer tickets from waiting threads
     *                         to the owning thread.
     * @return a new lottery thread queue.
     */
    public ThreadQueue newThreadQueue(boolean transferPriority) {
        // implement me
        return new LotteryQueue(transferPriority);
    }

    protected LotteryState getThreadState(KThread thread) {
        if (thread.schedulingState == null)
            thread.schedulingState = new LotteryState(thread);

        return (LotteryState) thread.schedulingState;
    }

    protected class LotteryQueue extends PriorityScheduler.PriorityQueue {

        LotteryQueue(boolean transferPriority) {
            super(transferPriority);
        }

        public void waitForAccess(KThread thread) {
            Lib.assertTrue(Machine.interrupt().disabled());
            LotteryState threadState = getThreadState(thread);
            threadState.waitForAccess(this);
            threadList.add(threadState);
            if (transferPriority) {
                acquireThread.transferPriority(this, threadState.getEffectivePriority());
            }
        }

        public void acquire(KThread thread) {
            Lib.assertTrue(Machine.interrupt().disabled());
            LotteryState threadState = getThreadState(thread);
            threadState.acquire(this);
            acquireThread = threadState;
        }

        public KThread nextThread() {
            Lib.assertTrue(Machine.interrupt().disabled());
            // implement me
            if (acquireThread != null) {//当前线程退出，重置有效优先级
                acquireThread.exit(this);
            }
            LotteryState threadState = pickNextThread();
            acquireThread = threadState;
            threadList.remove(threadState);
            if (threadState == null) return null;
            threadState.acquire(this);
            return threadState.thread;
        }

        @Override
        public LotteryState pickNextThread() {
            // implement me
            int totalPriority = 0;
            //排序
//            threadList.sort(Comparator.comparing(LotteryState::getPriority));
            for (int i = 0; i < threadList.size(); i++) {
                for (int j = i; j < threadList.size() - 1; j++) {
                    if (threadList.get(j).getEffectivePriority() > threadList.get(j + 1).getEffectivePriority()) {
                        LotteryState temp = threadList.remove(j + 1);
                        threadList.add(j, temp);
                    }
                }
            }
            //计算彩票总和
            for (LotteryState threadState : threadList) {
                totalPriority += threadState.getEffectivePriority();
            }
            int rand = Lib.random(totalPriority + 1);//from 1 to totalPriority
            int index = 0;
            for (int i = threadList.size() - 1; i > 0; i--) {
                if (rand >= threadList.get(i).getEffectivePriority()) {
                    index = i;
                    break;
                }
            }
            if (threadList.size() == 0) return null;
            else return threadList.get(index);
        }

        //根据等待线程的有效优先级更改当前线程的有效优先级
        @Override
        protected void changePriority() {
            if (!transferPriority) return;
            int totalPriority = priorityMinimum - 1;
            //计算彩票总和
            for (LotteryState threadState : threadList) {
                totalPriority += threadState.getEffectivePriority();
            }
            acquireThread.transferPriority(this, totalPriority);
        }


        // 等待线程列表
        private final LinkedList<LotteryState> threadList = new LinkedList<>();
        // 持有锁的线程
        private LotteryState acquireThread;
    }

    protected class LotteryState extends ThreadState {

        /**
         * Allocate a new <tt>ThreadState</tt> object and associate it with the
         * specified thread.
         *
         * @param thread the thread this state belongs to.
         */
        public LotteryState(KThread thread) {
            super(thread);
        }


        protected void transferPriority(LotteryQueue waitQueue, int priority) {
            effectPriorities.put(waitQueue, priority);
            recomputeEffectPriority();
        }

        @Override
        protected void recomputeEffectPriority() {
            int totalPriority = 0;
            for (Map.Entry<LotteryQueue, Integer> entry : effectPriorities.entrySet()) {
                totalPriority += entry.getValue();
            }
            if (totalPriority != effectPriority) {
                changeEffectPriority(totalPriority);
            }
        }

        private final HashMap<LotteryQueue, Integer> effectPriorities = new HashMap<>();
        private LotteryQueue waitQueue;
    }
}
