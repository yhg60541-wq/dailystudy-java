import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

/**
 * 关卡 2 · DelayMQ：让订单"等几秒再取消"
 *
 * BlockingQueue 拿到消息立刻就消费了，做不了"延时触发"。
 * DelayQueue 是 JDK 自带的延时队列：消息放进去，只有到了指定时间，消费者才能 take() 到。
 *
 * 放 5 笔订单，延时分别是 1s / 2s / 3s / 4s / 5s（用秒模拟分钟，别真等 15 分钟）。
 * 观察：哪怕生产者乱序放入，消费者 take() 出来的顺序也是按"到期时间"排好的。
 */
public class DelayMQ {

    public static void main(String[] args) throws InterruptedException {
        // 创建 DelayQueue，泛型是我们自己写的 DelayedOrder
        DelayQueue<DelayedOrder> queue = new DelayQueue<>();

        // 生产者：放 5 笔延时订单（DelayQueue 无界，put() 不抛异常，所以不用 try-catch）
        new Thread(() -> {
            // 故意乱序放！先放 5s 的，再放 1s 的——看消费者出来的顺序对不对
            queue.put(new DelayedOrder("订单-E", 5000));  // 5秒
            queue.put(new DelayedOrder("订单-C", 3000));  // 3秒
            queue.put(new DelayedOrder("订单-A", 1000));  // 1秒  ← 最早到期
            queue.put(new DelayedOrder("订单-D", 4000));  // 4秒
            queue.put(new DelayedOrder("订单-B", 2000));  // 2秒
            System.out.println("生产者：放了 5 笔延时订单（故意乱序放的）");
        }).start();

        // 消费者：poll() 带超时，等不到消息自动退出（和关卡 1 的 SimpleMQ 同样思路）
        new Thread(() -> {
            try {
                while (true) {
                    // DelayQueue.poll(2, TimeUnit.SECONDS)：最多等 2 秒
                    // 队列空且没有新消息 → 返回 null → break 退出
                    // 有到期消息 → 返回消息 → 处理
                    DelayedOrder order = queue.poll(2, TimeUnit.SECONDS);
                    if (order == null) break;  // 2 秒没消息了，说明处理完了，退出
                    System.out.println("消费者：" + order.getOrderId() + " 到期取消 ✅");
                }
                System.out.println("消费者：队列空了，退出 ✅");
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();

        Thread.sleep(10000);  // 留足时间让消费者处理完 5 秒延时
    }

    // =============== 延时订单类：实现 Delayed 接口 ===============
    public static class DelayedOrder implements Delayed {
        private final String orderId;
        private final long expireTimeMs;  // 到期的绝对时间戳（毫秒）

        // 构造方法：传订单号 + 延时多少毫秒
        public DelayedOrder(String orderId, long delayMs) {
            this.orderId = orderId;
            // 到期时间 = 现在 + 延时毫秒数
            this.expireTimeMs = System.currentTimeMillis() + delayMs;
        }

        // ① getDelay：距离到期还有多久
        //    返回值 > 0  → 还没到期，take() 会卡着等
        //    返回值 <= 0 → 时间到了，可以被 take() 出来了
        @Override
        public long getDelay(TimeUnit unit) {
            long remaining = expireTimeMs - System.currentTimeMillis();
            return unit.convert(remaining, TimeUnit.MILLISECONDS);
        }

        // ② compareTo：告诉 DelayQueue "谁到期早排前面"
        @Override
        public int compareTo(Delayed other) {
            DelayedOrder o = (DelayedOrder) other;
            return Long.compare(this.expireTimeMs, o.expireTimeMs);
        }

        public String getOrderId() { return orderId; }

        @Override
        public String toString() {
            return orderId + "(还剩" + getDelay(TimeUnit.SECONDS) + "秒)";
        }
    }
}
