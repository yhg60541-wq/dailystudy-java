# 从零手写一个哈希表

> 第 1 篇。目标：手写一个能跑的最小哈希表 → 逐个对齐 JDK `HashMap` 的设计决策 → 回头把自己项目里所有「哈希相关」的代码再看一遍，深入理解哈希表。

---

## 一、总起：为什么第一条就从哈希表开始

因为我两个项目里到处都是它，而我之前只是「会用」，说不出为什么：

- 登录态：`SessionStore` 用 `ConcurrentHashMap<String, Long>` 存 `token -> userId`；
- 会话上下文：`RecognitionStore` 用 `ConcurrentHashMap` 存 `sessionId -> 车型`；
- 缓存与队列：`TenderCache` 用 Redis 的 String key 做缓存，`ReminderDelayQueue` 用 Redis ZSET 做延时队列；
- 密码：`PasswordEncoder` 用 PBKDF2-SHA256 加盐哈希入库；
- 工具参数：一堆 `LinkedHashMap` 在传递工具调用参数。

「哈希」这个词在这五处含义并不完全相同（哈希表、哈希函数、有序哈希表）。

---

## 二、概念解释：哈希表只做三件事

```
key  →  [哈希函数]  →  hash  →  [下标映射]  →  桶位置  →  [冲突处理]  →  找到 value
```

### 1. 哈希函数：把任意 key 压成一个 int

要求「计算快」+「分布均匀」。Java 里默认是 `key.hashCode()`，但 JDK HashMap 还加了一层"扰动"（第四节讲）。

### 2. 下标映射：把 int 压到 `[0, 容量)` 范围

最直觉的写法是 `hash % n`。但 `%` 有两个坑：

- **负数坑**：Java 的 `%` 是取余，符号跟被除数走。`-1 % 16 = -1`，负数下标直接 `ArrayIndexOutOfBoundsException`。
- **位运算更快**：当容量 n 是 **2 的幂** 时，`hash % n` 等价于 `hash & (n - 1)`，而且 `&` 比 `%` 快得多。

这就是为什么 JDK 坚持容量为 2 的幂——它是整条设计链的根基。

### 3. 冲突处理：不同 key 算出同一个桶是必然的

**鸽巢原理**：你有 16 个桶，但装 17 个元素，**必然**有至少两个撞桶。主流两种解决思路：

| 方案               | 代表           | 怎么干                           |
| ------------------ | -------------- | -------------------------------- |
| **拉链法**   | JDK HashMap    | 同桶的不同 key 串成一条单链表    |
| **开放寻址** | ThreadLocalMap | 撞了就往旁边一格看，直到找到空位 |

拉链法更常用，这此实验用这个。

---

## 三、哈希原理：四个小实验

### 实验一：负数下标坑

```java
indexFor(-1, 16)  // 用 hash % n 的话，结果是 -1 → 数组炸了
indexFor(-1, 16)  // 用 hash & (n-1) 的话，-1 & 15 = 15 → 安全
```

`-1` 的补码是 32 位全 1（`1111...1111`），`& 15`（低 4 位全 1，高位全 0）把高位全抹成 0，结果必非负。**`&` 一箭双雕：既压范围，又消灭负数**。

**彩蛋**：别想用 `Math.abs` 救负数坑。`Math.abs(Integer.MIN_VALUE)` 吐出来的还是 `Integer.MIN_VALUE`——因为 int 范围不对称（-2³¹ ~ 2³¹-1），MIN_VALUE 的相反数在 int 里根本不存在。

### 实验二：为什么容量必须是 2 的幂

拿非 2 的幂的容量试试：模拟容量 10，`n-1 = 9 = 1001`：

```
h  &  9  (1001)  →  只有第 0 位和第 3 位能出结果
                  第 1、2 位永远是 0
                  → 桶 2、3、4、5、6、7 全空着！
                  → 10 个桶浪费了 6 个，数据挤在 4 个桶里
```

而 2 的幂减 1（比如 15 = `01111`）低位全 1，hash 的每一位信息都能留下来，**一个桶都不浪费**。

### 实验三：扰动函数到底解决了什么

两个 Integer key：`0` 和 `65536`。容量 16。

| key   | hashCode       | 不扰动 & 15 | 扰动 h^(h>>>16) & 15 |
| ----- | -------------- | ----------- | -------------------- |
| 0     | `0x00000000` | 0           | 0                    |
| 65536 | `0x00010000` | 0           | **1**          |

