/**
 * 手写简化版哈希表：数组 + 链表（拉链法）+ 动态扩容。
 *
 * 对齐 JDK HashMap 的四个核心机制：
 * 1) 容量永远是 2 的幂 —— 可以用位运算 & 代替取模 %，且扩容时下标能"二分"重算；
 * 2) hash 扰动函数 —— 把高 16 位异或到低 16 位，让高位也参与下标计算，减少冲突；
 * 3) 链表解决哈希冲突 —— 同桶的不同 key 串成一条链（拉链法）；
 * 4) 负载因子 + 扩容 —— size 超过阈值就容量翻倍并重新分布节点。
 *
 * 刻意省略 JDK 实现里的：红黑树树化、尾插保持插入顺序、并发安全、null key 的特殊处理。
 */
public class SimpleHashMap<K, V> {

    /** 桶节点：这里用最简单的单链表 */
    static class Node<K, V> {
        final int hash;      // 缓存哈希值，比较时先用 hash 挡掉大部分不相等的情况
        final K key;
        V value;
        Node<K, V> next;     // 同一桶内的下一个节点

        Node(int hash, K key, V value, Node<K, V> next) {
            this.hash = hash;
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }

    private Node<K, V>[] table;      // 桶数组，长度永远是 2 的幂
    private int size;                // 已存键值对数量
    private int threshold;           // 扩容阈值 = capacity * loadFactor
    private final float loadFactor;  // 负载因子

    private static final int DEFAULT_CAPACITY = 16;
    private static final float DEFAULT_LOAD_FACTOR = 0.75f;

    public SimpleHashMap() {
        this(DEFAULT_CAPACITY, DEFAULT_LOAD_FACTOR);
    }

