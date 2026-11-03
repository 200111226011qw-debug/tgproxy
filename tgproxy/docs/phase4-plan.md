# Phase 4 方案：MTProto 真校验接入主流程

> 前提：Phase 3 的 PR 已合并到 `main`（`feat/phase3-upgrade` → `main`）。Phase 4 从 `main` 拉 `feat/phase4-mtproto-wiring`。
> 若尚未合并，先不开发——否则分支里没有 `mtproto_handshake.py`。

---

## 0. 现状（已核实，不是推测）

| 事实 | 证据 |
|---|---|
| 主流程完全没引用 MTProto 校验 | `grep mtproto_handshake proxy_manager.py` → 无结果 |
| MTProto 节点恒为未验证 | L1542 `item["tg_ok"] = None` |
| entry 里**没有** `secret` 字段 | L263 只返回 `link/host/port/type` |
| 但 entry 带完整 `link` | `tg://proxy?server=…&port=…&secret=…`，可反解 |
| `_run_refresh` 的记账已支持三态 | `tg_ok is True → pass`，`False → fail`，`None → 跳过` |

结论：**`_run_refresh`、`sort_proxies`、known_bad 记账都不用改**，只需改测试入口与统计隔离。

---

## 1. 五处改动

### 1.1 新增 `_secret_from_link(link)`
```python
def _secret_from_link(link):
    """从 entry["link"] 反解 secret。不新增 entry 字段，避免动 /api/proxies 契约。"""
    if not link:
        return None
    try:
        params = parse_qs(urlparse(link).query)
    except ValueError:
        return None
    secret = (params.get("secret") or [""])[0].strip()
    return secret or None
```
- 解析失败 → `None` → 该节点保持未验证，不误判为失败。
- `parse_qs` 会自动 URL 解码，与 `_parse_mtproto` 的解析口径一致。

### 1.2 顶部导入（带降级）
```python
try:
    from mtproto_handshake import check_mtproto_proxy
except Exception:                       # 缺模块或缺加密库
    check_mtproto_proxy = None
```
- 缺失时 MTProto 继续走「未验证」，不影响启动。
- 模块级名字可直接被测试 monkeypatch（与 `test_telegram_via_socks5` 同风格）。

### 1.3 预算派生函数
```python
def mtproto_budget_for(max_latency_ms):
    """单节点总预算：必须 ≥ 门槛 + 余量，否则能过门槛的节点会被提前砍掉。
    与 tg_dc_timeout_for 同源（同一门槛耦合理由），但只跑一关。"""
    return max(TG_DC_TIMEOUT_BASE_SEC, max_latency_ms / 1000.0 + TG_DC_TIMEOUT_MARGIN_SEC)
```

### 1.4 `_test_telegram_entries` 增加 MTProto 分支

结构：
```
socks5_indices = [i for ... type == "socks5"]
mtproto_indices = [i for ... type == "mtproto"]

进度分母 = len(socks5_indices) + len(mtproto_indices)   # 语义变更，见 §3

同一个 ThreadPoolExecutor(TG_TEST_MAX_WORKERS)：
  - socks5: submit(test_telegram_via_socks5, ..., sink=collect, max_latency_ms=...)
  - mtproto: submit(_check_mtproto_entry, entry, ...)

结果按 index 回填；两类分别累加各自的计数器
```

`_check_mtproto_entry(entry, budget, max_latency_ms)` 返回 `(tg_ok, latency)`：

| 情况 | tg_ok | tg_latency_ms | 说明 |
|---|---|---|---|
| `ok=True` | `True` | `round(rtt_ms)` | 进入 available 候选 |
| `ok=False, code=CRYPTO_UNAVAILABLE` | `None` | `None` | **不惩罚**：缺库不是节点的错 |
| `ok=False, 其他 code` | `False` | `None` | 计入 tg_failed + 连败 |
| `secret` 解析失败 / 无 link | `None` | `None` | 无法测，不计入失败 |
| `check_mtproto_proxy is None` | `None` | `None` | 模块缺失降级 |

**为什么 `CRYPTO_UNAVAILABLE` 必须是 None**：否则没装 `cryptography` 的用户，所有 MTProto 节点会被连败拉黑，属于把环境问题算成节点问题。

### 1.5 统计隔离

- `samples` **只收 SOCKS5**（`collect` 只传给 SOCKS5 任务）。`summarize_tg_gates` 一行不改，`dc_fail/web_fail/ok` 仍是两关漏斗，不被 MTProto 污染。
- 新增 `self._mtproto_gate_stats = {"tested": n, "ok": n, "fail": n, "unavailable": n}`，在 `get_state()` 里作为**新增字段** `mtproto_gate_stats` 暴露（附加，不破坏现有字段）。
- `sort_proxies` 不用改：`0 if tg_ok else 1` 天然把 `True` 排进已验证组。
- UI 每行徽章 `未验证` 仅在 `tg_ok === null` 时显示——接线后只剩「缺库/无法解析」会命中，语义正确。

---

## 2. 不改的东西

| 项 | 原因 |
|---|---|
| `_run_refresh` 的 known_bad 记账 | 三态已支持，`False` 自动 +1、`True` 自动清零 |
| `summarize_tg_gates` | MTProto 不进 samples |
| `sort_proxies` | 无需改 |
| `/api/proxies` 既有字段 | 只新增 `mtproto_gate_stats` |
| Android 端 | 本轮不跟；契约差异记录在案（安卓仍按未验证处理） |

