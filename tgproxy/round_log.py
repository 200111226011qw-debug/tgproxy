"""轮次记录（rounds.jsonl）—— 与 Android 端 `RoundLogger` 同一份 JSONL 契约。

每轮刷新落一行 JSON 对象，最多保留 `ROUND_LOG_MAX_ENTRIES` 条。字段名、嵌套结构、
单位都与安卓端逐字对齐，两端日志可以直接放在一起比对。

为什么是 JSONL 而不是一个 JSON 数组：单行损坏只丢一行。数组格式下一个字符
截断就整份解析失败，历史全没了。

## 与 /api/proxies 的口径差异（**重要，别搞混**）

两边的字段集合已经一致（8 个），但 **`tg_failed` 同名不同义**：

- **日志** `stats.tg_failed`：两关失败，**不含**超门槛 —— 与安卓 `Stats.tgFailed` 同义
- **`/api/proxies`** `stats.tg_failed`：两关失败 **+** 超门槛 —— 历史口径，
  Web UI 按它算「四项之和 = 本轮测试数」，没动

`over_threshold` / `tested` 是后来补进 API 的（纯增量），所以日志侧现在直接取这两个
字段，不再自己算。两条恒等式分别锁住两边的口径：

    API  ：available + unverified + tg_failed              + dropped == tested
    日志 ：available + unverified + tg_failed + over_threshold + dropped == tested

两式相减即得 `API.tg_failed == 日志.tg_failed + over_threshold`。
"""

import json
import os
import time

# 保留条数：50 条按 10 分钟间隔约等于 8 小时历史，够看清「刚才那几轮为什么失败」，
# 又不至于让文件无限增长。与安卓端 Constants.ROUND_LOG_MAX_ENTRIES 一致。
ROUND_LOG_MAX_ENTRIES = 50

# 安卓端同样用这个格式（SimpleDateFormat "yyyy-MM-dd HH:mm:ss"）
TIME_FORMAT = "%Y-%m-%d %H:%M:%S"


def _atomic_write_text(path, text):
    """先写同目录临时文件再 os.replace：中断不会留下半份日志。"""
    tmp = path + ".tmp"
    try:
        with open(tmp, "w", encoding="utf-8") as fh:
            fh.write(text)
        os.replace(tmp, path)
    except OSError:
        try:
            if os.path.exists(tmp):
                os.remove(tmp)
        except OSError:
            pass


def load_rounds(path):
    """读取全部记录（旧 → 新）。文件缺失/损坏/单行坏掉都只跳过，不整体失败。"""
    if not path or not os.path.isfile(path):
        return []
    try:
        with open(path, "r", encoding="utf-8") as fh:
            raw = fh.read()
    except OSError:
        return []
    out = []
    for line in raw.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            obj = json.loads(line)
        except (ValueError, TypeError):
            continue
        if not isinstance(obj, dict):
            continue
        if not obj.get("ts"):
            continue
        out.append(obj)
    return out


def append_round(path, record, max_entries=ROUND_LOG_MAX_ENTRIES):
    """追加一条记录，返回追加后的完整列表（旧 → 新，已截断）。

    写失败静默：记日志不该影响刷新结果。失败时仍返回内存里的列表，
    界面照常显示 —— 只是重启后会丢。
    """
    if not path:
        return []
    existing = load_rounds(path)
    # seq 单调递增：界面拿它当列表 key。用「现有条数 + 1」在截断后会重号，
    # 必须取历史最大值 + 1。
    next_seq = max((int(r.get("seq") or 0) for r in existing), default=0) + 1
    item = dict(record)
    item["seq"] = next_seq
    merged = (existing + [item])[-max_entries:]
    _atomic_write_text(
        path,
        "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in merged),
    )
    return merged


def build_record(ok, error, elapsed_ms, stats, tg_stats, stage_durations, now=None):
    """组装一条轮次记录（纯函数，便于单测）。

    :param ok: 流程是否走通。**全源抓取失败算失败**（与安卓端一致：
               那种情况在安卓侧是抛异常 → Failure）。「本轮 0 达标但保留了
               旧列表」算成功，原因放进 `error` 作为提示。
    :param error: 失败原因或成功轮的提示文案
    :param elapsed_ms: 整轮耗时（毫秒，独立测量，不是五段相加）
    :param stats: 已按**日志口径**换算好的 8 个字段（见模块注释）
    :param tg_stats: {"dc_fail", "latency_rejected", "web_fail", "ok"}
    :param stage_durations: 秒为单位的阶段耗时 {"fetch","prepare","tcp","tg","commit","total"}
    """
    stamp = time.strftime(TIME_FORMAT, time.localtime(now)) if now is not None \
        else time.strftime(TIME_FORMAT)
    return {
        "ts": stamp,
        "ok": bool(ok),
        "error": error,
        "elapsed_ms": int(round(elapsed_ms)),
        "stats": {
            "available": int(stats.get("available", 0)),
            "unverified": int(stats.get("unverified", 0)),
            "tg_failed": int(stats.get("tg_failed", 0)),
            "over_threshold": int(stats.get("over_threshold", 0)),
            "dropped": int(stats.get("dropped", 0)),
            "excluded": int(stats.get("excluded", 0)),
            "blacklisted": int(stats.get("blacklisted", 0)),
            "tested": int(stats.get("tested", 0)),
        },
        "tg": {
            "dc_fail": int(tg_stats.get("dc_fail", 0)),
            "latency_rejected": int(tg_stats.get("latency_rejected", 0)),
            "web_fail": int(tg_stats.get("web_fail", 0)),
            "ok": int(tg_stats.get("ok", 0)),
        },
        "durations": {
            key + "_ms": int(round(float(stage_durations.get(key, 0.0)) * 1000.0))
            for key in ("fetch", "prepare", "tcp", "tg", "commit", "total")
        },
    }
