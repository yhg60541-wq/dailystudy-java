# Java 基础小复习

> 把 Java 核心知识点串一遍，每一条都能对应到实际代码。

---

## 一、基本数据类型：8 种，别记成 9 种

Java 只有 **8 种**基本数据类型：byte、short、int、long、float、double、char、boolean。

String、Integer、Long 这些**不是**基本类型，是**引用类型**（类）。

为什么容易记错？因为 Integer、Long 这些和基本类型长得太像了。比如 `int a = 0` 和 `Integer b = 0`，赋值方式一样，但本质不同：

| 对比项 | int（基本类型） | Integer（包装类） |
|---|---|---|
| 默认值 | 0 | **null** |
| 能不能为 null | 不能 | 能 |
| 内存位置 | 栈 | 堆（对象） |
| 比较 | == 比较值 | == 比较地址，equals 比较值 |

### 自动装箱和拆箱

```java
// 装箱：基本类型 → 包装类
Integer x = 100;     // 编译器自动 Integer.valueOf(100)

// 拆箱：包装类 → 基本类型
int y = x;           // 编译器自动 x.intValue()
```

### Integer == Integer 的陷阱

```java
Integer a = 127;
Integer b = 127;
System.out.println(a == b);   // true！

Integer c = 128;
Integer d = 128;
System.out.println(c == d);   // false！
```

为什么？Integer 有个**缓存池**（-128 ~ 127），范围内的数字直接返回缓存对象，超出范围每次 `new` 新对象。所以 == 在范围内为 true，超出为 false。**永远用 equals 比较包装类！**

---

## 二、== 和 equals：别搞混

`==` 永远比较内存地址（引用）。`equals` 默认也比较地址，但 String、Integer 等重写了，改成比较内容。

### 表格总结

| 类型 | `==` | `equals` |
|---|---|---|
| 基本类型（int、long...） | 比较**值** | 不能用（编译报错） |
| String | 比较**地址** | 重写了，比较**内容** |
| Integer / Long | 比较**地址** | 重写了，比较**内容** |
| 自己写的类 | 比较**地址** | 默认比较地址，需要自己重写才能比较内容 |

### 高频例子

```java
new String("abc") == new String("abc");    // false，两个不同对象
new String("abc").equals(new String("abc")); // true，内容相同

String s1 = "abc";
String s2 = "abc";
s1 == s2;   // true！字符串常量池复用了同一个对象
```

### 为什么 String 能进常量池？

因为 String 是**不可变的**（类被 final 修饰，内部 char 数组也被 final 修饰）。放心复用，不会被改。这也是 String 适合做 HashMap key 的原因——key 不能变，否则 hash 值变了就找不到了。

---

## 三、String 不可变：为什么要这样设计？

`String` 类被 `final` 修饰，内部存字符的 `char[]` 也被 `final` 修饰——创建完就不能改。

### 好处

1. **安全**：网络连接参数、数据库连接字符串不会被恶意代码篡改
2. **线程安全**：不可变对象天然线程安全，不需要加锁
3. **常量池复用**：相同的 "abc" 只存一份，省内存
4. **适合做 HashMap key**：key 不能变 → hash 值不变 → 能找到

### StringBuilder 和 StringBuffer

| 类 | 可变？ | 线程安全？ | 适用场景 |
|---|---|---|---|
| String | ❌ 不可变 | ✅ | 少量拼接，或拼接后不再变 |
| StringBuilder | ✅ 可变 | ❌ 不安全 | **循环拼接用这个**（单线程） |
| StringBuffer | ✅ 可变 | ✅ 安全 | 多线程拼接（内部 synchronized，比 StringBuilder 慢） |

### 为什么循环拼接不能用 String？

```java
// ❌ 不好：每次拼接都创建新对象
String s = "";
for (int i = 0; i < 1000; i++) {
    s = s + i;  // 每次 new 一个新 String，旧的被垃圾回收
}

// ✅ 好：一个 StringBuilder 对象反复 append
StringBuilder sb = new StringBuilder();
for (int i = 0; i < 1000; i++) {
    sb.append(i);  // 不创建新对象，原地修改
}
```

---

## 四、异常体系：Throwable → Error / Exception

```
Throwable（根）
├── Error          → JVM 级错误，不需要捕获
│   ├── OutOfMemoryError      （内存不够）
│   └── StackOverflowError    （栈溢出，递归太深）
└── Exception
    ├── RuntimeException（非受检异常，可以不捕获）
    │   ├── NullPointerException
    │   ├── ArrayIndexOutOfBoundsException
    │   └── ClassCastException
    └── 其他（受检异常，必须捕获或声明 throws）
        ├── IOException
        └── SQLException
```