---

## 3. 语义变更（会打破 2 个现有测试，属于有意变更）

| 测试 | 现断言 | 接线后 |
|---|---|---|
| `test_progress_tg_total_counts_only_socks5` | 2 socks5 + 1 mtproto → `total == 2` | `total == 3`（改名 `..._includes_mtproto`） |
| `test_progress_reports_both_test_stages_with_intermediate_counts` | tg `total == 30` | `total == 60` |
| `test_tg_gates.py::test_mtproto_entries_are_not_counted_as_gates` | `tg_ok` 为 `None` | `tg_ok` 为 `True`（mock 成功）；**漏斗 `total` 仍为 1**（隔离不变） |

第 3 条是重点：**连接线后也要保证 MTProto 不进两关漏斗**，只是它的 `tg_ok` 变了。测试要同时锁住这两件事。

---

## 4. 新增测试（`tests/test_mtproto_wiring.py`）

| 用例 | 断言 |
|---|---|
| `test_secret_from_link_tg_scheme` | `tg://proxy?...&secret=abc` → `abc` |
| `test_secret_from_link_https_scheme` | `https://t.me/proxy?...` → 同样解出 |
| `test_secret_from_link_missing_returns_none` | 无 secret / 畸形 URL → `None` |
| `test_mtproto_ok_marks_verified` | mock 返回 ok → `tg_ok is True`、latency 写入 |
| `test_mtproto_fail_marks_failed` | mock 返回 `PROTOCOL_ERROR` → `tg_ok is False` |
| `test_crypto_unavailable_stays_unverified` | mock 返回 `CRYPTO_UNAVAILABLE` → `tg_ok is None` |
| `test_mtproto_not_in_two_gate_funnel` | `tg_gate_stats["total"]` 只计 SOCKS5 |
| `test_mtproto_stats_bucket` | `mtproto_gate_stats == {tested:1, ok:1, fail:0, unavailable:0}` |
| `test_mtproto_uses_budget_from_threshold` | 门槛 5000ms → 预算 ≥ 6.0s |
| `test_missing_module_degrades_to_unverified` | `pm.check_mtproto_proxy = None` → `tg_ok is None` |

全部用 mock，无网络；沿用现有 `_PatchedGates` 风格。

---

## 5. 验收标准

1. `pytest tests/` 全绿（含被有意修改的 3 个用例）。
2. 新增 10 个用例全绿。
3. `get_state()` 同时含 `tg_gate_stats`（仅 SOCKS5）与 `mtproto_gate_stats`（MTProto 三项）。
4. 关掉 `cryptography` 模拟缺库时，MTProto 全为 `None`，且 `known_bad` 不增长。
5. 不改 `mtproto_handshake.py`、`mirror_race.py`、`mtproto_async.py`。

---

## 6. 风险

| 风险 | 缓解 |
|---|---|
| 接线后连败拉黑数量上升（MTProto 首次真正参与判定） | 属预期；首轮观察 `blacklisted` 计数，异常可回退 |
| MTProto 延迟口径与 SOCKS5 不同（前者含握手+resPQ） | 报告与 UI 文案标注；阈值仍用 1000ms 但需知悉口径差异 |
| 同一线程池混跑两类任务，慢的 SOCKS5 可能占满 | 线程池 work-conserving；若实测 MTProto 被饿死，再拆独立小池 |
| `link` 里 secret 解析失败导致静默未验证 | 计入 `mtproto_gate_stats.unavailable`，可从 API 观测 |
| 用户未合并 Phase 3 就开 Phase 4 | 前置检查：分支必须含 `mirror_race.py` 与 `mtproto_handshake.py` |

---

## 7. 完成记录（2026-11-04）

**实际破坏面：1 个测试，不是方案里预估的 3 个。**

排期时的判断有一处错误，实测后修正：
- `test_progress_reports_both_test_stages_with_intermediate_counts` 的 fixture 只生成 60 条 `socks5://`，不含 MTProto，所以接线后 `tg total` 仍是 30，**不会红**。
- `test_tg_gates.py::test_mtproto_entries_are_not_counted_as_gates` 的 MTProto entry 没有 `link`，反解失败后仍是 `tg_ok=None`，**也不会红**（但它现在只覆盖「缺 link 降级」，不是「已接线」）。
- 真正红的只有 `test_progress_tg_total_counts_only_socks5`（2→3），已改名为 `test_progress_tg_total_includes_mtproto`，并补了「http 类型不计入分母」的断言。

**新增测试**：`tests/test_mtproto_wiring.py` 共 15 例，其中最后一例是**不 mock 协议的真端到端**（本地 `MockMtproxyServer` → link 反解 → 真握手 → resPQ）。

**结果**：`pytest tests/` → **171 passed**（Phase 4 前 156，改名 1 例 + 新增 15 例）。

**实现要点**：
- `_secret_from_link` 从既有 `link` 反解，不新增 entry 字段，不动 `/api/proxies` 契约。
- `check_mtproto_proxy` 用 `try/except` 导入，缺失时降级为「未验证」而非启动失败。
- `tg_ok` 三态：`CRYPTO_UNAVAILABLE` / 无 secret → `None`，不计连败。
- 两关漏斗只收 SOCKS5；MTProto 单独进 `mtproto_gate_stats`。