    @SuppressWarnings("unchecked")
    public SimpleHashMap(int capacity, float loadFactor) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("容量必须为正数");
        }
        this.loadFactor = loadFactor;
        int cap = tableSizeFor(capacity);   // 向上取整到 2 的幂
        this.table = new Node[cap];
        this.threshold = (int) (cap * loadFactor);
    }

    /** 把任意容量向上取整到 2 的幂，保证下标可以用位运算算出来 */
    private static int tableSizeFor(int cap) {
        int n = -1 >>> Integer.numberOfLeadingZeros(cap - 1);
        if (n < 0) {
            return 1;
        }
        return n >= 1 << 30 ? 1 << 30 : n + 1;
    }

    /** 扰动函数：h ^ (h >>> 16)，让 hashCode 的高位也能影响低位下标 */
    static int hash(Object key) {
        int h = key == null ? 0 : key.hashCode();
        return h ^ (h >>> 16);
    }

    /** 取下标：hash & (n - 1)。n 是 2 的幂时，它等价于 hash % n，但位运算更快 */
    private int indexFor(int hash, int n) {
        return hash & (n - 1);
    }

    /** 放入/覆盖键值对，返回被覆盖的旧值（没有则返回 null） */
    public V put(K key, V value) {
        int hash = hash(key);
        int i = indexFor(hash, table.length);

        // 第一步：遍历该桶的链表，key 相等就覆盖旧值
        for (Node<K, V> p = table[i]; p != null; p = p.next) {
            if (p.hash == hash && (p.key == key || (key != null && key.equals(p.key)))) {
                V old = p.value;
                p.value = value;
                return old;
            }
        }

        // 第二步：没有相同 key，头插进该桶
        table[i] = new Node<>(hash, key, value, table[i]);
        size++;

        // 第三步：超过阈值就扩容
        if (size > threshold) {
            resize();
        }
        return null;
    }

    public V get(K key) {
        int hash = hash(key);
        int i = indexFor(hash, table.length);
        for (Node<K, V> p = table[i]; p != null; p = p.next) {
            if (p.hash == hash && (p.key == key || (key != null && key.equals(p.key)))) {
                return p.value;
            }
        }
        return null;
    }

    public V remove(K key) {
        int hash = hash(key);
        int i = indexFor(hash, table.length);
        Node<K, V> prev = null;
        for (Node<K, V> p = table[i]; p != null; prev = p, p = p.next) {
            if (p.hash == hash && (p.key == key || (key != null && key.equals(p.key)))) {
                if (prev == null) {
                    table[i] = p.next;      // 删的是链表头
                } else {
                    prev.next = p.next;     // 跳过 p
                }
                size--;
                return p.value;
            }
        }
        return null;
    }

    public int size() {
        return size;
    }

    /** 扩容：容量翻倍，把所有节点重新分配到新桶 */
    @SuppressWarnings("unchecked")
    private void resize() {
        Node<K, V>[] old = table;
        int oldCap = old.length;
        int newCap = oldCap << 1;               // 容量翻倍
        Node<K, V>[] nt = new Node[newCap];

        for (int j = 0; j < oldCap; j++) {
            Node<K, V> p = old[j];
            while (p != null) {
                Node<K, V> next = p.next;
                int i = indexFor(p.hash, newCap);   // 用新容量重算下标
                p.next = nt[i];
                nt[i] = p;
                p = next;
            }
        }
        table = nt;
        threshold = (int) (newCap * loadFactor);
        System.out.printf("[resize] 容量 %d -> %d，阈值 -> %d%n", oldCap, newCap, threshold);
    }

    /** 打印内部结构：每个非空桶的链表长这样 [下标] k=v -> k=v */
    public void dump(String title) {
        System.out.println("---- " + title + " (size=" + size + ", cap=" + table.length + ") ----");
        for (int i = 0; i < table.length; i++) {
            if (table[i] == null) {
                continue;
            }
            StringBuilder sb = new StringBuilder("  [" + i + "] ");
            for (Node<K, V> p = table[i]; p != null; p = p.next) {
                sb.append(p.key).append('=').append(p.value);
                if (p.next != null) {
                    sb.append(" -> ");
                }
            }
            System.out.println(sb);
        }
    }

    /** 某个桶里的链表长度（用于观察哈希冲突程度） */
    public int bucketSize(K key) {
        int hash = hash(key);
        int n = 0;
        for (Node<K, V> p = table[indexFor(hash, table.length)]; p != null; p = p.next) {
            n++;
        }
        return n;
    }

    // ================= 四个实验 =================

    /** 实验一：默认容量 16 的桶分布 */
    static void demoBucketDistribution() {
        SimpleHashMap<String, String> m = new SimpleHashMap<>();
        m.put("id", "0");
        m.put("name", "1");
        m.put("age", "2");
        m.put("city", "3");
        m.put("score", "4");
        m.put("level", "5");
        m.dump("实验一：默认容量 16 的桶分布");
    }

    /** 实验二：扩容边界 */
    static void demoResizeBoundary() {
        SimpleHashMap<Integer, String> m = new SimpleHashMap<>();
        for (int i = 1; i <= 12; i++) {
            m.put(i, "v" + i);
        }
        System.out.println("实验二：插入 12 个元素后 size=" + m.size()
                + "（还没到阈值 12？注意判断是 > 阈值）");
        m.put(13, "v13");   // 第 13 个才触发扩容
        m.dump("实验二：第 13 个元素触发扩容后的分布");
    }

    /** 实验三：哈希冲突退化成 O(n) */
    static class BadKey {
        private final int id;
        BadKey(int id) { this.id = id; }
        @Override public int hashCode() { return 1; }   // 所有 BadKey 撞同一个桶
        @Override public boolean equals(Object o) {
            return o instanceof BadKey && ((BadKey) o).id == this.id;
        }
    }
    static void demoWorstCase() {
        SimpleHashMap<BadKey, String> m = new SimpleHashMap<>();
        for (int i = 1; i <= 5; i++) {
            m.put(new BadKey(i), String.valueOf(i));
        }
        m.dump("实验三：5 个 hashCode 全相同的 key");
        BadKey any = new BadKey(1);
        System.out.println("bucketSize = " + m.bucketSize(any)
                + " -> get 最坏要比较 " + m.bucketSize(any) + " 次");
    }

    /** 实验四：扰动函数的价值 */
    static void demoDisturb() {
        int h1 = Integer.hashCode(0);       // 0x00000000
        int h2 = Integer.hashCode(65536);  // 0x00010000
        int n = 16;
        int disturb1 = h1 ^ (h1 >>> 16);
        int disturb2 = h2 ^ (h2 >>> 16);
        System.out.println("实验四：hashCode 高位不同、低位相同（容量 n = " + n + "）");
        System.out.printf("  key=0      hashCode=0x%08X  不扰动->桶%d    扰动->桶%d%n",
                h1, h1 & (n - 1), disturb1 & (n - 1));
        System.out.printf("  key=65536  hashCode=0x%08X  不扰动->桶%d    扰动->桶%d%n",
                h2, h2 & (n - 1), disturb2 & (n - 1));
    }

    public static void main(String[] args) {
        demoBucketDistribution();
        System.out.println();
        demoResizeBoundary();
        System.out.println();
        demoWorstCase();
        System.out.println();
        demoDisturb();
    }
}