### 为什么 IOException 必须 try-catch，NullPointerException 却可以不管？

因为一个是**受检异常**（编译器强制你处理），一个是**非受检异常**（编译器不强制）。设计上，受检异常代表"可以恢复的错误"（比如网络断了可以重试），非受检异常代表"编程错误"（比如空指针说明代码写错了，应该改代码而不是 catch）。

### 和前几篇博客的关联

博客里反复写 `try { ... } catch (InterruptedException e)`——`InterruptedException` 是**受检异常**，因为 `put()`、`take()`、`sleep()` 这些方法都声明了 `throws InterruptedException`，编译器强制你处理。

---

## 五、synchronized 和 volatile：别以为 volatile 能当锁

### synchronized

保证互斥：同一时刻只有一个线程执行被修饰的代码块。

```java
// 修饰方法
public synchronized void increment() {
    count++;
}

// 修饰代码块（更灵活）
public void increment() {
    synchronized (this) {
        count++;
    }
}
```

### volatile

保证可见性 + 禁止指令重排序，但不保证原子性。

### 什么叫"可见性"？

没有 volatile 时，线程 A 改了变量可能只改了自己 CPU 的缓存，线程 B 看不到最新值。有 volatile 时，线程 A 改了变量强制刷回主内存，线程 B 强制从主内存读，立刻看到。

### 什么叫"禁止指令重排序"？

CPU 为了性能可能调整指令执行顺序（比如把 `b = 2` 提前到 `a = 1` 前面执行）。volatile 修饰的变量，重排序被禁止，前后的指令必须按顺序执行。

### volatile 不保证原子性的经典例子

```java
volatile int count = 0;

// 两个线程同时调 10000 次 increment
public void increment() {
    count++;  // ①读 → ②加 → ③写，三步！volatile 只保证可见性，不保证这三步不被打断
}

// 结果：远小于 20000（丢了很多次）
```

**为什么？** `count++` 不是一条指令，是"读 → 加 → 写"三步。volatile 只保证"写了立刻别人能看到"，但不能保证别人读的时候你还没写完。

### 表格对比

| 对比项 | synchronized | volatile | Lock / ReentrantLock |
|---|---|---|---|
| 保证什么 | 互斥（同一时刻只有一个）+ 可见性 | 可见性 + 禁止重排序 | 互斥 + 可见性 |
| 原子性 | ✅ | ❌ | ✅ |
| 谁实现的 | JVM 内置 | JVM 内置 | JDK 代码（基于 AQS） |
| 释放方式 | 自动（出同步块自动释放） | —（不需要释放） | 手动（finally 里 unlock） |
| 额外能力 | — | — | 公平锁、可中断、超时获取 |

### AtomicInteger 是怎么保证 incrementAndGet 原子的？

底层 CAS！和前几篇博客里写的 `compareAndSet` 是同一个硬件指令。`incrementAndGet` 是 CAS 循环：读当前值 A → 尝试 CAS(A, A+1) → 成功就返回，失败就重读再试。

---

## 六、集合框架

### ArrayList vs LinkedList

| 对比项 | ArrayList | LinkedList |
|---|---|---|
| 底层 | 动态数组 | 双向链表 |
| 查询（按下标） | **快** O(1)（随机访问） | **慢** O(n)（要从头遍历） |
| 增删（中间位置） | **慢** O(n)（要搬移后面的元素） | **快** O(1)（改指针就行） |
| 线程安全 | ❌ 不安全 | ❌ 不安全 |
| 扩容 | 1.5 倍扩容 | 不需要（链表没有固定容量） |

### HashMap 核心

| 版本 | 底层结构 | 线程安全？ |
|---|---|---|
| JDK 7 | 数组 + 链表 | ❌（多线程 resize 会死循环） |
| JDK 8 | 数组 + 链表 + 红黑树 | ❌（不会死循环，但会丢数据） |
| ConcurrentHashMap 7 | 分段锁（Segment） | ✅ |
| ConcurrentHashMap 8 | CAS + synchronized（锁单个桶） | ✅ |

### HashMap 关键参数

| 参数 | 默认值 | 含义 |
|---|---|---|
| 初始容量 | 16 | 数组初始长度（必须是 2 的幂） |
| 负载因子 | 0.75 | `size > capacity × 0.75` 时触发扩容（容量翻倍） |
| 树化阈值 | 8 | 链表长度 ≥ 8 且容量 ≥ 64 → 转红黑树（查询 O(log n)） |
| 还原阈值 | 6 | 树节点降到 6 → 退回链表 |

