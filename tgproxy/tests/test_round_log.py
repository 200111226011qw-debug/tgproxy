# -*- coding: utf-8 -*-
"""轮次记录 rounds.jsonl（2026-10-28，与安卓端同一份契约）。

验收口径：
1. 一行一轮，字段名/嵌套/单位与安卓端逐字对齐（`*_ms` 全是毫秒）；
2. 坏行只丢一行，不整体失败；
3. 超限截断，且 seq 继续单调（截断后重号会让界面列表 key 撞车）；
4. 日志的 `stats.tg_failed` 是**两关失败、不含超门槛**，并能用恒等式自证：
   available + unverified + tg_failed + over_threshold + dropped == tested
5. 全源抓取失败算失败轮；本轮 0 达标但保留旧列表算成功轮（带提示）。
"""
import json
import os
import sys
import tempfile
import time

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), os.pardir)))

import proxy_manager as pm  # noqa: E402
import round_log  # noqa: E402


def _tmp_path(name="rounds.jsonl"):
    handle, path = tempfile.mkstemp(suffix=".jsonl")
    os.close(handle)
    os.remove(path)  # 让实现自己创建
    return path


# ----------------------------------------------------------------------
# 存储层
# ----------------------------------------------------------------------

def test_build_record_units_and_keys():
    record = round_log.build_record(
        ok=True,
        error=None,
        elapsed_ms=44760.4,
        stats={"available": 11, "unverified": 0, "tg_failed": 260,
               "over_threshold": 75, "dropped": 1200, "excluded": 3,
               "blacklisted": 40, "tested": 1546},
        tg_stats={"dc_fail": 1200, "latency_rejected": 75, "web_fail": 260, "ok": 11},
        stage_durations={"fetch": 16.6, "prepare": 0.9, "tcp": 8.4,
                         "tg": 27.6, "commit": 0.2, "total": 53.7},
    )
    assert record["ok"] is True
    assert record["elapsed_ms"] == 44760
    assert record["stats"]["tested"] == 1546
    assert record["tg"]["latency_rejected"] == 75
    # 阶段耗时从秒换算成毫秒，与安卓端一致
    assert record["durations"] == {
        "fetch_ms": 16600, "prepare_ms": 900, "tcp_ms": 8400,
        "tg_ms": 27600, "commit_ms": 200, "total_ms": 53700,
    }
    assert record["ts"]  # 时间戳非空


def test_append_and_load_roundtrip():
    path = _tmp_path()
    first = round_log.build_record(
        ok=True, error=None, elapsed_ms=1000,
        stats={"available": 1, "tested": 10},
        tg_stats={"ok": 1}, stage_durations={"total": 1.0},
    )
    round_log.append_round(path, first)
    merged = round_log.append_round(path, dict(first, ok=False, error="boom"))

    assert len(merged) == 2
    assert merged[0]["seq"] == 1
    assert merged[1]["seq"] == 2

    reloaded = round_log.load_rounds(path)
    assert [r["seq"] for r in reloaded] == [1, 2]
    assert reloaded[1]["ok"] is False
    assert reloaded[1]["error"] == "boom"

    # 每行一个对象 —— JSONL 形态本身是契约
    with open(path, "r", encoding="utf-8") as fh:
        lines = [ln for ln in fh.read().splitlines() if ln.strip()]
    assert len(lines) == 2
    assert all(json.loads(ln) for ln in lines)


def test_corrupt_line_is_skipped():
    path = _tmp_path()
    with open(path, "w", encoding="utf-8") as fh:
        fh.write('{"ts":"2026-10-28 03:00:00","ok":true,"elapsed_ms":1000}\n')
        fh.write('{"ts":"2026-10-28 03:0\n')       # 半行截断
        fh.write('not json at all\n')              # 不是对象
        fh.write('[1,2,3]\n')                      # 是 JSON 但不是对象
        fh.write('{"ok":true}\n')                  # 没有 ts，无法定位
        fh.write('{"ts":"2026-10-28 03:20:00","ok":false,"error":"x"}\n')

    loaded = round_log.load_rounds(path)
    assert len(loaded) == 2
    assert loaded[0]["ts"] == "2026-10-28 03:00:00"
    assert loaded[1]["ts"] == "2026-10-28 03:20:00"


def test_truncate_keeps_latest_and_seq_monotonic():
    path = _tmp_path()
    base = round_log.build_record(
        ok=True, error=None, elapsed_ms=1,
        stats={}, tg_stats={}, stage_durations={},
    )
    for i in range(5):
        round_log.append_round(path, dict(base, ts="2026-10-28 03:0%d:00" % i),
                               max_entries=3)

    loaded = round_log.load_rounds(path)
    assert len(loaded) == 3
    assert [r["ts"] for r in loaded] == [
        "2026-10-28 03:02:00", "2026-10-28 03:03:00", "2026-10-28 03:04:00",
    ]
    # 截断后 seq 不能回头（用「现有条数 + 1」会变成 4,4,4）
    assert [r["seq"] for r in loaded] == [3, 4, 5]

    merged = round_log.append_round(path, base, max_entries=3)
    assert merged[-1]["seq"] == 6


