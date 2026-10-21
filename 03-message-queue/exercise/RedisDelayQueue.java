import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * 关卡 3 · RedisDelayQueue：用 ConcurrentSkipListMap 模拟 Redis ZSET
 *
 * 为什么要换 ZSET？
 *   DelayQueue（关卡 2）有两宗罪：① JVM 重启全丢（数据在内存）② 单机独占（起两个 JVM 各玩各的）。
 *   真实生产用 Redis ZSET：score 排序 + ZPOPMIN 原子取到期 + 可选持久化。
 *   ConcurrentSkipListMap 的 API 和 ZSET 几乎一样——用它先把算法跑通，换 Redis 只是换 API 调用。
 *
 * 关键演示：两个 worker 线程抢同一条到期任务
 *   runBuggyWorkers：firstEntry() + remove()（两步非原子）→ 重复消费 ❌
 *   runSafeWorkers：pollFirstEntry()（一步原子，底层 CAS）→ 只有一人成功 ✅
 *
 * 运行：先跑 runSafeWorkers，观察每条订单只被一个 worker 处理；
 *      注释掉 runSafeWorkers、取消注释 runBuggyWorkers，能看到同一订单被两个 worker 都处理了
 */
public class RedisDelayQueue {

    public static void main(String[] args) throws InterruptedException {
        // ConcurrentSkipListMap：key 自动升序排列的线程安全 Map（底层是跳表）
        // key = Long（到期时间戳，毫秒）—— 早到期的时间戳小，自动排前面
        // value = String（订单号）
        ConcurrentSkipListMap<Long, String> zset = new ConcurrentSkipListMap<>();

        // 生产者：放 5 笔订单，延时 1s~5s，故意乱序放入（和关卡 2 一样）
        long now = System.currentTimeMillis();
        zset.put(now + 5000, "订单-E");
        zset.put(now + 3000, "订单-C");
        zset.put(now + 1000, "订单-A");
        zset.put(now + 4000, "订单-D");
        zset.put(now + 2000, "订单-B");
        System.out.println("生产者：放了 5 笔订单，zset 当前 key 顺序：" + zset.keySet());

        // 方法一（有 bug）：先注释掉，跑 runSafeWorkers 看正确效果
        // runBuggyWorkers(zset);

        // 方法二（正确）：取消注释 runBuggyWorkers，注释掉这行看错误效果
        runSafeWorkers(zset);

        Thread.sleep(10000);
    }

    /**
     * 有 bug 的 worker——先读 firstEntry()，再 remove()，两步不是原子的
     * 两个 worker 同时进来，会同时拿到同一条到期消息 → 重复消费
     *
     * 为什么非原子？firstEntry() 和 remove() 是两行独立的 Java 代码，
     * 编译成几十条独立的 CPU 指令。操作系统可以在这中间切换 CPU 给另一个线程。
     */
    static void runBuggyWorkers(ConcurrentSkipListMap<Long, String> zset) {
        for (int i = 1; i <= 2; i++) {
            final int id = i;
            new Thread(() -> {
                while (true) {
                    // ① 读队首
                    Map.Entry<Long, String> entry = zset.firstEntry();
                    if (entry == null) break;
                    long now = System.currentTimeMillis();
                    if (entry.getKey() > now) {
                        // 还没到期，等 100ms 再看
                        try { Thread.sleep(100); } catch (InterruptedException e) {}
                        continue;
                    }
                    // ② 删除（注意：① 和 ② 之间可能被另一个 worker 抢先了！）
                    zset.remove(entry.getKey());
                    System.out.println("worker" + id + " 处理了 " + entry.getValue());
                }
            }, "worker-" + i).start();
        }
    }

    /**
     * 正确的 worker——用 pollFirstEntry() 原子地"取 + 删"
     *
     * 底层是 CPU 硬件级 CAS 指令（cmpxchg）：
     *   CAS(内存位置V, 预期值A, 新值B)：
     *     1. 读 V 的值
     *     2. 如果 V == A → 把 V 改成 B，返回"成功"
     *     3. 如果 V ≠ A → 什么都不做，返回"失败"
     *   这三步在 CPU 里是一条不可分割的指令——执行时锁住内存总线，
     *   其他 CPU 核心此刻不能读也不能写这块内存。
     *
     * CAS 的通用模式："只有第一个抢到的人干活，其他人看到被抢了立刻放弃"
     * 不管抢的是"延时任务"还是"扩容权"，思路完全一样。
     */
    static void runSafeWorkers(ConcurrentSkipListMap<Long, String> zset) {
        for (int i = 1; i <= 2; i++) {
            final int id = i;
            new Thread(() -> {
                while (true) {
                    // 原子操作：要么拿到 entry 并从 map 里删掉，要么返回 null
                    // 和 firstEntry()+remove() 的区别：中间没有空隙，两个 worker 不可能同时拿到同一条
                    Map.Entry<Long, String> entry = zset.pollFirstEntry();
                    if (entry == null) break;
                    long now = System.currentTimeMillis();
                    if (entry.getKey() > now) {
                        // 还没到期，放回去，等 100ms 再看
                        zset.put(entry.getKey(), entry.getValue());
                        try { Thread.sleep(100); } catch (InterruptedException e) {}
                        continue;
                    }
                    System.out.println("worker" + id + " 处理了 " + entry.getValue());
                }
            }, "worker-" + i).start();
        }
    }
}
