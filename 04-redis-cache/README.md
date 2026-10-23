# 第 4 篇 · Redis 缓存与分布式锁（陪练任务书）

> 规则一句话：**代码你来敲，我给任务、给 review、给追问，但不直接给答案。**

## 为什么是 Redis

车型识别项目里有一段真实代码：`PurchaseService` 注释写着"Redis 预扣 + Lua + MQ 异步落库"。
这篇文章就从"分布式锁"这个核心需求出发，一关一关升级：

主线场景：**秒杀场景，10 个线程同时扣库存——怎么保证不会超卖？**

## 目录说明

| 目录 | 是什么 |
| --- | --- |
| `exercise/` | 练习区，代码全部自己写 |

（四关全部做完后，再给参考实现和范文对照。）

## 每一关的五步流程

1. 看任务 → 2. 自己写代码 → 3. 自己 `javac` + `java` 跑出结果 → 4. 把输出贴出来，并用一句话解释"为什么是这个结果" → 5. 老师 review，抛 1~2 个追问。

**卡住了怎么办**：先说"我试了什么、卡在哪一步"，再要思考方向（一级提示 → 二级提示），最后才看代码片段——只看那几行。

## 关卡清单

### 关卡 0 · 热身：Redis 是什么？（不写代码）
- **任务**：查项目里的 `pom.xml` 有没有 Redis 依赖；回到第 3 篇文章里 Redis 那一节，快速过一遍：
  1. Redis 和 MySQL 的区别（数据存哪、速度、持久化）
  2. SETNX 命令是什么意思？（key 不存在才 set，返回 1；key 存在就不 set，返回 0）
- **验收**：用自己的话解释"为什么秒杀扣库存要用 SETNX"。
- **追问**：如果 Redis 挂了怎么办？（缓存穿透 vs 数据库直查）

### 关卡 1 · 手写一个最小版 Redis：SETNX（SimpleRedis.java）
- **任务**：用 JDK 纯内存模拟 Redis 的 SETNX 命令——一个线程安全的 `ConcurrentHashMap<String, Object>`，配合一个 `AtomicBoolean` 或直接用 `ConcurrentHashMap.putIfAbsent`。
  - TODO 1-1：写 `boolean setnx(String key, Object value)` —— key 不存在时设值返回 true，key 已存在返回 false
  - TODO 1-2：起 10 个线程同时 `setnx("库存锁", "xxx")` —— 观察只有 1 个线程返回 true，其他 9 个返回 false
- **验收**：10 个线程竞争，正好 1 个成功、9 个失败。
- **追问**：`ConcurrentHashMap.putIfAbsent` 和 CAS 是什么关系？（和第 2 篇 `compareAndSet` 同一个思路）

### 关卡 2 · 用 SETNX 实现分布式锁（RedisLock.java）
- **任务**：基于关卡 1 的 `setnx`，写一个最简单的分布式锁：
  - TODO 2-1：写 `boolean tryLock(String lockKey, String requestId, long expireMs)` —— 加锁成功返回 true，带超时（expireMs 毫秒后自动释放，防止死锁）
  - TODO 2-2：写 `void unlock(String lockKey, String requestId)` —— 只有锁的持有者才能解锁（防止 A 解锁了 B 的锁）
  - TODO 2-3：跑 10 个线程同时 tryLock，用 requestId 标识自己，打印谁抢到了锁
- **验收**：只有 1 个线程抢到锁，并且 unlock 不会互相影响。
- **追问**：① 超时释放怎么实现？（Timer 定时删除 key）② unlock 为什么要传 requestId？（如果不加，会有什么 bug？）

### 关卡 3 · 分布式锁的两个 bug + Redis + Lua（RedisLuaLock.java）
- **任务**：关卡 2 的 unlock 有 bug——"先 get 再 delete"两步不是原子的。换成模拟 Lua 脚本：
  - TODO 3-1：写 `boolean safeUnlock(String lockKey, String requestId)` —— "判断 value 是不是 requestId → 是才 delete" 必须原子
  - TODO 3-2：用关卡 2 的 Redis 模拟类加一个 `boolean compareAndDelete(key, expectValue)` 方法（类似 CAS）
  - TODO 3-3：跑 10 个线程竞争锁 + 释放锁，观察不会出现"两个线程都成功 unlock"
- **验收**：并发释放锁时不会重复删除。
- **追问**：① 真实 Redis 里这对应一条什么命令？（`EVAL` 执行 Lua 脚本）② 为什么要 Lua？（因为 Redis 单线程，Lua 脚本也是单线程执行，天然原子）③ Lua 的原子性和 Redis 单线程是什么关系？

### 关卡 4 · 理论关：缓存三大问题（不写代码）
- **任务**：四个问题，可查资料，但必须用自己的话写：
  1. 什么是缓存穿透、击穿、雪崩？分别怎么防？（关卡 3 的"互斥锁防击穿"就是我们写过的）
  2. Redisson 的分布式锁比我们手写的好在哪？（看门狗续期、可重入锁、读写锁）
  3. Redis 怎么保证高可用？（主从复制 → 哨兵 → 集群）
  4. Redis 和 MySQL 的双写一致性怎么保证？（先写 MySQL 再删缓存？还是反过来？）
- **验收**：每题 3-5 句话讲清，不背书。
- **追问**：面试官问"怎么实现分布式锁"，你怎么答？（用你关卡 1-3 的真实代码说）

## 进度自检（自己填）

| 关卡 | 状态 | 我跑出的关键输出 | 追问答上了吗 | 卡住/想不通的点 |
| --- | --- | --- | --- | --- |
| 0 | | | | |
| 1 | | | | |
| 2 | | | | |
| 3 | | | | |
| 4 | | | | |

## 复盘：做完之后要能不看代码讲出这五条

1. Redis 和 MySQL 的核心区别是什么？（数据存哪、速度、持久化）
2. SETNX 命令做什么的？为什么秒杀扣库存用它？
3. 手写分布式锁需要哪两步？加锁（SETNX + 超时）+ 解锁（判断持有者再删，必须原子）
4. Lua 脚本为什么能保证原子性？（Redis 单线程，Lua 脚本整个执行完才处理下一条命令）
5. 缓存三大问题（穿透/击穿/雪崩）分别是什么？各怎么防？
