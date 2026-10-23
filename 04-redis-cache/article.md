# 第 4 篇 · Redis 缓存与分布式锁：从 CAS 到 Lua

几乎所有电商系统都有"秒杀"这个场景：10000 人同时抢 100 件商品。如果每个请求都直接扣 MySQL 库存——MySQL 能扛住吗？

答案是扛不住。MySQL 单表每秒最多处理 500~1000 次写操作，10000 人同时来，数据库连接池瞬间占满，后面的请求全报超时。

**解法就是两层**：
1. 库存先存 Redis（超快，10000 次请求秒处理完）
2. 扣库存用分布式锁（保证同一时刻只有一个人能扣，不会超卖）

这篇文章就从这个场景出发，一关一关写：从最简单的原子 setnx，到带超时的分布式锁，再到 Lua 原子解锁，最后过一遍缓存世界的四大常识。

---

## 关卡 1 · 最小原子命令：SETNX

### 问题：怎么让 10 个线程里只有 1 个成功？

先不考虑 Redis，用纯 JDK 写一个最小版：

```java
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class SimpleRedis {
    private final ConcurrentMap<String, Object> store = new ConcurrentHashMap<>();

    // SETNX = SET if Not eXists
    // key 不存在才 set，返回 true；key 已存在返回 false
    public boolean setnx(String key, Object value) {
        return store.putIfAbsent(key, value) == null;
    }
}
```

`putIfAbsent(key, value)` 是 JDK 版的 SETNX——底层也是 CAS（Compare-And-Swap）。CPU 硬件指令保证"读 + 判断 + 写"一步完成，中间没有空隙。

### 跑一下：10 个线程抢"库存锁"

```java
SimpleRedis redis = new SimpleRedis();
for (int i = 0; i < 10; i++) {
    final String myId = "thread-" + i;
    new Thread(() -> {
        boolean got = redis.setnx("库存锁", myId);
        if (got) System.out.println("✅ " + myId + " 抢到了");
        else     System.out.println("❌ " + myId + " 没抢到");
    }).start();
}
```

**输出**（不管跑多少次，抢到的永远是 1 个）：
```
✅ thread-3 抢到了
❌ thread-0 没抢到
❌ thread-1 没抢到
...（其他 8 个）
```

### 为什么能保证只有一个成功？

分两种情况：
1. **桶是空的** → 用 CAS 抢桶头（`cmpxchg(table[5], null, newNode)`），成功就放进去，失败直接返回旧值
2. **桶不空** → 锁住桶头（synchronized），循环检查有没有这个 key，没有才加到链表尾

两个路径都是原子的——10 个线程同时进来，CAS 只有一个能成功，其他 9 个看到已经被抢了立刻放弃。

---

## 关卡 2 · 加超时 + 持有者校验：像样的分布式锁

关卡 1 有两个致命问题：
- **没有超时**：拿到锁的进程挂了 → 锁永远不释放 → 所有人都拿不到（死锁）
- **没有持有者校验**：A 拿到锁，B 不小心把它删了 → A 还以为自己持有锁 → 并发执行

### 先加超时（expireMs）

用 `ScheduledExecutorService` 定时删除 key：

```java
private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

public boolean tryLock(String lockKey, String requestId, long expireMs) {
    long expireAtMs = System.currentTimeMillis() + expireMs;
    LockRecord record = new LockRecord(requestId, expireAtMs);

    boolean success = locks.putIfAbsent(lockKey, record) == null;

    if (success) {
        // 抢到锁 → expireMs 毫秒后自动删除（防止进程挂了锁不释放）
        scheduler.schedule(() -> {
            locks.remove(lockKey, record);  // 双参数版：只有 value 还是原来的 record 才删
        }, expireMs, TimeUnit.MILLISECONDS);
    }
    return success;
}
```

**关键点**：`locks.remove(lockKey, record)` 不是单参数的 `remove(lockKey)`——双参数版底层也是 CAS，只有 value 还等于原来的 record 才删。万一锁过期后被别人拿走了，定时器还去删就误删了别人的锁。

### 再加持有者校验（unlock）

```java
public void unlock(String lockKey, String requestId) {
    LockRecord record = locks.get(lockKey);          // 第①步：读
    if (record != null && record.requestId.equals(requestId)) {  // 第②步：判断
        locks.remove(lockKey);                        // 第③步：删
    }
}
```

### 故意留一个 bug：①②③ 不是原子的！

```
时刻     流程
──────────────────────────────────────
T1      A（持有者）调 unlock
        → get("库存锁") → 发现 requestId="A" → 准备 remove
T2      🔴 锁到期了！定时器自动删了！
T3      B 抢到了同一个锁！
T4      A 继续执行 remove("库存锁") → 🔴 把 B 的锁删了！
        → B 以为自己持有锁，实际 key 已经没了
        → 第三个线程 C 也能抢进来 → 并发执行！
```

