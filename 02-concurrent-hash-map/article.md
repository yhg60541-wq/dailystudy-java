# ConcurrentHashMap：从 2816 个 key 丢失到 0 丢失，我花了 4 步

你好，我是那个手写 HashMap 的新手。

上一篇文章里，我手写了一个 `SimpleHashMap`——单线程用起来没问题，但跑并发测试的时候丢了 2816 个 key。这篇文章，我带你看看我怎么一层一层把它变成线程安全的 ConcurrentHashMap。

**完整代码在 GitHub：** [dailystudy-java](https://github.com/yhg60541-wq/dailystudy-java)

---

## 一、总起：这篇文章讲什么？

一句话：**这是一个"发现问题 → 解决问题 → 发现新问题 → 再解决"的完整故事线。**

我一共走了 5 个关卡，每个关卡都有真实的代码和跑出来的数据：

| 关卡        | 方案                                  | 10 线程丢 key     | 耗时  | 核心收获           |
| ----------- | ------------------------------------- | ----------------- | ----- | ------------------ |
| **0** | 无锁 HashMap                          | 14 ~ 2816（随机） | 极快  | 理解为什么不安全   |
| **1** | synchronized 整表锁                   | 0                 | ~48ms | 最保底的方案       |
| **2** | 桶锁 + AtomicInteger                  | 2 ~ 6             | 快    | 两把锁不协调的坑   |
| **3** | sizeCtl CAS + AtomicInteger           | 0                 | ~18ms | 面试必背的 sizeCtl |
| **4** | CounterCell + CAS + synchronized 保底 | 0                 | ~45ms | 三件套全对齐       |

- 我遇到了什么问题
- 我是怎么解决的
- 跑出来什么真实数据
- 最后卡在了哪里，为什么

---

## 二、背景：先跑起来看看会发生什么

### 2.1 关卡 0：无锁 HashMap 到底有多不安全？

上一篇我写的 `SimpleHashMap`（用链表处理碰撞），单线程 put 1000 个 key 一点问题没有。

那 10 个线程同时 put 呢？写个测试：

```java
// ConcurrencyTest.java
SimpleHashMap<Integer, String> map = new SimpleHashMap<>();

// 10 个线程，每个线程 put 1000 个不同的 key
Thread[] threads = new Thread[10];
for (int t = 0; t < 10; t++) {
    final int base = t * 1000;
    threads[t] = new Thread(() -> {
        for (int i = 0; i < 1000; i++) {
            map.put(base + i, "v" + (base + i));
        }
    });
}
```

**跑出来的结果（我自己跑了 3 次）：**

```
期望=10000, 实际=3476, 差=6524, 丢key=2816
期望=10000, 实际=9286, 差=714,  丢key=14
期望=10000, 实际=4320, 差=5680, 丢key=2134
```

丢 key 的幅度**完全随机**——有时候只丢 14 个，有时候丢 2816 个。

### 2.2 为什么会丢？2 个根因

**根因 1：resize 撞车**

当 size 超过阈值（容量 × 0.75）时，HashMap 会把数组扩容一倍。

两个线程同时发现 size 到阈值了，都开始 resize：

- 线程 A 把节点搬到新 table 的桶 3
- 线程 B 也把节点搬到新 table 的桶 3，直接覆盖 A 搬的节点
- 结果：A 搬的节点没了 → 丢

**JDK 7 更可怕：** 用头插法，resize 过程中链表可能成环 → 死循环（经典生产事故！）。
JDK 8 改成尾插法不成环了，但还是会丢数据。

**根因 2：同一个桶写冲突**

两个线程同时 put 到同一个桶：

- 线程 A：头插节点 X → `bucket[i] = X`，X.next = head
- 线程 B：头插节点 Y → `bucket[i] = Y`，Y.next = head
- 结果：bucket[i] 现在是 Y，X 去哪了？**丢了**！

---

## 三、关卡 1：最蠢但有效的方案——整表加锁

### 3.1 怎么做？

把整个 `put()` 方法用 `synchronized` 包住：

```java
public synchronized V put(K key, V value) {
    // 和 SimpleHashMap 的 put 一模一样，只是多了 synchronized
}
```

`synchronized(this)` 的意思：同一时刻只能有一个线程执行这个方法。其他线程想进来，必须等当前线程执行完。

### 3.2 跑出来的结果

```
期望=10000, 实际=10000, 差=0, 丢key=0, 耗时=48ms
```

**0 丢失！** 而且代码改动就一个关键字，太简单了。

### 3.3 但有个大问题：排队效应

`synchronized(this)` 把整个 put 串行化了。10 个线程想 put，必须排队：

- 线程 A 进去，耗时 ~4ms
- 线程 B 等 A 出来再进去
- 线程 C 等 B 出来再进去
- ...

**所有线程排队等着拿这一把锁。** 并发度从 10 变成了 1，性能暴跌。

### 3.4 面试考点

> **面试官：ConcurrentHashMap 和 Hashtable 有什么区别？**
>
> 答：Hashtable 也是 `synchronized` 整个方法（关卡 1 的方案），粒度太粗。
> ConcurrentHashMap 在 JDK 8 用的是 `synchronized(桶头节点)` 桶锁，只有写同一个桶的线程才竞争锁。
> 粒度从"整张表"降到了"一个桶"，并发度高得多。

---

## 四、关卡 2：想做桶锁——踩了两把锁的坑

### 4.1 桶锁的思路

既然整表锁太粗，能不能改成"每个桶一把锁"？

- 桶是空的：`synchronized(table)` 先占住这个桶的位置，再塞节点
- 桶不是空的：`synchronized(head)` 锁住这个桶的头节点，再遍历链表

### 4.2 跑出来的结果

```
期望=10000, 实际=9997, 差=3, 丢key=6
期望=10000, 实际=9998, 差=2, 丢key=2
```

**又开始丢了！** 而且丢的数量不多，每次差 2~6 个——这说明不是 resize 这种大问题，而是偶发的小冲突。

### 4.3 根因：两把锁互不协调

桶锁方案里有两把不同的锁：

| 场景       | 锁对象                  |
| ---------- | ----------------------- |
| 桶是空的   | `synchronized(table)` |
| 桶不是空的 | `synchronized(head)`  |

这两把锁**完全独立**！

假设：桶 3 此刻是空的（head = null）。

- 线程 A 想 put 到桶 3，head 是 null，所以它拿 `synchronized(table)`
- 与此同时，另一个线程 B 已经先 put 了一个节点到桶 3
- 现在桶 3 不是空的了，线程 C 想 put 到桶 3，它拿的是 `synchronized(head)`
- **线程 A 拿 table 锁，线程 C 拿 head 锁，两把锁互不干扰！**
- A 和 C 同时写桶 3 → 丢 key

### 4.4 临时方案

桶锁的坑根因是：**空桶和非空桶用的是两把不同的锁，互不协调。**

**临时回退**：先用 `synchronized(this)` 整表锁保底，保证正确性。等搞懂 sizeCtl 这个核心机制，再回头搞真正的桶锁。

### 4.5 面试考点

> **面试官：JDK 8 ConcurrentHashMap 的 putVal 空桶时怎么处理？**
>
> 答：**不用锁！用 CAS 直接塞！**
> `if ((head = tabAt(tab, i)) == null) casTabAt(tab, i, null, newNode);`
> 这叫"无锁最快路径"——空桶是最简单的情况，直接 CAS 塞进去，连锁都不用碰。
> 但这个 CAS 能安全，是因为配合了 ForwardingNode + helpTransfer + transferIndex 三件套，
> 简化版做不到，所以用 synchronized 保底。

---

## 五、关卡 3：sizeCtl CAS 防重复扩容 + AtomicInteger

### 5.1 两个关键新增

**新增 1：`sizeCtl`**

```java
private volatile int sizeCtl;
// 什么是 volatile？保证一个线程改了 sizeCtl，所有其他线程立刻能看到新值
```

`sizeCtl` 有两个含义：

- **正数**：阈值（size 超过它就触发扩容）。比如初始容量 16 × 0.75 = 12
- **-1**：正在扩容中

**新增 2：`AtomicInteger size` 替代普通 int**

普通 `int` 的 `size++` 不是原子的（先读、再加、再写回，3 步），多线程下会丢。
`AtomicInteger.incrementAndGet()` 是原子的（内部用 CAS），多线程安全。

### 5.2 tryResize()：CAS 抢扩容权

```java
private void tryResize() {
    if (sizeCtl <= 0) return;          // 已经在扩容了，跳过
    if (size <= sizeCtl) return;       // size 还没到阈值，跳过

    // ⭐ CAS：只有一个线程能把 sizeCtl 从阈值改成 -1
    if (!SIZE_CTL_CAS.compareAndSet(this, sizeCtl, -1)) return;

    doResize();  // CAS 成功的那个线程 → 独占执行
}
```

**CAS（Compare-And-Swap）是什么？**

简单说：**CPU 硬件级原子指令，要么做完要么不做，中间不会被打断。**

```
CAS(内存位置 V, 预期值 A, 新值 B)：
  1. 读 V 的值
  2. 如果 V == A（还是我预期的值）→ 把 V 改成 B，返回 true
  3. 如果 V ≠ A（被别人抢先改了）→ 返回 false，啥也不动
```

**10 个线程同时 tryResize：**

- 线程 A：`CAS(sizeCtl, 12, -1)` → ✅ 成功（V 确实是 12）→ 独占 doResize
- 线程 B：`CAS(sizeCtl, 12, -1)` → ❌ 失败（V 已经被 A 改成 -1 了）→ 直接 return
- 线程 C、D、E...：和 B 一样，全部 CAS 失败，全部跳过

**为什么不用 `synchronized` 包住 tryResize？**

`synchronized` 会让**所有** tryResize 串行化——包括那些 size 根本没到阈值的线程也要排队等锁。CAS 是无锁的，只有 size 刚到阈值的那批线程才竞争一次 CAS，其他人 size 不够直接 return，根本不碰 CAS。

### 5.3 跑出来的结果

```
期望=10000, 实际=10000, 差=0, 丢key=0, 耗时=17ms
期望=10000, 实际=10000, 差=0, 丢key=0, 耗时=18ms
期望=10000, 实际=10000, 差=0, 丢key=0, 耗时=19ms
```

**连续 3 次全 0 丢失！** 而且耗时比关卡 1 的 48ms 快了一倍多。

### 5.4 面试考点

> **面试官：sizeCtl 的值分别代表什么？**
>
> 答：正数是阈值（触发扩容），负数表示正在扩容。
> 简化版是 -1（单线程独占），JDK 8 是 -(N+1) 表示有 N 个线程协作搬运。

> **面试官：为什么用 CAS sizeCtl 防重复扩容，不用 synchronized？**
>
> 答：synchronized 会让所有 tryResize 串行化——包括 size 没到阈值的也要排队。
> CAS 是无锁的：只有 size 刚到阈值的那批线程才竞争一次 CAS，其他人 size 不够直接 return，
> 根本不碰 CAS，性能极高。

---

## 六、关卡 4：CounterCell 分散计数 

### 6.1 AtomicInteger 还有什么问题？

关卡 3 用了 `AtomicInteger size`。但 100 个线程同时 `incrementAndGet()`，会怎么样？

`AtomicInteger.incrementAndGet()` 内部也是 CAS：

```
while (true) {
    int A = size.get();          // 读当前值（预期值）
    int B = A + 1;               // 新值
    if (size.compareAndSet(A, B)) return B;  // CAS
    // CAS 失败 → 被别人抢先改了 → 自旋重来
}
```

100 个线程抢一个变量：

- 线程 A CAS 成功 → 改了
- 线程 B CAS 失败 → 自旋
- 线程 C CAS 失败 → 自旋
- ...
- 线程 B 下一轮又 CAS → 还是失败（A 又改了）→ 再自旋
- 平均下来，**每个线程要自旋 ~50 次才能成功一次 CAS！**

### 6.2 CounterCell 分散计数（LongAdder 风格）

**思路**：别让所有线程抢一个变量，分散到 N 个 cell。

```java
private long baseCount = 0L;                  // 低并发兜底
private volatile AtomicLongArray counterCells; // 高并发分散到 N 个 cell

private void addCount(long delta) {
    if (counterCells == null) {
        // 低并发：直接 CAS 改 baseCount（几乎一定成功）
        if (BASE_COUNT_CAS.compareAndSet(this, baseCount, baseCount + delta)) return;
        // CAS 失败 → 并发上来了，初始化 counterCells
        counterCells = new AtomicLongArray(availableProcessors * 2);
    }
    // 高并发：各线程碰不同 cell（靠线程 local 的 probe 值 hash）
    int probe = PROBE.get();
    counterCells.addAndGet(probe % counterCells.length(), delta);
}

// size() 时：base + 所有 cell 求和
private long sumCount() {
    long sum = baseCount;
    if (counterCells != null) for (int i = 0; i < counterCells.length(); i++) sum += counterCells.get(i);
    return sum;
}
```

100 线程分散到 16 个 cell → 平均每个 cell 只有 ~6 个线程碰 → 几乎不自旋！

### 6.3 跑出来的结果（10 次连续）

```
[1] 期望=10000, 实际=10000, 差=0, 丢key=0, 耗时=49ms
[2] 期望=10000, 实际=10000, 差=0, 丢key=0, 耗时=125ms   ← 偶尔波动
[3] 期望=10000, 实际=10000, 差=0, 丢key=0, 耗时=49ms
...
[10] 期望=10000, 实际=10000, 差=0, 丢key=0, 耗时=48ms  ← 稳定 10 次全 0 丢失
```

### 6.4 面试考点

> **面试官：高并发下 AtomicInteger 自旋久怎么办？**
>
> 答：用 LongAdder 风格分散计数。不是让所有线程抢一个 AtomicInteger，
> 而是分散到多个 CounterCell，每个线程只碰自己那个 cell。
> size() 时 base + 所有 cell 求和即可。JDK 8 ConcurrentHashMap 就是这么做的。

> **面试官：sizeCtl 从 12 改成 -1 再改成 19，会不会有 ABA 问题？**
>
> 答：不会。ABA 问题是 CAS 看到的值先被改成 B 又改回 A，以为没人动过。
> sizeCtl 只会单调递增：12 → -1 → 19 → -1 → 38... 永远不会复用到之前的值。

---

## 七、易错点总结

| 坑                               | 为什么会踩                                                  | 正确做法                                                                                      |
| -------------------------------- | ----------------------------------------------------------- | --------------------------------------------------------------------------------------------- |
| **两把锁不协调**           | 空桶`synchronized(table)` + 非空桶 `synchronized(head)` | 空桶用 TabAt CAS 无锁塞，不需要锁                                                             |
| **doResize 和 put 并发**   | 各自用不同锁，互不协调                                      | JDK 8 用 sizeCtl + ForwardingNode + helpTransfer 三件套配合；简化版用 synchronized 整表锁保底 |
| **普通 int size++ 不安全** | i++ 是读-改-写 3 步                                         | 用 AtomicInteger 或 CounterCell 分散计数                                                      |
| **resize 后下标错位**      | 用 resize 前 table.length 算新下标                          | 读完 sizeCtl 再读 table，保证 table.length 最新                                               |

---

## 八、项目对照

 `SessionStore`（token→userId）和 `RecognitionStore`（sessionId→label）后续要加并发，**不要直接用 ConcurrentHashMap**——数据量不大（课程级 demo），`synchronized(HashMap)` 完全够用，还不用理解 sizeCtl、ForwardingNode 这些复杂机制。

真正需要 ConcurrentHashMap 这种级别的优化，是**数据量上 10 万 + 并发 1000 QPS**以上的场景。

---

## 九、完整代码 + 跑法

完整代码在 GitHub：[dailystudy-java](https://github.com/yhg60541-wq/dailystudy-java)

目录结构：

```
study-blog-java/
└── 02-concurrent-hash-map/
    ├── SimpleConcurrentHashMap.java   ← 关卡 4 最终版（带详细中文注释）
    └── ConcurrencyTest.java           ← 关卡 0 测试（SimpleHashMap 并发丢 key 实验）
```

跑一下关卡 4：

```bash
javac SimpleConcurrentHashMap.java
java SimpleConcurrentHashMap
# 输出：期望=10000, 实际=10000, 差=0, 丢key=0, 耗时≈45ms
```

---

## 十、结语：这个"造轮子"教会了我什么？

上一篇写 HashMap，我觉得"哈希表不就是个数组加链表吗"。
这篇写 ConcurrentHashMap，我才发现——**让并发安全这件事，比让单线程正确难太多了。**

关卡 2 踩的那两把锁的坑，让我真正理解了：

> **JDK 8 的 synchronized(head) 桶锁能生效，不是因为 synchronized 本身有多牛，
> 而是因为配合了 ForwardingNode + helpTransfer + transferIndex 这整套精密协作协议。**

关卡 3 的 sizeCtl CAS，让我第一次感受到了**硬件级 CAS 的威力**——
10 个线程竞争，只有一个干了一次 CPU CAS，其他 9 个干了一次 CAS 发现失败就走了，
没有锁、没有排队、没有线程挂起。

关卡 4 的 CounterCell，让我记住了：

> **解决并发问题不一定要加锁，很多时候"分散"比"集中抢锁"更高效。**

保持学习，保持造轮子。
