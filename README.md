# dailystudy-java

> 用「手写轮子倒逼复习」的方式，把后端常用数据结构和中间件逐个造一遍，再回头看自己项目里为什么这么用。

## 为什么做这个

八股文背了忘、忘了背，项目里的 `HashMap`、`ConcurrentHashMap`、Redis ZSet 每天在用，但说不出"为什么是它而不是别的"。

这个仓库的目标：**每一篇手写一个最小可运行实现 → 对齐 JDK/中间件的设计决策 → 回到自己的项目对照验证**。

## 目录

| 篇数 | 主题 | 目录 |
| --- | --- | --- |
| 第 1 篇 | 手写简化哈希表（拉链法 + 扰动 + 扩容） | `01-hash-table-from-scratch/` |

## 每篇包含什么

- **完整代码**：可独立编译运行，带中文注释
- **配套文章**：掘金/CSDN 发布用，含概念解释、原理推导、踩坑清单、面试问答

## 运行环境

- JDK 21
- 无第三方依赖

## 本地跑

```bash
cd 01-hash-table-from-scratch
javac SimpleHashMap.java
java SimpleHashMap
```