这就是关卡 3 要修的。

---

## 关卡 3 · 原子解锁 + Lua 思想

关卡 2 的 bug 根因：`get` + `remove` 是两步操作，中间有空隙（操作系统可以切换 CPU 给另一个线程）。

### 修复：双参数 remove 底层也是 CAS

```java
public boolean safeUnlock(String key, String requestId) {
    return store.remove(key, requestId);  // 双参数版！
}
```

`ConcurrentHashMap.remove(key, expectedValue)` 底层是 CAS：
- key 存在且 value.equals(expectedValue) → 删除，返回 true
- key 不存在 或 value 不匹配 → 不删除，返回 false

**一步完成，没有空隙！**

### 跑一下验证

```
步骤 1：A 抢到锁
  → A 抢到锁？true（预期 true）
  → 锁当前持有者：A

步骤 2：模拟锁过期（手动删除 key），然后 B 抢锁
  → B 抢到锁？true（预期 true）
  → 锁当前持有者：B

步骤 3：A 执行 safeUnlock(锁, A)——但锁现在是 B 持有！
  → A 删掉了锁？false（预期 false）✅
  → 锁当前持有者：B（没被误删！）

步骤 4：B 执行 safeUnlock(锁, B)
  → B 删掉了锁？true（预期 true）✅
```

### 真实 Redis 对应什么命令？

Redis 里叫 **Lua 脚本**：

```lua
if redis.call("get", KEYS[1]) == ARGV[1] then
    return redis.call("del", KEYS[1])
else
    return 0
end
```

### 为什么 Lua 能保证原子？

Redis 是**单线程模型**（6.0 之前执行命令的是单线程）——所有命令按顺序执行。Lua 脚本也是单线程执行，**整个脚本执行完才处理下一条命令**，中间不会被打断。

6.0 之后 Redis 引入了多线程做网络 IO（接收连接、读写 socket），但**执行命令还是单线程**——所以 Lua 脚本依然天然原子。

### 整条知识链（和前两篇串联）

```
第 2 篇：CAS = Compare-And-Swap = CPU 硬件级原子指令
  用法：抢 sizeCtl 扩容权（只有一个线程能 CAS 成功改成 -1）

第 3 篇：pollFirstEntry() 原子抢占延时任务
  用法：两个 worker 抢同一个到期消息
  本质：底层也是 CAS

第 4 篇：SETNX 抢锁 + remove(key, expectedValue) 原子解锁
  用法：分布式锁 + Lua 脚本
  本质：还是 CAS！双参数 remove 底层就是 CAS 比较 value 再删

三篇贯穿同一个思想：
  "抢资源的人只有第一个 CAS 成功，其他人看到已经被抢了立刻放弃"
```

---

## 关卡 4 · 理论关：缓存世界的四大常识

### 常识一：缓存穿透 / 击穿 / 雪崩

三个问题都是"缓存失效后大量请求打向 MySQL"，但场景不同、解法不同。

#### 穿透：查根本不存在的数据

**场景**：用户恶意构造一堆不存在的 user_id 来查，比如 `GET /user/-1`、`GET /user/-999`。Redis 里永远没有，MySQL 里也永远没有——每次都打 MySQL。

**类比**：小偷天天来敲你家空房子的门，每次都确认"没人"后走了，但还是天天来。

**解法**：布隆过滤器。Java 里 `com.google.common.hash.BloomFilter` 可以直接用。把所有合法 key 加进去，过滤器说"肯定不存在"就直接返回空，不查 Redis 和 MySQL。布隆过滤器有极小概率误判（说"可能存在"但实际不存在），但**绝不会漏判**（说"肯定不存在"就真不存在）。

#### 击穿：某个热点 key 刚好过期

**场景**：秒杀库存 key="库存锁" 过期了——瞬间几千个请求同时打 MySQL，MySQL 被冲垮。

**类比**：你家突然被一群人同时闯入（门刚开锁，大家一起冲）。

**解法**：**互斥锁**——就是关卡 2-3 写的分布式锁！

```
❌ 没有互斥锁时：
  10000 个请求 → Redis 都没找到 → 同时查 MySQL → MySQL 挂了

✅ 关卡 2-3 写法：
  请求 1 → SETNX("库存锁:update", "req-1") → 成功！→ 查 MySQL → 写缓存 → DEL
  请求 2 → SETNX → 失败 → sleep 50ms → 重新读缓存 → 已经有了！返回
  请求 3 → SETNX → 失败 → sleep → 读缓存 → 有了！
  ... 9999 个请求同理 → 只有 1 个查 MySQL，其他全读缓存
```