不扰动时两个 key 全撞桶——`65536` 的高 16 位那点"1"被 `& 15` 直接抹掉了。`h ^ (h>>>16)` 把高 16 位混进低 16 位，让高位也能影响下标。**"高位异或到低位"不是玄学，是让高位那点差异不被白丢**。

### 实验四：扩容时节点会去哪

默认容量 16，阈值 12（16 × 0.75）。连续 put 1..13：

```
put 1: size=1 ... put 12: size=12   ← 不触发，12 不 > 12
[resize] 16 -> 32，阈值 -> 24        ← 第 13 个 put 完 size=13 > 12，触发扩容
put 13: size=13
```

扩容后节点重新分布。JDK 8 有个精妙优化：**不用重新算 hash**，只看 `p.hash & oldCap`：

- 结果为 0 → 新下标 = 原下标（原地不动）
- 结果为 1 → 新下标 = 原下标 + oldCap（搬到"旧容量之后"的新桶）

为什么这招成立？因为 `& (newCap - 1)` 比 `& (oldCap - 1)` 正好多看了 1 位——那一位恰好是 oldCap 的值。翻倍扩容只为这招铺路。

---

## 四、学习正文：一个能跑的最小实现

完整代码在文末，核心是「数组 + 链表」。

### 4.1 骨架

```java
static class Node<K, V> {
    final int hash;   // 缓存 hash 值，比较时先用 int 比挡掉大部分
    final K key;
    V value;
    Node<K, V> next;  // 同桶内的下一个节点
}

private Node<K, V>[] table;   // 桶数组，长度永远是 2 的幂
private int size;             // 已存键值对数量
private int threshold;        // 扩容阈值 = capacity * loadFactor
```

### 4.2 哈希函数 + 下标映射

```java
// 扰动：让高位也参与下标计算
static int hash(Object key) {
    int h = key == null ? 0 : key.hashCode();
    return h ^ (h >>> 16);
}

// n 是 2 的幂时，& (n-1) 等价于 % n，但更快
private int indexFor(int hash, int n) {
    return hash & (n - 1);
}
```

### 4.3 put：先找同 key 覆盖，再头插，最后判扩容

```java
public V put(K key, V value) {
    int hash = hash(key);
    int i = indexFor(hash, table.length);

    // ① 遍历该桶链表，key 相等就覆盖
    for (Node<K, V> p = table[i]; p != null; p = p.next) {
        if (p.hash == hash && (p.key == key || (key != null && key.equals(p.key)))) {
            V old = p.value;
            p.value = value;
            return old;
        }
    }
    // ② 头插新节点（注意第四个参数是原来的头节点，它变成新节点的 next）
    table[i] = new Node<>(hash, key, value, table[i]);
    size++;
    // ③ 超阈值扩容（注意是 > 不是 >=）
    if (size > threshold) {
        resize();
    }
    return null;
}
```

两个小细节：

- `p.hash == hash && ... equals(...)`：先用 int 比较，99% 的不匹配节点在第一步就被过滤掉了，equals 可能跑几十行，int 比较是一条指令。
- **判断条件是 `>` 而不是 `>=`**：`threshold = 16 * 0.75 = 12`，所以第 12 个元素不会扩容，第 13 个才扩容。

### 4.4 resize：容量翻倍 + 重新分布

```java
private void resize() {
    Node<K, V>[] old = table;
    int newCap = old.length << 1;          // 容量翻倍（左移 1 位 = ×2）
    Node<K, V>[] nt = new Node[newCap];
    for (int j = 0; j < old.length; j++) {
        for (Node<K, V> p = old[j]; p != null; ) {
            Node<K, V> next = p.next;
            int i = indexFor(p.hash, newCap);   // 用新容量重算下标
            p.next = nt[i];
            nt[i] = p;
            p = next;
        }
    }
    table = nt;
    threshold = (int) (newCap * loadFactor);
}
```

扩容是**最贵的一步**：要把所有节点重新分布，时间复杂度 O(n)。所以「预估数据量时给个初始容量」，直接省掉若干次扩容。

### 4.5 跑一遍：四个实验的真实输出

**实验一：默认容量 16 的桶分布**

```
---- 实验一：默认容量 16 的桶分布 (size=6, cap=16) ----
  [1] score=4
  [5] level=5 -> city=3      ← 真实发生了冲突
  [8] name=1
  [11] id=0
  [14] age=2
```

`level` 和 `city` 撞在了 5 号桶，链表把它们串起来。**冲突是常态，不是 bug**——哈希表的设计目标只是把「链表的平均长度」压到接近 1。

