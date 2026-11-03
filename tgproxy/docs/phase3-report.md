# Phase 3 报告：竞速合入 + 并发调优 + 3000 节点 A/B

**日期**：2026-11-04
**基线源码**：`200111226011qw-debug/tgproxy@main`（jsDelivr 取回，`proxy_manager.py` 69,540 字节，与 GitHub blob 一致）
**测试**：156 passed（项目 93 + 本项目新增 63），改动前后均全绿

---

## 0. 一句话结论

| 指标 | 改造前 | 改造后 | 提升 |
|---|---:|---:|---:|
| fetch（直连黑洞 + 健康镜像） | 15,090 ms | **279 ms** | **54.0×** |
| TG 实测（2700 节点，并发 100 → 300） | 1,663 ms | **590 ms** | **2.8×** |
| TCP 预筛（3000 节点，未改动） | 493 ms | 493 ms | — |
| **单轮合计（3000 节点合成）** | **17.25 s** | **1.36 s** | **12.65×** |

---

## 1. 改了什么

### 1.1 `fetch_proxy_text`（L432–446 → 竞速）
- 直连与 jsDelivr 镜像**并发起跑**，取首个「完整响应」。
- 复用上游 `mirror_urls()` / `_RAW_URL_RE` / `MIRROR_HOSTS`，不另写一份候选构造。
- `TGPROXY_FETCH_RACE=0` 回退到原顺序逻辑（逐字节一致），用于 A/B 与线上回滚。
- 超时语义：**每候选 timeout → 整体 timeout**。旧实现最坏 5×15s=75s；新实现最坏 15s。
- 解码：项目侧传 `utf8_errors="replace"`，与原 `_http_get` 一致，避免非 UTF-8 源被误杀。

### 1.2 `TG_TEST_MAX_WORKERS`：100 → 300（env 可覆盖）
实测（2700 个 SOCKS5 节点，合成闸门 60ms）：

| workers | 耗时 | 相对 100 |
|---:|---:|---:|
| 100 | 1,663 ms | 1.00× |
| 250 | 704 ms | 2.36× |
| **300** | **590 ms** | **2.82×** |
| 400 | 470 ms | 3.54× |
| 500 | 402 ms | 4.14× |

**为什么取 300 而不是 250 或 500**：
- 250 是你给的起点；300 快 17%，且与 TCP 预筛水位（300）对称。
- 400/500 在合成基准里仍线性——但合成闸门只有 `sleep`，**不含真实 TLS 的 CPU 成本**。真实环境里 500 路并发握手会先撞 CPU，线性必然弯折。所以不盲推。
- 保守回退：`TGPROXY_TG_WORKERS=250`。

### 1.3 逐 future 兜底（防呆）
`_test_telegram_entries` / `_test_entries` 的 `future.result()` 改为 try/except：
单节点异常只把该节点记为失败，不再冒泡跳出 for。否则 `with ThreadPoolExecutor` 退出会
`shutdown(wait=True)`，把剩余 200+ 个任务全部 join —— 一颗老鼠屎拖死整轮。

### 1.4 `mirror_race.py` 配套
- 内部线程池 8 → **64**（上游 20 源 × 每源最多 4 候选 = 80 的极端上界；8 个 worker 会被黑洞候选堵满一整个 `connect_timeout`）。
- 新增 `utf8_errors` 参数（默认 `strict`，项目侧传 `replace`）。

---

## 2. 3000 节点 A/B 明细

```
fetch   baseline= 15090.1ms  race=  279.3ms  speedup=54.0x
tcp        493.4ms  tested=3000 ok=3000
tg      workers=100     1662.8ms  (baseline)
tg      workers=300      590.5ms
total   before= 17.25s  after= 1.36s  saved=15.88s  speedup=12.65x
```

**真实性声明**：
- `fetch`：真实 HTTP（本地黑洞 + 健康镜像）。黑洞 `accept` 后永不响应，等价于 GitHub 被墙。
- `tcp`：**真实 socket**。300 个本地 sink（accept 即 close），3000 节点轮询，3000/3000 通过。
- `tg`：**合成闸门**（`sleep(RTT/2)`）。沙箱到 Telegram DC 不通、也无法为 `desktop.telegram.org` 签受信证书，所以**不含 TLS 握手成本、不含丢包重传**。它只证明调度与并发水位。
- 因此 17.25s → 1.36s 是**结构性对比**，不是 WAN 实测。真实环境的总耗时由 RTT 主导，改善幅度会小于 12.65×，但 fetch 的 15s 黑洞是**确定性**收益。

---

## 3. 一个顺带发现（影响竞速候选）

`cdn.jsdelivr.net` 对 **HTML** 文件返回 `301 → raw.githubusercontent.com`（被墙），而
`gcore` / `fastly` 正常 `200`。代理列表是 `.txt`，不受影响；但**爬虫模式下抓 HTML 页面时，
cdn 候选必然失败**，实际由 gcore/fastly 胜出。竞速能自动绕过，只是浪费一个候选。

---

## 4. 如何应用到你的仓库

```bash
# 1) 把 mirror_race.py 放进 tgproxy/（新增文件，不在 patch 里）
# 2) 在仓库根目录打补丁
git apply docs/phase3.patch
# 3) 回归
python -m pytest tgproxy/tests -q
```

补丁只含 `tgproxy/proxy_manager.py` 的 6 个 hunk，已用 `git apply --check` 在原始文件上验证通过（LF 行尾）。

**回退**：`TGPROXY_FETCH_RACE=0` 恢复顺序抓取；`TGPROXY_TG_WORKERS=250` 恢复保守并发。

---

## 5. 未做 / 下一步

- 未接 `app.py` / UI：`fetch` 阶段耗时属于 `stage_durations.fetch`，现有界面已能展示，无需改前端。
- 未改 `mtproto_async.py`（封存）。
- 下一步（可选）：Phase 1b `ee`/FakeTLS；或把 `tg_gate_stats` 的 `dc_fail` 比例接进源体检，作为「环境异常」的第二判据。