#### 雪崩：大量 key 同时过期

**场景**：设置 30min TTL，结果 30min 后同一秒有 10000 个 key 同时过期——MySQL 瞬间被冲垮。

**类比**：整栋楼同时着火，消防只能救一个小区。

**解法**：**过期时间加随机值**。比如 `30min + random(0, 300s)`，让 key 分散过期，不要集中在同一秒。

### 常识二：Redisson 的分布式锁比手写的好在哪？

我们手写的（关卡 1-3）能跑，但只有最基本的原子语义。Redisson 是生产级组件，多了三个核心能力：

#### 能力 1：看门狗续期

我们手写的锁超时时间固定（比如 3 秒）。如果业务代码突然慢了（比如查 MySQL 卡了 1 秒），总共 4 秒——锁在第 3 秒自动过期了，另一个线程抢进来，两个线程同时扣库存 → 超卖。

**Redisson 的看门狗**默认每 10 秒续期一次（续到 30 秒）。只要持有者还活着（业务线程还没结束），看门狗就一直续期，不会误删。业务代码结束后 `unlock()`，看门狗自动停。

#### 能力 2：可重入锁

同一线程拿到锁后，再进来同一个方法（递归或方法调方法），会被自己挡在外面——死锁。

Redisson 用计数器实现可重入：同一线程第 1 次 lock → 计数器=1；第 2 次 lock → 计数器=2（放行，不用重新 CAS）；第 1 次 unlock → 计数器=1（不释放）；第 2 次 unlock → 计数器=0（真正释放）。

#### 能力 3：读写锁

1000 个请求读库存（`GET stock:123`），不应该让它们排队等写锁。Redisson 的 `RReadWriteLock`：读可以并发（1000 个一起读），写互斥（同一时刻只有一个能写）。

### 常识三：Redis 怎么保证高可用？

Redis 挂了怎么办？三级演进：

#### 第 1 级：主从复制（基础）

- 主节点（master）负责写 → 同步给所有从节点（slave）
- 从节点负责读（读写分离，降低主节点压力）
- 问题：主挂了怎么办？从节点不会自动上位，得手动切换

#### 第 2 级：哨兵（Sentinel，自动故障转移）

- 多台哨兵（通常 3 台）监控主节点健康
- 哨兵定期 ping 主节点，如果连续 3 次没响应（主观下线 + 客观下线），自动选一个从节点上位成新主
- 客户端连哨兵，哨兵告诉你主是谁（自动切换，对业务透明）

#### 第 3 级：集群（Cluster，分片 + 高可用）

- 数据分片到 16384 个 slot，分布在多个主节点
- 每个主节点又有从节点（保证每个分片高可用）
- 存不下了？加节点自动 rehash 分片
- 主挂了？从自动上位
- 哨兵能管 1 个主 + 多个从；集群能管 N 个主 + 每个主 N 个从

### 常识四：Redis 和 MySQL 双写一致性

缓存和数据库数据不一致怎么办？

#### 三种方案对比

| 方案 | 流程 | 一致性 | 复杂度 |
|---|---|---|---|
| 先写 MySQL，再删缓存 | ① UPDATE MySQL ② DEL cache | 最终一致（实践够用） | 低 |
| 先删缓存，再写 MySQL | ① DEL cache ② UPDATE MySQL | 概率更高不一致 | 低 |
| 订阅 MySQL binlog 同步缓存 | MySQL 写 binlog → Canal 订阅 → 更新缓存 | 强一致 | 高 |

#### 最常用方案：先写 MySQL，再删缓存

极小概率不一致的场景：

```
时刻     读请求 A                  写请求 B
─────────────────────────────────────────────────────
T1      读 cache → 没有 → 查 MySQL → 拿到旧值
                                    还没写回 cache...
T2                               UPDATE MySQL（改了新值）
T3                               DEL cache（删了缓存）
T4      把旧值写回 cache           ← 旧值又写进去了！
结果：cache 是旧值，MySQL 是新值 → 不一致
```

但这个"空隙"极短（MySQL 写 + DEL cache 通常 < 10ms），而且只有读请求**刚好在这个空隙里**才会触发。实际生产里概率极低。如果真要彻底解决，加一个**延时双删**（写完 MySQL 后先 DEL，睡 50ms 再 DEL 一次）基本就兜住了。

---

## 四个方案对比表（和前三篇的关卡结构对齐）