**实验二：扩容边界**

```
实验二：插入 12 个元素后 size=12（还没到阈值 12？注意判断是 > 阈值）
[resize] 容量 16 -> 32，阈值 -> 24
```

**实验三：哈希冲突退化成 O(n)**

让所有 key 的 `hashCode()` 都返回 1：

```
---- 实验三：5 个 hashCode 全相同的 key (size=5, cap=16) ----
  [1] 5=5 -> 4=4 -> 3=3 -> 2=2 -> 1=1
bucketSize = 5 -> get 最坏要比较 5 次
```

这就是哈希碰撞攻击的原理（恶意构造一堆同 hash 的 key 打满一个桶）。JDK 8 的对策是：**桶内节点数达到 8 且容量 ≥ 64 时，把链表转成红黑树**，把最坏从 O(n) 降到 O(log n)。

**实验四：扰动函数的价值**

```
实验四：hashCode 高位不同、低位相同（容量 n = 16）
  key=0      hashCode=0x00000000  不扰动->桶0    扰动->桶0
  key=65536  hashCode=0x00010000  不扰动->桶0    扰动->桶1
```

---

## 五、易错点清单

整个学习过程中遇到的问题与小细节：

### 1. `hashCode` / `equals` 契约

`equals` 相等的对象 `hashCode` 必须相等。只重写一个，哈希表直接失效——`get` 查不到刚 `put` 进去的东西。我造的 BadKey 就故意先漏写 equals，get 永远返回 null，补上之后立刻能查到。

### 2. `Math.abs(Integer.MIN_VALUE)` 还是负数

int 范围不对称：-2³¹ ~ 2³¹-1。MIN_VALUE 的相反数在 int 里装不下，`Math.abs(MIN_VALUE)` 吐出来的还是 MIN_VALUE，照样是负数。哈希表下标映射**别用 abs，要用 `& (n-1)`**。

### 3. 可变对象当 key 会"跑掉"

`put` 之后修改对象里参与 `hashCode` 的字段，下标会变，再 `get` 就找不回来了。所以 key 应该用 String、`Integer` 这类不可变类型——我项目里全是 `String` key，是下意识做对了。

### 4. 扩容阈值是 `>` 不是 `>=`

`threshold = 16 * 0.75 = 12`，第 12 个元素不扩容，第 13 个才扩。差一个字符，扩容时机全错。

### 5. 链表头插的坑

头插法（我的简化版）在**并发扩容**时会把链表搞成环，`get` 时死循环、CPU 打满——这是著名的 "HashMap Infinite Loop"。JDK 8 改成尾插后不再成环，但**并发写依然会丢数据、size 不准**。并发场景必须用 `ConcurrentHashMap`。

---

## 六、个人总结：五句话讲清哈希表

1. **哈希表 = 数组 + 哈希函数 + 下标映射 + 冲突处理 + 动态扩容**，一句话概括：把 key 变成数组下标，直接 O(1) 读写。
2. **冲突必然存在**（鸽巢原理）。两种主流解法：拉链法（HashMap）、开放寻址（ThreadLocalMap）。
3. **容量取 2 的幂**：能用 `& (n-1)` 代替 `%`（快），且扩容时下标能"二分"重算（原地或 +oldCap）。
4. **扰动函数** `h^(h>>>16)` 解决了高位白算的问题，让高位差异也能影响下标。
5. **`hashCode` / `equals` 契约**：`equals` 相等的对象 `hashCode` 必须相等，违反了哈希表直接失效。

---

## 七、回到项目：5 处「哈希」都在干什么

### 1）`ConcurrentHashMap` 做登录态 —— 哈希表 + 并发

`token` 是 UUID 字符串，`hashCode()` 分布本身均匀，所以不需要再扰动。用 `ConcurrentHashMap` 而不是 `HashMap` 的原因很直接：Web 容器是多线程并发调用的。

> 分版本说：JDK 7 的 HashMap 扩容用头插法，并发扩容会让链表成环（死循环）；JDK 8 改成尾插后不再成环，但并发写依然会丢数据。所以结论是**并发场景别用 HashMap**，用 ConcurrentHashMap。

### 2）`ConcurrentHashMap` 存会话上下文

同一个知识点，换个场景再写一遍。key 选 `sessionId` 是因为它天然唯一、String 哈希均匀。

### 3）PBKDF2 加盐哈希 —— 这里的「哈希」不是哈希表

