import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 关卡 3 · RedisLuaLock：分布式锁的原子解锁 + Lua 思想
 *
 * 关卡 2 的 unlock 有 bug：get 检查持有者 → 没问题 → delete
 * 这三步是**非原子**的——中间可能被别人抢了锁，你把别人的锁删了。
 *
 * 真实 Redis 的解法：用 Lua 脚本。Redis 是单线程的，Lua 脚本也是单线程执行——
 *   整个 Lua 脚本执行完才处理下一条命令，中间不会被打断 → 天然原子。
 *
 * 我们用 ConcurrentHashMap.remove(key, value) 的语义模拟 Lua：
 *   remove(key, expectedValue) 只有当 key 对应的 value 等于 expectedValue 时才删除
 *   这是一个原子操作（底层 CAS），不会出现关卡 2 的 bug。
 *
 * 运行：javac RedisLuaLock.java && java RedisLuaLock
 */
public class RedisLuaLock {

    private final ConcurrentMap<String, String> store = new ConcurrentHashMap<>();

    public boolean setnx(String key, String requestId) {
        return store.putIfAbsent(key, requestId) == null;
    }

    /**
     * 模拟 Redis + Lua 原子解锁
     * ConcurrentHashMap.remove(key, value) 底层是 CAS，一步完成：
     *   - key 存在且 value.equals(expected) → 删除，返回 true
     *   - key 不存在 或 value 不匹配 → 不删除，返回 false
     *
     * 对应真实 Redis 的 Lua 脚本（单线程执行，天然原子）：
     *   if redis.call("get", KEYS[1]) == ARGV[1] then
     *       return redis.call("del", KEYS[1])
     *   else
     *       return 0
     *   end
     */
    public boolean safeUnlock(String key, String requestId) {
        return store.remove(key, requestId);
    }

    // =============== 测试：模拟"解锁误删"的 bug → 对比安全版 ===============
    public static void main(String[] args) throws InterruptedException {
        RedisLuaLock redis = new RedisLuaLock();
        final String lockKey = "库存锁";

        // 场景：A 持有锁 → A 超时了（锁自动过期）→ B 抢到锁
        //      → A 还没结束，执行 unlock(lockKey, A)
        //      → 关卡 2 非原子写法：get(A)==A → 间隙！锁已被 B 抢 → remove 误删 B 的锁
        //      → 关卡 3 CAS 写法：remove(key, A) → 发现 value 是 B 不是 A → 不删 → 安全 ✅

        System.out.println("=== 测试 safeUnlock：A 误删 B 的锁会不会发生 ===");

        // 步骤 1：A 抢到锁
        System.out.println("步骤 1：A 尝试加锁");
        boolean aGot = redis.setnx(lockKey, "A");
        System.out.println("A 抢到锁？" + aGot + "（预期 true）");
        System.out.println("锁当前持有者：" + redis.store.get(lockKey));

        // 步骤 2：锁过期了（模拟：直接手动把 key 删了，然后 B 抢）
        System.out.println("\n步骤 2：模拟锁过期（手动删除 key），然后 B 抢锁");
        redis.store.remove(lockKey);
        boolean bGot = redis.setnx(lockKey, "B");
        System.out.println("B 抢到锁？" + bGot + "（预期 true）");
        System.out.println("锁当前持有者：" + redis.store.get(lockKey));

        // 步骤 3：A 还没结束，执行 safeUnlock（传自己的 requestId="A"）
        System.out.println("\n步骤 3：A 执行 safeUnlock(锁, A)——但锁现在是 B 持有！");
        boolean aDeleted = redis.safeUnlock(lockKey, "A");
        System.out.println("A 删掉了锁？" + aDeleted + "（预期 false）");
        System.out.println("锁当前持有者：" + redis.store.get(lockKey));

        // 步骤 4：B 执行 safeUnlock（传自己的 requestId="B"）
        System.out.println("\n步骤 4：B 执行 safeUnlock(锁, B)");
        boolean bDeleted = redis.safeUnlock(lockKey, "B");
        System.out.println("B 删掉了锁？" + bDeleted + "（预期 true）");
        System.out.println("锁当前持有者：" + redis.store.get(lockKey));

        // 步骤 5：safeUnlock 一把梭的 10 线程并发测试
        System.out.println("\n=== 扩展测试：10 线程用 safeUnlock 竞争 ===");
        for (int i = 0; i < 10; i++) {
            final String myId = "thread-" + i;
            new Thread(() -> {
                boolean got = redis.setnx(lockKey, myId);
                if (got) {
                    System.out.println("✅ " + myId + " 抢到了锁");
                    try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                    redis.safeUnlock(lockKey, myId);
                    System.out.println("  🔓 " + myId + " 安全释放了锁");
                }
            }).start();
        }
        Thread.sleep(2000);

        System.out.println("\n=== 总结 ===");
        System.out.println("关卡 2 bug：get→判断→remove 两步，中间有空隙，A 可能误删 B 的锁");
        System.out.println("关卡 3 修复：remove(key, expectedValue) 底层 CAS，一步原子，安全 ✅");
        System.out.println("真实 Redis：用 Lua 脚本，Redis 单线程执行，整个脚本天然原子");
    }
}
