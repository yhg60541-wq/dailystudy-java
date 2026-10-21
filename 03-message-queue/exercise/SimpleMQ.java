import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 关卡 1 · SimpleMQ：最小的生产者-消费者
 *
 * 用 JDK 自带的 BlockingQueue 写一个能跑的消息队列。
 * 两个生产者-消费者模型演示三个关键点：
 *   1. put/take 是阻塞的——队列满了生产者等，队列空了消费者等
 *   2. 两个消费者抢同一条消息——不会重复消费（ArrayBlockingQueue 内部有锁）
 *   3. poll(超时) 让消费者"等不到就退出"，不会卡死
 *
 * 运行：javac SimpleMQ.java && java SimpleMQ
 * 观察：两个消费者的输出交错出现，但每条"订单-N"只被打印一次
 */
public class SimpleMQ {

    public static void main(String[] args) throws InterruptedException {
        // 容量 10 的 ArrayBlockingQueue：这个"最小 MQ"的全部家当
        // BlockingQueue = 线程安全队列，自带"满了等空位、空了等消息"的阻塞逻辑
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(10);

        // 生产者线程：循环 10 次，每次往队列里放一条"订单取消消息"
        // put() 可能抛 InterruptedException（队列满了生产者在等，等的过程中被打断）
        // Java 强制要求处理这个异常，所以必须包 try-catch
        new Thread(() -> {
            try {
                for (int i = 0; i < 10; i++) {
                    queue.put("订单-" + i);
                }
                System.out.println("生产了 10 条消息");
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();

        // 消费者线程 1
        // 用 poll(超时) 而不是 take()：take() 队列空了会永远阻塞，poll 等 1 秒没消息就返回 null
        // 返回 null 说明生产者已经放完、队列也空了 → break 退出，不会卡死
        new Thread(() -> {
            try {
                while (true) {
                    String msg = queue.poll(1, TimeUnit.SECONDS);
                    if (msg == null) break;
                    System.out.println("消费者1 消费了 " + msg);
                }
                System.out.println("消费者1 退出");
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();

        // 消费者线程 2：和消费者 1 抢同一批消息
        // ArrayBlockingQueue 内部的锁保证了：每条消息只能被一个线程取走
        new Thread(() -> {
            try {
                while (true) {
                    String msg = queue.poll(1, TimeUnit.SECONDS);
                    if (msg == null) break;
                    System.out.println("消费者2 消费了 " + msg);
                }
                System.out.println("消费者2 退出");
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();

        // 给生产者+消费者 3 秒时间跑完
        // main 线程如果先结束，JVM 会跟着退出，其他线程就被掐了
        Thread.sleep(3000);
    }
}