### 为什么容量必须是 2 的幂？

`hash & (n-1)` 等价于 `hash % n`，但位运算快得多。而且只有 n 是 2 的幂，`n-1` 才能保证低位全 1，hash 的每一位信息才能均匀用到所有桶里。如果容量是 10，`10-1=9=1001`，那 `&` 之后只有第 0、3 位能出结果，桶 2/4/5/6/7 永远空着。

---

## 七、CAS 思想

CAS 是 CPU 硬件级原子指令（`cmpxchg`），把"读 + 判断 + 写"合成一步不可分割的操作。执行时 CPU 锁住内存总线，其他线程此刻不能读也不能写这块内存。

这个思想贯穿了前四篇的所有并发场景：

| 博客 | 抢的是什么 | CAS 写法 |
|---|---|---|
| 2 | sizeCtl 扩容权 | `SIZE_CTL_CAS.compareAndSet(this, 12, -1)` |
| 3 | DelayQueue 延时任务 | `pollFirstEntry()` 底层 CAS |
| 4 | 分布式锁 | `putIfAbsent(key, value) == null` |
| 4 | 原子解锁 | `store.remove(key, expectedValue)` 底层 CAS |

统一思想：只有第一个 CAS 成功的人能干活，其他人看到已经被抢了立刻放弃。

---

## 八、高频知识点

### Q1：HashMap 线程不安全的两个根因？

① 多线程同时 resize 互相覆盖新 table → 丢数据；② 同一个桶写冲突，头插法（JDK 7）会成环死循环。JDK 8 改成尾插法消除了死循环，但并发写依然会丢数据。所以用 ConcurrentHashMap。

### Q2：CAS 怎么保证原子性？

CAS 是 CPU 硬件级指令（`cmpxchg`），把"读 + 判断 + 写"合成一步不可分割的操作。执行时 CPU 锁住内存总线，其他线程此刻不能读也不能写这块内存。

### Q3：DelayQueue 为什么按到期时间出队？

DelayQueue 底层用 PriorityQueue（堆），放消息时按 `compareTo` 排序（到期早的排堆顶），消费者 `take()` 时只看堆首的 `getDelay()`——如果还没到时间就阻塞等，到了才返回。

### Q4：分布式锁怎么防误删别人的锁？

解锁时先判断当前持有者是不是自己（value 比对），只有匹配才删。用双参数 `remove(key, expectedValue)` 底层 CAS，一步完成判断+删除，中间没有空隙。Redis 对应 Lua 脚本（Redis 单线程执行，整个脚本天然原子）。

### Q5：缓存击穿怎么防？

热点 key 过期瞬间，用互斥锁（SETNX 抢"只允许一个人查 MySQL"）。只有抢到锁的人去查 MySQL + 写缓存，其他人 sleep 一下再读缓存（已经有了）。

---

## 九、复盘

| 篇 | 核心 | 对应简历哪个技术栈 |
|---|---|---|
| 1 | HashMap 底层：数组+链表+扰动函数+扩容 | Java 集合 |
| 2 | ConcurrentHashMap：锁分段→CAS→sizeCtl | Java 并发 |
| 3 | 消息队列：BlockingQueue→DelayQueue→ZSET | MQ |
| 4 | Redis 缓存与分布式锁：SETNX→超时→CAS 原子解锁 | Redis |
| **这篇** | Java 基础核心知识点串讲 | Java |

---

## 速查卡

| 问题 | 一句话 |
|---|---|
| int 和 Integer 区别？ | int 是基本类型，默认值 0；Integer 是包装类，默认值 null。 |
| == 和 equals 区别？ | == 比较地址，equals 默认也比较地址，String/Integer 等重写了比较内容。 |
| String 为什么不可变？ | final 修饰 + 内部 char[] final。好处：安全、线程安全、常量池复用、适合做 HashMap key。 |
| volatile 能当锁吗？ | 不能。只保证可见性和禁止重排序，不保证原子性。 |
| synchronized 和 ReentrantLock 区别？ | synchronized JVM 内置自动释放；ReentrantLock JDK 手动释放，支持公平锁、可中断、超时。 |
| HashMap 线程安全吗？ | 不安全。多线程用 ConcurrentHashMap（CAS + 锁桶头）。 |
| 为什么容量必须是 2 的幂？ | 保证 `hash & (n-1)` 等价于 `hash % n`，且 hash 每一位都能均匀用到所有桶。 |
| 异常体系？ | Throwable 根，Error（OOM/StackOverflow）+ Exception（RuntimeException/受检异常）。 |