def test_missing_file_and_empty_path():
    assert round_log.load_rounds(_tmp_path()) == []
    assert round_log.load_rounds(None) == []
    # 路径为空 = 不记录，且不能抛
    assert round_log.append_round(None, {"ts": "x"}) == []


# ----------------------------------------------------------------------
# 与 ProxyManager 的集成
# ----------------------------------------------------------------------

def _text(count=20):
    return "\n".join("socks5://10.0.0.%d:1080" % i for i in range(1, count + 1))


class _Patched:
    """网络入口全换假件：偶数尾号 TCP 死，奇数尾号 TG 通过但一半超门槛。"""

    def __init__(self, fetch_fail=False):
        self.fetch_fail = fetch_fail

    def __enter__(self):
        self._orig = (pm.fetch_proxy_text, pm.test_tcp_latency,
                      pm.test_telegram_via_socks5)
        text = _text()

        def fake_fetch(url, timeout=None):
            if self.fetch_fail:
                raise OSError("blocked")
            return text, False

        def fake_tcp(host, port, timeout=None):
            return 30 if int(host.rsplit(".", 1)[-1]) % 2 else None

        def fake_tg(host, port, timeout=None, deadline=None, sink=None,
                    max_latency_ms=None):
            tail = int(host.rsplit(".", 1)[-1])
            # 尾号 1、5、9… 这类走超门槛分支，制造 over_threshold > 0
            latency = 1500 if tail % 4 == 1 else 40
            if sink is not None:
                sink({"stage": "ok", "dc_ms": 30.0, "web_ms": 40.0})
            return latency

        pm.fetch_proxy_text = fake_fetch
        pm.test_tcp_latency = fake_tcp
        pm.test_telegram_via_socks5 = fake_tg
        return self

    def __exit__(self, *exc):
        (pm.fetch_proxy_text, pm.test_tcp_latency,
         pm.test_telegram_via_socks5) = self._orig
        return False


def test_round_record_satisfies_invariant():
    path = _tmp_path()
    manager = pm.ProxyManager(rounds_path=path)
    with _Patched():
        assert manager.refresh() is True

    rounds = round_log.load_rounds(path)
    assert len(rounds) == 1
    rec = rounds[0]
    assert rec["ok"] is True
    s = rec["stats"]
    # 恒等式：五类互斥且穷尽本轮测过的节点
    assert (s["available"] + s["unverified"] + s["tg_failed"]
            + s["over_threshold"] + s["dropped"]) == s["tested"]
    # 本轮确实制造了超门槛，否则上面的恒等式是空转
    assert s["over_threshold"] > 0
    # 日志的 tg_failed 不含超门槛：API 的 tg_failed = 两关未过 + 超门槛
    assert manager.get_state()["stats"]["tg_failed"] == s["tg_failed"] + s["over_threshold"]
    # 漏斗四桶之和 == 走过 TG 的节点数 == tested - dropped
    tg = rec["tg"]
    assert tg["dc_fail"] + tg["latency_rejected"] + tg["web_fail"] + tg["ok"] \
        == s["tested"] - s["dropped"]
    assert rec["durations"]["total_ms"] > 0


def test_all_sources_failed_is_recorded_as_failure():
    path = _tmp_path()
    manager = pm.ProxyManager(rounds_path=path)
    with _Patched(fetch_fail=True):
        assert manager.refresh() is True

    rec = round_log.load_rounds(path)[-1]
    assert rec["ok"] is False, "全源抓取失败应当记成失败轮（与安卓端一致）"
    assert "抓取失败" in (rec["error"] or "")


def test_exception_round_is_recorded_without_stats():
    path = _tmp_path()
    manager = pm.ProxyManager(rounds_path=path)
    orig = pm.parse_proxy_text
    pm.parse_proxy_text = lambda text: (_ for _ in ()).throw(RuntimeError("解析炸了"))
    try:
        with _Patched():
            assert manager.refresh() is True
    finally:
        pm.parse_proxy_text = orig

    rec = round_log.load_rounds(path)[-1]
    assert rec["ok"] is False
    assert "解析炸了" in (rec["error"] or "")
    # 异常轮统计归零（与安卓端失败轮一致），不拿上一轮的数字充数
    assert rec["stats"]["tested"] == 0
    assert rec["stats"]["available"] == 0
    assert rec["durations"]["total_ms"] == 0


def test_state_exposes_recent_rounds():
    path = _tmp_path()
    manager = pm.ProxyManager(rounds_path=path)
    with _Patched():
        manager.refresh()
        manager.refresh()

    state = manager.get_state()
    assert len(state["rounds"]) == 2
    assert state["rounds"][-1]["seq"] == 2


def test_no_rounds_path_means_no_file():
    manager = pm.ProxyManager()
    with _Patched():
        assert manager.refresh() is True
    assert manager.get_state()["rounds"] == []
