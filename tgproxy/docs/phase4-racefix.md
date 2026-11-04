# Phase 4 补丁：修复 AES 后端并发首次解析竞态

## 现象

Phase 4 接线后，用本地 `MockMtproxyServer` 做**冷启动并发**验证（20 个真节点、300 并发池），同一进程内反复跑：

```
trial0 stats={"tested": 3, "ok": 1, "fail": 0, "unavailable": 2}
       codes=[(61061, None, 'CRYPTO_UNAVAILABLE'), (61062, None, 'CRYPTO_UNAVAILABLE'), (61063, True, 'OK')]
trial1 stats={"tested": 3, "ok": 3, "fail": 0, "unavailable": 0}
trial2 stats={"tested": 3, "ok": 3, "fail": 0, "unavailable": 0}
```

只有**进程内第一次并发调用**会中招，之后完全正常；串行调用则一次都不出错。这个"只在首轮、只在并发时"的特征，在生产上极难复现，且会被误读成"打包漏了 cryptography"。

## 根因

`mtproto_handshake._aes_backend()` 是教科书式的错误双检锁：

```python
if _AES_RESOLVED:
    return _AES_BACKEND
_AES_RESOLVED = True          # ← 先置位
try:
    from cryptography...      # ← 首次导入要加载 OpenSSL DLL，几十~几百毫秒
    _AES_BACKEND = _new
```

线程 A 置位后进入 `import cryptography`（首次会加载 `libcrypto` DLL）。窗口期内线程 B、C 看到 `_AES_RESOLVED == True`，直接返回**还是 `None`** 的 `_AES_BACKEND`，`aes_ctr` 于是抛 `CRYPTO_UNAVAILABLE`。

并发度越高，中招越多；N 个并发首次调用里通常只有 1 个成功。

## 影响面

- 仅影响**进程启动后的第一轮刷新**的 MTProto 节点。
- `tg_ok=None` 是"无法判定"，**不计入 known_bad 连败**，所以不会误伤节点，只是白跑一轮。
- 但用户看到的是"刚启动时 MTProto 几乎全是未验证，跑第二轮才好"——最容易被归因成打包缺库。

## 修复

1. **锁 + 双检**：`_AES_LOCK = threading.Lock()`，进锁后再检查一次，避免重复解析。
2. **置位顺序倒转**：`_AES_BACKEND` 先赋值，`_AES_RESOLVED = True` **最后**置位。任何看到 "已解析" 的线程都能拿到终值（包括"确实没有可用后端"的 `None`），绝不会读到半成品状态。
3. 抽出 `_load_aes_factory()`（只做探测、不缓存），让竞态可被确定性测试覆盖。

## 验证

| 项 | 结果 |
|---|---|
| 新增 `tests/test_aes_backend_race.py` | 3 例：并发首次解析、置位不变量、解析结果缓存 |
| 冷启动 20 节点并发 | `{"tested": 20, "ok": 20, "fail": 0, "unavailable": 0}`，`server_errors=0` |
| 全量回归 | 174 passed（原 171 + 新增 3） |

修复前用同一脚本 3 次试验必现 `unavailable=2`；修复后 20/20 一次通过。

## 复现命令

```bash
cd tgproxy
python tests/test_aes_backend_race.py
python -m pytest tests -q
```
