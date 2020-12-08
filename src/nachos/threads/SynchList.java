package nachos.threads;

import nachos.machine.Lib;

import java.util.LinkedList;

/**
 * A synchronized queue.
 */
public class SynchList {
    /**
     * Allocate a new synchronized queue.
     */
    public SynchList() {
        list = new LinkedList<Object>();
        lock = new Lock();
        listEmpty = new Condition(lock);
    }

    /**
     * Add the specified object to the end of the queue. If another thread is
     * waiting in <tt>removeFirst()</tt>, it is woken up.
     *
     * @param o the object to add. Must not be <tt>null</tt>.
     */
    public void add(Object o) {
        Lib.assertTrue(o != null);

        lock.acquire();
        list.add(o);
        listEmpty.wake();
        lock.release();
    }

    /**
     * Remove an object from the front of the queue, blocking until the queue
     * is non-empty if necessary.
     *
     * @return the element removed from the front of the queue.
     */
    public Object removeFirst() {
        Object o;

        lock.acquire();
        while (list.isEmpty())
            listEmpty.sleep();
        o = list.removeFirst();
        lock.release();

        return o;
    }


    /**
     * 移除并返回队列顶。如果队列为空，则返回 null（不阻塞）
     *
     * @return 从队列顶移除的元素。如果队列为空，则为 null
     */
    public Object removeFirstOrNull() {
        Object o = null;

        lock.acquire();
        if (!list.isEmpty()) {
            o = list.removeFirst();
        }
        lock.release();

        return o;
    }

    public boolean isEmpty() {
        boolean isEmpty;
        lock.acquire();
        isEmpty = list.isEmpty();
        lock.release();
        return isEmpty;
    }

    private static class PingTest implements Runnable {
        PingTest(SynchList ping, SynchList pong) {
            this.ping = ping;
            this.pong = pong;
        }

        public void run() {
            for (int i = 0; i < 10; i++)
                pong.add(ping.removeFirst());
        }

        private SynchList ping;
        private SynchList pong;
    }

    /**
     * Test that this module is working.
     */
    public static void selfTest() {
        SynchList ping = new SynchList();
        SynchList pong = new SynchList();

        new KThread(new PingTest(ping, pong)).setName("ping").fork();

        for (int i = 0; i < 10; i++) {
            Integer o = new Integer(i);
            ping.add(o);
            Lib.assertTrue(pong.removeFirst() == o);
        }
    }

    private LinkedList<Object> list;
    private Lock lock;
    private Condition listEmpty;
}

