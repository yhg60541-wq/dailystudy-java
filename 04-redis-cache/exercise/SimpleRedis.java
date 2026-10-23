import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 关卡 1 · SimpleRedis：用纯 JDK 模拟 Redis 的 SETNX 命令
 *
 * 为什么要"模拟"？因为我们不需要真的装一个 Redis 服务器。
 * Redis 的核心就是：一个线程安全的 key-value 存储 + 几条原子命令。
 * ConcurrentHashMap 已经线程安全了，差的就是"不存在才 set"这个原子语义。
 *
 * SETNX = SET if Not eXists：
 *   key 不存在 → 设值成功，返回 1（true）
 *   key 已存在 → 什么都不做，返回 0（false）
 *
 * 运行：javac SimpleRedis.java && java SimpleRedis
 */
public class SimpleRedis {

    // 模拟 Redis 的内存存储：key → value
    private final ConcurrentMap<String, Object> store = new ConcurrentHashMap<>();

    /**
     * SETNX：只有当 key 不存在时才设值
     * ConcurrentHashMap.putIfAbsent 就是 JDK 版的 SETNX！
     *   - 如果 key 不存在：放进去并返回 null → 我们 return true
     *   - 如果 key 已存在：返回旧值（不是 null）→ 我们 return false
     *
     * 这一步底层也是 CAS（和第 2 篇 ConcurrentHashMap 的 put 同一个思路）
     */
    public boolean setnx(String key, Object value) {
        // TODO 1-1：写这行代码
        return store.putIfAbsent(key, value) == null;
    }

    public Object get(String key) {
        return store.get(key);
    }

    public void del(String key) {
        store.remove(key);
    }

    // =============== 测试：10 个线程同时抢锁 ===============
        // =============== 测试：10 个线程同时抢锁 ===============
    public static void main(String[] args) throws InterruptedException {
        SimpleRedis redis = new SimpleRedis();
        final String lockKey = "库存锁";
        final java.util.concurrent.atomic.AtomicInteger successCount 
            = new java.util.concurrent.atomic.AtomicInteger(0);

        System.out.println("准备起 10 个线程同时抢锁...");

        // 10 个线程并发抢锁
        Thread[] threads = new Thread[10];
        for (int i = 0; i < 10; i++) {
            final String myId = "thread-" + i;
            threads[i] = new Thread(() -> {
                boolean got = redis.setnx(lockKey, myId);
                if (got) {
                    int count = successCount.incrementAndGet();
                    System.out.println("✅ " + myId + " 抢到了锁！（第 " + count + " 个）");
                } else {
                    System.out.println("❌ " + myId + " 没抢到锁");
                }
            });
            threads[i].start();
        }

        // 等所有线程跑完
        for (Thread t : threads) t.join();

        System.out.println("\n=== 结果 ===");
        System.out.println("抢到锁的线程数：" + successCount.get() + "（预期 = 1）");
        System.out.println("锁的持有者：" + redis.get(lockKey));
    }
}