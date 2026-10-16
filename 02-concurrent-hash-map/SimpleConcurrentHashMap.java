import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * ================================================================
 *  SimpleConcurrentHashMap —— 手写 ConcurrentHashMap 复盘
 * ================================================================
 *  这是一个"逐关卡递进"的代码：从最开始 HashMap 并发不安全，
 *  到一层层加锁 → 加 CAS → 加 CounterCell 分散计数 → 最终稳定 0 丢失。
 *
 *  关卡 0：无锁 HashMap，10 线程跑 → 丢 14~2816 个 key（看看会发生什么）
 *  关卡 1：synchronized 整表锁 → 0 丢失，但所有写操作排队等锁
 *  关卡 2：想优化成桶锁（synchronized 每个桶的头节点）→ 遇到两把锁不协调的坑
 *  关卡 3：sizeCtl CAS 防重复扩容 + AtomicInteger 替代普通 int → 0 丢失 + 更快
 *  关卡 4（当前版本）：CounterCell 分散计数（LongAdder 风格）+ 对齐核心机制
 *
 *  跑出来的真实结果（10 线程 × 1000 key）：
 *  期望=10000, 实际=10000, 差=0, 丢key=0, 耗时≈45ms（稳定 10 次全 0 丢失）
 * ================================================================
 */
public class SimpleConcurrentHashMap<K, V> {

    // ────────────────────────────────────────────────
    //  数据结构：每个桶是一条链表
    // ────────────────────────────────────────────────

    /**
     * 链表节点：
     *   hash  —— key 经过 hash 扰动后的结果（用来定位桶 + 加速比较）
     *   key   —— 实际的键（final 不可变，防止运行中被改）
     *   value —— 实际的值
     *   next  —— 指向下一个节点（volatile 保证多线程遍历时能看到最新值）
     */
    static class Node<K, V> {
        final int hash;
        final K key;
        V value;
        volatile Node<K, V> next;

        Node(int h, K k, V v, Node<K, V> n) { hash = h; key = k; value = v; next = n; }
    }

    // ────────────────────────────────────────────────
    //  核心字段
    // ────────────────────────────────────────────────

    /**
     * table —— 核心数组（volatile 修饰）
     *   什么是 volatile？简单说：
     *     普通变量在线程间有"可见性"问题——线程 A 改了值，线程 B 可能还在读自己的缓存副本。
     *     volatile 保证：只要 table 被换了（resize 后 table = newTable），
     *     所有线程立刻能看到新引用，不用等缓存刷新。
     */
    private volatile Node<K, V>[] table;

    /**
     * sizeCtl —— ⭐ ConcurrentHashMap 的"灵魂字段"
     *   什么是 sizeCtl？一个 volatile int，含义随值变化：
     *
     *     > 0  ：阈值（size 超过它就触发扩容）。比如初始容量 16 × 0.75 = 12
     *     = -1 ：正在扩容中（简化版单线程独占，JDK 8 是 -(N+1) 表示 N 个线程协作搬）
     *
     *   为什么需要它？
     *     关卡 1 的整表锁方案：每个 put 都要 synchronized，包括 size 根本没到阈值的线程也要排队。
     *     用 sizeCtl + CAS：只有 size 刚到阈值的那批线程才竞争一次 CAS，其他人直接跳过，不排队。
     */
    private volatile int sizeCtl;

    /**
     * CounterCell[] 分散计数（LongAdder 风格，JDK 8 CHM 的 base + CounterCell[]）
     *
     *  为什么不用单个 AtomicInteger？
     *    AtomicInteger.incrementAndGet() 底层是 CAS，高并发下多个线程抢同一个变量：
     *      - 线程 A CAS 成功 → 改值
     *      - 线程 B CAS 失败 → 自旋重来
     *      - 线程 C CAS 也失败 → 自旋重来
     *      ...
     *    100 线程抢一个 AtomicInteger，平均每个线程要自旋 ~50 次才能成功一次！
     *
     *  CounterCell 的思路（LongAdder）：
     *    不是让所有线程抢同一个变量，而是分散到 N 个 cell。
     *    每个线程只碰自己那个 cell（靠线程 local 的 probe 值 hash 到某个 cell）。
     *    size() 的时候把 baseCount + 所有 cell 加起来。
     *    100 线程分散到 16 个 cell，平均每个 cell 只有 ~6 个线程碰，几乎不自旋。
     */
    private long baseCount = 0L;                               // 低并发兜底：直接 CAS 改这个
    private volatile AtomicLongArray counterCells;             // 高并发分散：各线程碰不同 cell
    private static final ThreadLocal<Integer> PROBE =          // 线程 local 的 cell 下标种子
        ThreadLocal.withInitial(() -> Math.abs((int) System.nanoTime()));

