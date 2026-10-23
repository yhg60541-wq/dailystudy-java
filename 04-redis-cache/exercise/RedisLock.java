import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 关卡 2 · RedisLock：用 SETNX 实现分布式锁
 *
 * 分布式锁的核心需求：多个机器上的多个进程，要保证同一段代码同一时刻只有一个能执行。
 * SETNX 天然适合这个场景——key 就是"锁"，谁 set 成功了谁拿到锁。
 *
 * 两个关键点：
 *   1. 加锁时要带超时（expireMs）——防止拿锁的进程挂了不释放，锁永远不释放（死锁）
 *   2. 解锁时要校验持有者——防止 A 解锁了 B 的锁
 *
 * 运行：javac RedisLock.java && java RedisLock
 */
public class RedisLock {

    // 用 ConcurrentHashMap + Timer 模拟 Redis 的 SETNX + EXPIRE
    private final ConcurrentMap<String, LockRecord> locks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    // 锁记录：持有者 requestId + 过期时间戳（毫秒）
    private static class LockRecord {
        final String requestId;
        final long expireAtMs;

        LockRecord(String requestId, long expireAtMs) {
            this.requestId = requestId;
            this.expireAtMs = expireAtMs;
        }
    }

    /**
     * 尝试加锁
     * @param lockKey    锁的名字（比如 "库存锁"）
     * @param requestId  持有者的唯一标识（比如 "thread-3"）
     * @param expireMs   锁的超时时间（毫秒）——超过这个时间自动释放
     * @return true=成功拿到锁，false=锁被别人持有
     *
     * TODO 2-1：用 ConcurrentHashMap.putIfAbsent 实现
     *   1. 计算 expireAtMs = now + expireMs
     *   2. new LockRecord(requestId, expireAtMs)
     *   3. putIfAbsent(lockKey, record) == null → 成功
     *   4. 成功后用 scheduler.schedule 定时删除 key
     */
    public boolean tryLock(String lockKey, String requestId, long expireMs) {
        long expireAtMs = System.currentTimeMillis() + expireMs;
        LockRecord record = new LockRecord(requestId, expireAtMs);

        boolean success = locks.putIfAbsent(lockKey, record) == null;

        if (success) {
            scheduler.schedule(() -> {
                locks.remove(lockKey, record);
            }, expireMs, TimeUnit.MILLISECONDS);
        }
        return success;
    }

    /**
     * 释放锁（只有持有者自己能释放）
     * 注意：get + remove 是两步操作！并发下会有 bug → 关卡 3 用 CAS 修
     */
    public void unlock(String lockKey, String requestId) {
        LockRecord record = locks.get(lockKey);
        if (record != null && record.requestId.equals(requestId)) {
            locks.remove(lockKey);  // 🔴 故意非原子！留到关卡 3 修
            System.out.println("  🔓 " + requestId + " 释放了锁");
        } else {
            System.out.println("  ⚠️ " + requestId + " 尝试释放但不是持有者（或锁已过期）");
        }
    }

    // =============== 测试：10 个线程竞争锁 ===============
    public static void main(String[] args) throws InterruptedException {
        RedisLock lock = new RedisLock();
        final String lockKey = "库存锁";
        final java.util.concurrent.atomic.AtomicInteger successCount
            = new java.util.concurrent.atomic.AtomicInteger(0);

        System.out.println("=== 10 个线程同时抢锁，3 秒后自动超时 ===");

        Thread[] threads = new Thread[10];
        for (int i = 0; i < 10; i++) {
            final String myId = "thread-" + i;
            threads[i] = new Thread(() -> {
                // 抢锁
                boolean got = lock.tryLock(lockKey, myId, 3000);  // 3秒超时
                if (got) {
                    int count = successCount.incrementAndGet();
                    System.out.println("✅ " + myId + " 抢到锁！（第 " + count + " 个）");
                    // 拿到锁后假装干活 500ms，然后主动释放
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                    lock.unlock(lockKey, myId);
                } else {
                    System.out.println("❌ " + myId + " 没抢到锁");
                }
            });
            threads[i].start();
        }

        for (Thread t : threads) t.join();

        System.out.println("\n=== 结果 ===");
        System.out.println("抢到锁的线程数：" + successCount.get() + "（预期 = 1）");
        System.out.println("锁当前持有者：" + (lock.locks.get(lockKey) != null
            ? lock.locks.get(lockKey).requestId + "（剩余 "
                + (lock.locks.get(lockKey).expireAtMs - System.currentTimeMillis()) + "ms 过期）"
            : "已释放"));

        // 关掉定时器线程，程序才能正常退出
        lock.scheduler.shutdown();
    }
}
