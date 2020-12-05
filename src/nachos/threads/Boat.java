package nachos.threads;

import nachos.ag.BoatGrader;

public class Boat {
    static BoatGrader bg;

    // 岛上的人
    static int oahuChildrenCount;
    static int oahuAdultsCount;
//    static int molokaiChildrenCount;
//    static int molokaiAdultsCount;

    // 岛上的登船机会锁及条件变量
    static Lock oahuLock;
    static Lock molokaiLock;
    static Condition2 oahuCondition;
    static Condition2 molokaiCondition;

    // 船上的人
    static int boatChildrenCount;
    static int boatAdultsCount;

    // 船锁及条件变量
    static Lock boatLock;
    static Condition2 boatCondition;

    // 船的位置
    // 1 - oahu
    // 2 - molokai
    // other - unknown or running
    static int boatPosition;

    // 阻止坐船
    static boolean prevent;

    // 主线程等待锁及条件变量
    static Lock mainThreadLock;
    static Condition2 mainThreadCondition;

    public static void selfTest() {
        BoatGrader b = new BoatGrader();

        System.out.println("\n ***Testing Boats with only 2 children***");
        begin(0, 2, b);

        System.out.println("\n ***Testing Boats with 2 children, 1 adult***");
        begin(1, 2, b);

        System.out.println("\n ***Testing Boats with 3 children, 3 adults***");
        begin(3, 3, b);
    }

    public static void begin(int adults, int children, BoatGrader b) {
        // Store the externally generated autograder in a class
        // variable to be accessible by children.
        bg = b;

        // Instantiate global variables here

        oahuChildrenCount = children;
        oahuAdultsCount = adults;
//        molokaiChildrenCount = 0;
//        molokaiAdultsCount = 0;

        oahuLock = new Lock();
        molokaiLock = new Lock();
        oahuCondition = new Condition2(oahuLock);
        molokaiCondition = new Condition2(molokaiLock);

        boatChildrenCount = 0;
        boatAdultsCount = 0;

        boatLock = new Lock();
        boatCondition = new Condition2(boatLock);

        boatPosition = 0;
        prevent = false;

        mainThreadLock = new Lock();
        mainThreadCondition = new Condition2(mainThreadLock);

        // Create threads here. See section 3.4 of the Nachos for Java
        // Walkthrough linked from the projects page.

        mainThreadLock.acquire();

        Runnable adult = new Runnable() {
            @Override
            public void run() {
                AdultItinerary();
            }
        };

        Runnable child = new Runnable() {
            @Override
            public void run() {
                ChildItinerary();
            }
        };

        for (int i = 0; i < adults; i++) {
            new KThread(adult).fork();
        }

        for (int i = 0; i < children; i++) {
            new KThread(child).fork();
        }

        boatPosition = 1;

        mainThreadCondition.sleep();
        mainThreadLock.release();

        System.out.println("Process End");

    }

    static void AdultItinerary() {
        bg.initializeAdult(); //Required for autograder interface. Must be the first thing called.
        //DO NOT PUT ANYTHING ABOVE THIS LINE.

	/* This is where you should put your solutions. Make calls
	   to the BoatGrader to show that it is synchronized. For
	   example:
	       bg.AdultRowToMolokai();
	   indicates that an adult has rowed the boat across to Molokai
	*/

        // 上船机会
        oahuLock.acquire();
        // 条件：
        // 1 - 船必须在 oahu 岛
        // 2 - 船上必须没有人
        // 3 - 岛上必须只有一个小孩
        // 不满足则等待
        while (boatPosition != 1 || boatAdultsCount + boatChildrenCount != 0 || oahuChildrenCount != 1) {
            oahuCondition.sleep();
        }
        // 上船
        boatAdultsCount += 1;
        oahuAdultsCount -= 1;
        // 交出上船机会锁
        oahuLock.release();
        // 去 molokai
        bg.AdultRowToMolokai();
        boatPosition = 2;
        // 下船
        boatAdultsCount -= 1;
        // 通知 molokai 的人船来了
        molokaiLock.acquire();
        molokaiCondition.wakeAll();
        molokaiLock.release();
    }

    static void ChildItinerary() {
        bg.initializeChild(); //Required for autograder interface. Must be the first thing called.
        //DO NOT PUT ANYTHING ABOVE THIS LINE.

        boolean allLast = false;
        while (true) {
            // 获取 oahu 的上船机会
            oahuLock.acquire();
            // 条件：
            // 1 - 船在 oahu
            // 2 - 船上的小孩和岛上的小孩至少有两个（因为自己就是小孩，所以已经保证至少有一个）
            while (boatPosition != 1 || oahuChildrenCount + boatChildrenCount == 1) {
                oahuCondition.sleep();
            }
            // 锁船
            boatLock.acquire();
            // 上船
            boatChildrenCount += 1;
            oahuChildrenCount -= 1;
            // 岛上已经没有人了，最后一趟
            if (oahuAdultsCount + oahuChildrenCount == 0) {
                allLast = true;
            }
            // 自己是第二个上的，负责划船
            if (boatChildrenCount == 2) {
                // 释放上船机会
                oahuLock.release();
                // 唤醒船上的人
                boatCondition.wakeAll();
                // 划船
                bg.ChildRowToMolokai();
                boatPosition = 2;
                // 下船
                boatChildrenCount -= 1;
                // 最后一趟的话，唤醒主线程（运行完毕）
                // 同时阻止再回去
                if (allLast) {
                    prevent = true;
                    mainThreadLock.acquire();
                    mainThreadCondition.wakeAll();
                    mainThreadLock.release();
                    boatLock.release();
                    return;
                }
                // 释放船锁
                boatLock.release();
                // 通知 molokai 的人船来了
                molokaiLock.acquire();
                molokaiCondition.wakeAll();
                molokaiLock.release();
            } else {
                // 自己是第一个上船的，乘客身份
                // 释放上船机会
                oahuLock.release();
                // 等待第二个人上船
                // 并划船到目的地
                while (boatPosition != 2) {
                    boatCondition.sleep();
                }
                // 到站
                bg.ChildRideToMolokai();
                // 下船
                boatChildrenCount -= 1;
                // 释放船锁
                boatLock.release();
            }

            // 获取 molokai 的上船机会
            molokaiLock.acquire();
            // 条件：
            // 1 - 没有结束
            // 2 - 船在 molokai
            // 3 - 船上没人（已经全下船了，并且没人上船）
            while (prevent || boatPosition != 2 || boatChildrenCount != 0) {
                molokaiCondition.sleep();
            }
            // 上船
            boatChildrenCount += 1;
            // 释放上船机会
            molokaiLock.release();
            // 回到 oahu
            bg.ChildRowToOahu();
            boatPosition = 1;
            // 下船
            boatChildrenCount -= 1;
            oahuChildrenCount += 1;
            // 通知 oahu 的人船到了
            oahuLock.acquire();
            oahuCondition.wakeAll();
            oahuLock.release();
        }
    }

    static void SampleItinerary() {
        // Please note that this isn't a valid solution (you can't fit
        // all of them on the boat). Please also note that you may not
        // have a single thread calculate a solution and then just play
        // it back at the autograder -- you will be caught.
        System.out.println("\n ***Everyone piles on the boat and goes to Molokai***");
        bg.AdultRowToMolokai();
        bg.ChildRideToMolokai();
        bg.AdultRideToMolokai();
        bg.ChildRideToMolokai();
    }

}