```java
byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        .generateSecret(new PBEKeySpec(password.toCharArray(), salt, 65536, 256))
        .getEncoded();
```

这是**密码学哈希函数**，跟哈希表没关系，但同属「哈希函数」这个大类，容易混：

|            | 哈希表用的哈希函数         | 密码存储用的哈希函数     |
| ---------- | -------------------------- | ------------------------ |
| 目标       | 快、分布均匀               | 慢（抗暴力破解）、不可逆 |
| 例子       | `hashCode()`、MurmurHash | PBKDF2、bcrypt、Argon2   |
| 冲突       | 允许，链表兜住             | 不可接受                 |
| 是否需要盐 | 不需要                     | 必须，防彩虹表           |

一句话：**哈希表的哈希要「快而均匀」，密码的哈希要「慢而单向」**。

### 4）Redis 缓存 —— 哈希映射下沉到中间件

`TenderCache` 把 `tender:doc:{id}:content` 当 key 存 Redis。Redis 内部就是用**哈希表**实现的，还支持**渐进式 rehash**（扩容时把迁移成本摊到每次操作上，避免单次 O(n) 卡住）。

### 5）Redis ZSET —— 哈希 + 跳表

`ReminderDelayQueue` 的延时队列用 ZSET 存 `member -> score`：哈希表负责 `member -> score` 的 O(1) 查找，跳表负责按 score 排序和范围查询。这也解释了为什么它比"给每个提醒建一个带 TTL 的 key"更适合做延时队列。

---

## 八、	Questions

**Q1：`hash % n` 和 `hash & (n-1)` 什么情况下等价？**
→ 当 n 是 2 的幂时完全等价。因为 `& (n-1)` 只保留低位，而 `% n` 对 2 的幂取模也等价于保留低位。`&` 比 `%` 快得多。

**Q2：为什么 HashMap 容量必须是 2 的幂？**
→ 两个理由：① 能用 `& (n-1)` 代替 `%`，且容量利用率 100%（非 2 的幂会有桶永远空着）；② 扩容时下标能二分（原地不动或 +oldCap），JDK 8 甚至不用重新算 hash。

**Q3：`h ^ (h >>> 16)` 扰动函数在干什么？**
→ 把 hashCode 的高 16 位异或到低 16 位。因为下标只用低位算，高位等于白算，扰动让高位差异也能被利用起来。

**Q4：哈希冲突有哪些解决方式？HashMap 用的哪种？**
→ 主流两种：拉链法（链表挂同桶节点）、开放寻址（撞了就往旁边看）。HashMap 用拉链法，且当链表长度 ≥ 8 且容量 ≥ 64 时转红黑树（JDK 8），把最坏从 O(n) 降到 O(log n)。

**Q5：`hashCode` 和 `equals` 的契约是什么？违反了会怎样？**
→ `equals` 相等的对象 `hashCode` 必须相等。只重写一个，哈希表直接失效——`put` 进去的东西 `get` 不回来。反过来 `hashCode` 相等但 `equals` 不等，是合法的"哈希冲突"，拉链法兜住。

**Q6：为什么 HashMap 线程不安全？JDK 7 和 JDK 8 有什么区别？**
→ JDK 7 头插法并发扩容会让链表成环（死循环，CPU 打满）；JDK 8 改成尾插后不再成环，但并发写依然会丢数据、size 不准。并发场景用 `ConcurrentHashMap`。

**Q7：负载因子 0.75 是什么？为什么是 0.75？**
→ 负载因子 = size / capacity，0.75 是扩容阈值。调低 → 冲突少但浪费空间、扩容频繁；调高 → 空间省但链表变长。0.75 是空间与时间的折中，不是随便定的。

**Q8：扩容为什么是翻倍而不是 +10？**
→ 翻倍才能保持 2 的幂，让 `& (n-1)` 继续成立，且扩容后节点下标只有两种可能（原地或 +oldCap），为 JDK 8 的"不重新算 hash"铺路。

**Q9：JDK 8 扩容为什么不用重新计算 hash？**
→ 因为新容量是旧容量的 2 倍，`indexFor(hash, newCap)` 和 `indexFor(hash, oldCap)` 的差异只在**1 位**（hash 值里 oldCap 对应的那一位）。判断 `p.hash & oldCap` 是否为 0 就能知道节点该留还是该搬——0 留原桶，1 搬到原下标 + oldCap 的新桶。

---

**本文代码**：`SimpleHashMap.java`（完整实现 + 四个实验），JDK 21 直接 `javac` 跑通。
