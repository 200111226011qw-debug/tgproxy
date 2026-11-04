# Phase 5 预研：镜像数据新鲜度（2026-11-04 实测）

> 本轮不实现，只固化证据，避免下次重新踩一遍。

## 1. 实测结论

对 `gcore` / `cdn` / `fastly` 三个 jsDelivr 节点的 `proxy_manager.py` 做 HEAD：

| 节点 | `Age` | 缓存头 |
|---|---:|---|
| gcore.jsdelivr.net | 3405s | `Cache-Control: public, max-age=604800, s-maxage=43200` |
| cdn.jsdelivr.net | 2359s | 同上 |
| fastly.jsdelivr.net | 3406s | 同上 |

三个关键事实：

1. **`s-maxage=43200` = 12 小时**，与「分支引用缓存 12h」的说法一致，不是猜测。
2. **`Age` 头可用**：请求发出时缓存里那份已经陈旧 39~57 分钟。这是可编程读取的新鲜度信号。
3. **三节点 ETag 相同**（`W/"10fa4-d3527e5a751ed624"`），说明它们共享同一份陈旧对象——换节点不解决新鲜度。

## 2. 已排除的方案

| 方案 | 结果 |
|---|---|
| URL 追加 `?v=<epoch>` 或 `?v=<random>` | **无效**。三次请求都返回 69,540 字节的旧对象，与不带查询串完全一致 |
| 换 `gcore` / `fastly` 节点 | **无效**。三者 `Age` 相近、ETag 相同 |
| 用 `@<commit-sha>` | 内容正确但**永久冻结**在某个提交，比 12h 更旧，只适合做「版本固定」不适合「取最新」 |
| GitHub API `contents` | 沙箱到 `api.github.com` 的 HTTPS 被阻断（TCP 通、TLS 000）；用户环境 GitHub 直连同样不可用 |

## 3. 候选方案（待实现与实测）

### A. 新鲜度可见化（成本最低，先做）
`mirror_race` 已经在解析响应头。增加：
- `Attempt.age_seconds`（读 `Age`），`FetchResult.age_seconds`（胜出者）；
- 若 `age_seconds > 2 × 刷新间隔`，本轮标记 `stale=True`，可通过 `/api/proxies` 暴露，UI 显示「数据来自镜像缓存（xx 分钟前）」。

不改抓取行为，只让用户知道数据有多旧——比静默给旧数据诚实。

### B. 引入不同 TTL 的镜像并竞速
候选：`raw.githack.com`、`cdn.statically.io`。需要实测：
- 它们的 `Cache-Control` / `Age` 是多少；
- 同一路径下的内容是否比 jsDelivr 新；
- 可用性（国内可达性）。

若其中一个 TTL 在分钟级，竞速天然会选中它（新鲜度优先于延迟），jsDelivr 退化为兜底。

### C. 优先非 GitHub 源
`proxyscrape` API 已在默认源里，它不走 CDN、无此问题。可提高其权重，或在「全源皆 stale」时提示用户。

## 4. 下一轮要先量的三件事

1. `raw.githack.com` / `cdn.statically.io` 的 `Age` 与内容新鲜度（同一 commit 下的文件对比）。
2. 竞速时把「新鲜度」作为排序键之一是否会导致延迟劣化（需要 `bench_phase5.py`）。
3. `Age` 缺失时（非 CDN 源）如何归一化，避免把「无头」误判为「最新」。