| 方案 | 解决什么 | 能不能多实例 | 原子性 | 复杂度 |
|---|---|---|---|---|
| **关卡 1 SimpleRedis** | 原子 setnx | ❌ 单机 | ✅ CAS | 极低 |
| **关卡 2 RedisLock** | + 超时释放 + 持有者校验 | ❌ 单机 | ⚠️ unlock 非原子 | 低 |
| **关卡 3 RedisLuaLock** | + 原子解锁 | ❌ 单机 | ✅ 全链路原子 | 低 |
| **真实 Redis** | + 多实例共享 + 持久化 + 高可用 | ✅ 主从/哨兵/集群 | ✅ Lua 脚本 | 中 |
| **Redisson** | + 看门狗续期 + 可重入 + 读写锁 | ✅ | ✅ | 中 |

---

## 回扣项目

### 车型导购系统的 PurchaseService

`PurchaseService` 注释里写着"Redis 预扣 + Lua + MQ 异步落库"：
- Redis 预扣：秒杀场景库存先扣 Redis，关卡 1-3 的分布式锁保证不会超卖
- Lua：关卡 3 的原子解锁，保证不会误删别人的锁
- MQ 异步落库：扣完 Redis 库存后发消息到 MQ，后台消费者慢慢落 MySQL——不阻塞秒杀请求

### 招标助手项目的实际用法

- **三级缓存**：文档解析结果 → 字段抽取片段 → 对比结果，30min~1h TTL
- **ZSET 延时队列**：score 存到期时间戳，ZRANGEBYSCORE 取到期待办，ZPOPMIN 原子抢占
- **Set 防重复推送**：SADD 返回 1 表示第一次加，没发过就发送提醒
- **降级**：所有 Redis 操作包 try-catch，失败降级到数据库查询，主流程不受影响

---

## 复盘：做完这篇要能不看代码讲出这五条

1. **为什么 SETNX 是原子的？** → ConcurrentHashMap.putIfAbsent 底层 CAS，空桶时 CAS 抢桶头，非空桶时 synchronized 锁桶头再双重检查。
2. **分布式锁需要哪三步？** → 加锁（SETNX + 超时）+ 干活 + 解锁（判断持有者再删，必须原子，用双参数 remove 或 Lua）。
3. **关卡 2 和关卡 3 的 bug 和修复？** → bug 是 unlock 的 get+remove 两步中间有空隙，A 可能误删 B 的锁；修复是 remove(key, expectedValue) 底层 CAS，一步完成。
4. **缓存三大问题怎么防？** → 穿透用布隆过滤器；击穿用互斥锁（就是关卡 2-3 写的）；雪崩用过期时间加随机值。
5. **整条知识链的 CAS 复用？** → 第 2 篇抢扩容权 → 第 3 篇抢延时任务 → 第 4 篇抢锁/原子解锁，全是"只有第一个 CAS 成功，其他人看到被抢了立刻放弃"。

---

## 速查：面试高频问题标准回答

### Q1：怎么实现分布式锁？

> 三要素：① SETNX 原子抢锁（key=锁名，value=唯一 requestId）；② 超时自动释放（SETNX 时带 expire 参数，防止进程挂了不释放）；③ 解锁时先判断持有者再删除（用双参数 DEL key value 或 Lua 脚本，必须原子，防止 A 误删 B 的锁）。生产级用 Redisson，多了看门狗续期、可重入锁、读写锁。

### Q2：缓存穿透、击穿、雪崩分别是什么？

> - **穿透**：查根本不存在的数据，Redis 和 MySQL 都没有，每次都打 MySQL → 布隆过滤器兜底。
> - **击穿**：热点 key 刚好过期，瞬间几千个请求同时打 MySQL → 互斥锁（SETNX 抢"只允许一个人查 MySQL"）。
> - **雪崩**：大量 key 同时过期，MySQL 被瞬间冲垮 → 过期时间加随机值。

### Q3：Redis 怎么保证高可用？

> 三级演进：主从复制（读写分离）→ 哨兵（自动故障转移，主挂了从自动上位）→ 集群（数据分片到 16384 个 slot，每个分片又有哨兵）。

### Q4：Redis 和 MySQL 数据不一致怎么办？

> 最常用：先写 MySQL 再删缓存（最终一致，空隙极短，实践够用）。如果真要彻底解决，延时双删（写完 MySQL 先 DEL，睡 50ms 再 DEL 一次）或者订阅 MySQL binlog 同步缓存。

### Q5：为什么手写的分布式锁不如 Redisson？

> 手写只有最基本的原子语义。Redisson 多了三个核心能力：① 看门狗续期（默认每 10 秒续到 30 秒，业务没跑完不会误删）；② 可重入锁（同一线程可以多次 lock，内部用计数器）；③ 读写锁（读并发写互斥，提升读性能）。