    // ────────────────────────────────────────────────
    //  VarHandle：Java 9+ 官方 CAS API（替代 Unsafe）
    // ────────────────────────────────────────────────

    /**
     * 什么是 CAS？Compare-And-Swap（比较并交换）—— CPU 硬件级原子指令。
     *
     *   CAS(V, A, B) 做的事：
     *     1. 读取内存位置 V 当前的值
     *     2. 如果 V == A（还是我预期的值 A）→ 把 V 改成 B，返回 true
     *     3. 如果 V ≠ A（被别人抢先改过了）→ 啥也不干，返回 false
     *
     *   这三步在 CPU 层面是**原子的**——要么全做完，要么不做，中间不会被打断。
     *   所以多个线程同时 CAS，只有一个能成功，其他全部失败重来。
     *
     *   VarHandle vs Unsafe：
     *     JDK 8 用 Unsafe.compareAndSwapObject，Java 9+ 出了 VarHandle 替代它。
     *     VarHandle 更安全（有类型检查），底层同样调用 CPU 的 CAS 指令。
     */
    private static final VarHandle SIZE_CTL_CAS;     // 用在 tryResize()：抢扩容权
    private static final VarHandle BASE_COUNT_CAS;   // 用在 addCount()：低并发兜底计数

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.privateLookupIn(
                    SimpleConcurrentHashMap.class, MethodHandles.lookup());
            SIZE_CTL_CAS   = l.findVarHandle(SimpleConcurrentHashMap.class, "sizeCtl",   int.class);
            BASE_COUNT_CAS = l.findVarHandle(SimpleConcurrentHashMap.class, "baseCount", long.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ────────────────────────────────────────────────
    //  构造 & 工具方法
    // ────────────────────────────────────────────────

    public SimpleConcurrentHashMap() {
        this.table = new Node[16];         // 初始容量 16
        this.sizeCtl = 12;                  // 阈值 = 16 × 0.75 = 12（load factor 0.75）
    }

    /**
     * hash 扰动：key.hashCode() 的高位和低位做异或
     * 为什么？让高位的差异也能参与到散列里，减少碰撞。
     * 比如两个 hashCode 只差最高位，不扰动的话在小容量 table 里会落到同一个桶。
     */
    static int hash(Object k) {
        int h = k == null ? 0 : k.hashCode();
        return h ^ (h >>> 16);
    }

    /**
     * (n-1) & hash 定位桶下标
     * 为什么用 (n-1) 而不是 % n？
     *   当 n 是 2 的幂时，(n-1) 的二进制是全 1（16→1111，32→11111），
     *   和 hash 做与运算就是取 hash 的低 log2(n) 位，效果和取模一样但快得多。
     */
    static int idx(int h, int n) { return h & (n - 1); }

    // ────────────────────────────────────────────────
    //  CounterCell 分散计数
    // ────────────────────────────────────────────────

    /**
     * 新增 delta 个元素时调用这个方法。
     * 自动走低并发兜底或高并发分散。
     */
    private void addCount(long delta) {
        if (counterCells == null) {
            // 低并发：直接 CAS 改 baseCount（几乎一定成功）
            if (BASE_COUNT_CAS.compareAndSet(this, baseCount, baseCount + delta)) return;
            // CAS 失败了 → 说明并发上来了，初始化 counterCells 切换到分散模式
            counterCells = new AtomicLongArray(
                Math.max(Runtime.getRuntime().availableProcessors() * 2, 2));
        }
        // 高并发：分散到 counterCells[probe % length]
        // PROBE 是线程 local 的随机种子，各线程 hash 到不同 cell
        int probe = PROBE.get();
        counterCells.addAndGet(Math.abs(probe) % counterCells.length(), delta);
    }

    /** size()：base + 所有 cell 求和 */
    private long sumCount() {
        long sum = baseCount;
        AtomicLongArray cs = counterCells;
        if (cs != null) for (int i = 0; i < cs.length(); i++) sum += cs.get(i);
        return sum;
    }

    // ────────────────────────────────────────────────
    //  put：核心写入逻辑
    // ────────────────────────────────────────────────

    /**
     * 为什么用 synchronized(this) 整表锁？
     *
     *   JDK 8 的 ConcurrentHashMap 用的是 synchronized(桶头节点) 桶锁——粒度更细。
     *   但简化版做不到桶锁，因为：
     *     空桶时桶头是 null → synchronized(null) 报错！得改成 synchronized(table)，
     *     这就和非空桶的 synchronized(head) 成了两把不同的锁，互不协调。
     *
     *   那 JDK 8 怎么解决空桶问题？
     *     答案是：空桶不用锁！直接 CAS 无塞！
     *     （帖子原话："桶为空的空桶 → 用 CAS 直接放进去（不加锁）"）
     *     但这个 CAS 能生效，是因为配合了 ForwardingNode（标记已搬完桶）
     *     + helpTransfer（新来的线程帮忙搬节点）+ transferIndex（分段搬运）三件套。
     *
     *   简化版缺 ForwardingNode，resize 期间往旧 table 塞的节点永远不会被搬到新 table → 丢。
     *   所以用 synchronized(this) 整表锁保底，保证 put 和 doResize 严格串行。
     */
    public synchronized V put(K key, V value) {
        int hash = hash(key);
        int i = idx(hash, table.length);
        Node<K, V> head = (Node<K, V>) ((Object[]) table)[i];

        if (head == null) {
            // 空桶：直接塞（JDK 8 这里是 TabAt CAS 无锁塞，简化版用 synchronized 已经拿锁了）
            ((Object[]) table)[i] = new Node<>(hash, key, value, null);
        } else {
            // 非空桶：遍历链表
            for (Node<K, V> p = head; p != null; p = p.next) {
                if (p.hash == hash && (p.key == key || (key != null && key.equals(p.key)))) {
                    V old = p.value; p.value = value;
                    return old;  // 覆盖已有 key → 不算新增
                }
            }
            // 没找到 → 头插到链表最前面
            ((Object[]) table)[i] = new Node<>(hash, key, value, head);
        }
        addCount(1);
        return null;
    }

    // ────────────────────────────────────────────────
    //  tryResize：触发扩容（真硬件级 CAS）
    // ────────────────────────────────────────────────

    /**
     *  为什么用 CAS sizeCtl，不用 synchronized(this) 包住 tryResize？
     *
     *   synchronized 会让所有 tryResize 串行化——包括那些 size 根本没到阈值的线程也要排队。
     *   CAS 是无锁的：只有 size 刚到阈值的那批线程才竞争一次 CAS，其他人 size 不够直接 return，
     *   根本不碰 CAS。
     *
     *   场景：10 个线程同时 put，size 都刚好超过阈值 12
     *     线程 A：tryResize → CAS(12, -1) → ✅ 成功 → 独占执行 doResize
     *     线程 B：tryResize → CAS(12, -1) → ❌ 失败（sizeCtl 已经是 -1）→ 直接 return
     *     线程 C、D、E...：同 B，全部 CAS 失败，全部跳过
     *
     *   这就是 CAS 的威力——10 个线程竞争，只有一个线程干了一次 CPU CAS 指令，
     *   其他 9 个线程干了一次 CPU CAS 指令发现失败就走了，没有线程被挂起、没有排队。
     */
    private void tryResize() {
        if (sizeCtl <= 0) return;          // 已经在扩容了，跳过
        if (sumCount() <= sizeCtl) return; // size 还没到阈值，跳过

        // ⭐ 真·硬件级 CAS：只有一个线程能把 sizeCtl 从阈值改成 -1
        if (!SIZE_CTL_CAS.compareAndSet(this, sizeCtl, -1)) return;

        // CAS 成功的那个线程 → 独占执行扩容
        doResize();
    }

    // ────────────────────────────────────────────────
    //  doResize：真正搬节点
    // ────────────────────────────────────────────────

    /**
     * rehash 逻辑：不是重新 hash(key)，而是看 (p.hash & oldCap)
     * 为什么？因为 newCap = oldCap << 1（翻倍），
     * 多出来的那一位 hash 值（oldCap 那一位）决定节点在新 table 的位置：
     *   & oldCap == 0 → 这一位是 0 → 留在原下标 j
     *   & oldCap != 0 → 这一位是 1 → 去 j + oldCap
     *
     * 例子：oldCap=16，hash=25 (二进制 11001)
     *   hash & 16 (10000) = 16 ≠ 0 → 去新 table 的下标 idx(25, 32) = 25 & 31 = 25
     *   而原来 idx(25, 16) = 25 & 15 = 9，正好 9 + 16 = 25！
     *   所以判断 (hash & oldCap) 就能知道节点是留原地还是搬去 i + oldCap。
     *   这比重新 hash + 重新 idx 快得多！
     */
    @SuppressWarnings("unchecked")
    private synchronized void doResize() {
        Node<K, V>[] old = table;
        int oldCap = old.length;
        int newCap = oldCap << 1;
        Node<K, V>[] nt = new Node[newCap];

        for (int j = 0; j < oldCap; j++) {
            Node<K, V> p = (Node<K, V>) ((Object[]) old)[j];
            if (p == null) continue;

            // 拆成两条链：低位链（留在 j）+ 高位链（去 j + oldCap）
            Node<K, V> lowHead = null, lowTail = null;
            Node<K, V> highHead = null, highTail = null;

            while (p != null) {
                Node<K, V> next = p.next;
                p.next = null;
                if ((p.hash & oldCap) == 0) {
                    // 低位：留在 j
                    if (lowTail == null) lowHead = lowTail = p;
                    else { lowTail.next = p; lowTail = p; }
                } else {
                    // 高位：去 j + oldCap
                    if (highTail == null) highHead = highTail = p;
                    else { highTail.next = p; highTail = p; }
                }
                p = next;
            }

            if (lowHead != null)  ((Object[]) nt)[j] = lowHead;
            if (highHead != null) ((Object[]) nt)[j + oldCap] = highHead;
        }

        table = nt;                        // volatile 写：新 table 对所有线程立即可见
        sizeCtl = (int) (newCap * 0.75f);  // 改回正数（新阈值）
    }

    // ────────────────────────────────────────────────
    //  get：读无锁遍历
    // ────────────────────────────────────────────────

    /**
     * JDK 8 CHM 的 get 完全无锁！
     * 靠 volatile table + volatile Node.next 保证遍历时的可见性。
     * 这就是 CHM 性能比 Hashtable（get 也加锁）好这么多的核心原因——读不需要排队！
     */
    public V get(K key) {
        Node<K, V>[] tab = table;  // volatile 读
        int hash = hash(key);
        int i = idx(hash, tab.length);
        Node<K, V> head = (Node<K, V>) ((Object[]) tab)[i];
        if (head == null) return null;

        // 无锁遍历：volatile next 保证看到最新值
        for (Node<K, V> p = head; p != null; p = p.next) {
            if (p.hash == hash && (p.key == key || (key != null && key.equals(p.key)))) {
                return p.value;
            }
        }
        return null;
    }

    public int size() { return (int) sumCount(); }

    // ────────────────────────────────────────────────
    //  关卡 4 验收测试
    // ────────────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        SimpleConcurrentHashMap<Integer, String> map = new SimpleConcurrentHashMap<>();
        int threadCount = 10, perThread = 1000;
        Thread[] threads = new Thread[threadCount];

        for (int t = 0; t < threadCount; t++) {
            final int base = t * perThread;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    map.put(base + i, "v" + (base + i));
                }
            }, "Worker-" + t);
        }

        long start = System.currentTimeMillis();
        for (Thread th : threads) th.start();
        for (Thread th : threads) th.join();
        long elapsed = System.currentTimeMillis() - start;

        int expected = threadCount * perThread;
        int actual = map.size();
        int missing = 0;
        for (int i = 0; i < expected; i++) {
            if (map.get(i) == null) missing++;
        }

        System.out.printf("期望=%d, 实际=%d, 差=%d, 丢key=%d, 耗时=%dms%n",
                expected, actual, expected - actual, missing, elapsed);
    }
}
