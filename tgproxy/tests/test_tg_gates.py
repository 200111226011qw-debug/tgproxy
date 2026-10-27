# -*- coding: utf-8 -*-
"""TG 两关各自的耗时分布（2026-10-28）。

为什么需要：TG 阶段占整轮 62%（61s / 98s），但不知道这 61 秒花在哪一关。
失败节点的耗时在返回值里是拿不到的（函数返回 None），所以必须在两关外面
各自计时，并用可选 sink 回调收集。

要回答三个问题：
1. 多少节点卡在第一关（DC CONNECT），多少卡在第二关（TLS+HTTP）；
2. 成功节点的两关各花多久；
3. 失败节点的耗时是不是恰好顶在超时上限。
"""
import os
import sys

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), os.pardir)))

import proxy_manager as pm  # noqa: E402


def _entry(host, port=1080, type_="socks5"):
    return {"host": host, "port": port, "type": type_}


class _PatchedGates:
    def __init__(self, dc, web):
        self.dc = dc
        self.web = web

    def __enter__(self):
        self._orig = (pm.test_socks5_telegram_dc, pm.test_telegram_web_via_socks5)
        pm.test_socks5_telegram_dc = self.dc
        pm.test_telegram_web_via_socks5 = self.web
        return self

    def __exit__(self, *exc):
        (pm.test_socks5_telegram_dc,
         pm.test_telegram_web_via_socks5) = self._orig
        return False


# --------------------------------------------------------------------------
# 汇总函数
# --------------------------------------------------------------------------

def test_summarize_counts_by_stage():
    samples = [
        {"stage": "dc_fail", "dc_ms": 8000.0, "web_ms": None},
        {"stage": "dc_fail", "dc_ms": 7990.0, "web_ms": None},
        {"stage": "dc_fail", "dc_ms": 8010.0, "web_ms": None},
        {"stage": "web_fail", "dc_ms": 300.0, "web_ms": 5000.0},
        {"stage": "ok", "dc_ms": 200.0, "web_ms": 800.0},
        {"stage": "ok", "dc_ms": 250.0, "web_ms": 900.0},
    ]
    s = pm.summarize_tg_gates(samples)

    assert s["total"] == 6
    assert s["dc_fail"]["count"] == 3
    assert s["web_fail"]["count"] == 1
    assert s["ok"]["count"] == 2
    assert s["deadline"]["count"] == 0

    # 失败节点的耗时顶在超时上限 —— 这正是「该降超时」的证据
    assert s["dc_fail"]["dc_p50"] == 8000.0
    assert s["dc_fail"]["dc_max"] == 8010.0
    # 没经过第二关的，不该出现 web_* 键
    assert "web_p50" not in s["dc_fail"]

    assert s["ok"]["dc_max"] == 250.0
    assert s["ok"]["web_max"] == 900.0
    assert s["web_fail"]["web_max"] == 5000.0


def test_summarize_empty_is_safe():
    s = pm.summarize_tg_gates([])
    assert s["total"] == 0
    for stage in ("dc_fail", "deadline", "latency_rejected", "web_fail", "ok"):
        assert s[stage]["count"] == 0
        assert "dc_p50" not in s[stage]


# --------------------------------------------------------------------------
# 剪枝：dc_ms 超门槛时跳过第二关（等价变换）
# --------------------------------------------------------------------------

def test_prune_skips_web_gate_when_dc_exceeds_threshold():
    def web_must_not_run(h, p, t=None, **k):
        raise AssertionError("dc_ms 已超门槛，第二关不该被调用")

    records = []
    with _PatchedGates(lambda h, p, t=None, **k: 2000,
                       web_must_not_run):
        value = pm.test_telegram_via_socks5(
            "1.1.1.1", 1080, sink=records.append, max_latency_ms=1000)

    # 返回值是 dc_ms 而不是 None：下游会当成「过了但超门槛」，
    # 从而 tg_failed 计数与「通过则清零连败」的语义保持不变
    assert value == 2000
    assert len(records) == 1
    assert records[0]["stage"] == "latency_rejected"
    assert records[0]["web_ms"] is None


def test_prune_boundary_equal_is_not_pruned():
    """dc_ms == 门槛时应当继续跑第二关（门槛判定用的是 <=）。"""
    called = []

    def web(h, p, t=None, **k):
        called.append(1)
        return 300

    with _PatchedGates(lambda h, p, t=None, **k: 1000, web):
        value = pm.test_telegram_via_socks5(
            "1.1.1.1", 1080, max_latency_ms=1000)
    assert called == [1]
    assert value == 1000


def test_no_prune_when_max_latency_absent():
    """不传门槛时保持旧行为（不剪枝）。"""
    called = []

    def web(h, p, t=None, **k):
        called.append(1)
        return 300

    with _PatchedGates(lambda h, p, t=None, **k: 5000, web):
        value = pm.test_telegram_via_socks5("1.1.1.1", 1080)
    assert called == [1]
    assert value == 5000


def test_dc_and_web_budgets_are_independent():
    """第一关预算随门槛派生，第二关预算固定 —— 两者不能共用一个值。"""
    dc_calls, web_calls = [], []

    def fake_dc(h, p, t=None, **k):
        dc_calls.append(t)
        return 700

    def fake_web(h, p, t=None, **k):
        web_calls.append(t)
        return 300

    with _PatchedGates(fake_dc, fake_web):
        pm.test_telegram_via_socks5("1.1.1.1", 1080, max_latency_ms=1000)
        pm.test_telegram_via_socks5("1.1.1.1", 1080, max_latency_ms=5000)

    assert dc_calls == [2.0, 6.0], "第一关预算没随门槛变：%r" % (dc_calls,)
    # 第二关实测 web_p50 就有 2~3s，绝不能跟着门槛缩到 2s
    assert web_calls == [8.0, 8.0], "第二关预算不该受门槛影响：%r" % (web_calls,)


def test_deadline_expands_for_high_threshold():
    """高门槛下 deadline 必须跟着两关预算扩展，否则第一关永远跑不完。"""
    assert pm.tg_deadline_for(2.0, 8.0) == 20.0     # 下限
    assert pm.tg_deadline_for(61.0, 8.0) == 71.0    # 门槛 60s 时
    assert pm.tg_deadline_for(6.0, 8.0) == 20.0     # 6+8+2=16 < 20


def test_manager_marks_pruned_node_as_over_threshold_not_failed():
    """剪枝节点必须 tg_ok=True（不误增连败）、tg_latency=dc_ms（不被 kept）。"""
    manager = pm.ProxyManager(max_tg_latency_ms=1000)

    def web_must_not_run(h, p, t=None, **k):
        raise AssertionError("剪枝后不该调用第二关")

    with _PatchedGates(lambda h, p, t=None, **k: 2000, web_must_not_run):
        results = manager._test_telegram_entries([_entry("1.1.1.1")])

    assert len(results) == 1
    assert results[0]["tg_ok"] is True
    assert results[0]["tg_latency_ms"] == 2000
    stats = manager.get_state()["tg_gate_stats"]
    assert stats["latency_rejected"]["count"] == 1
    assert stats["web_fail"]["count"] == 0


# --------------------------------------------------------------------------
# sink 不改变语义
# --------------------------------------------------------------------------

def test_sink_does_not_change_return_value():
    with _PatchedGates(lambda h, p, t=None, **k: 150,
                       lambda h, p, t=None, **k: 400):
        assert pm.test_telegram_via_socks5("1.1.1.1", 1080) == 150
        assert pm.test_telegram_via_socks5("1.1.1.1", 1080, sink=lambda r: None) == 150
    with _PatchedGates(lambda h, p, t=None, **k: 150,
                       lambda h, p, t=None, **k: None):
        assert pm.test_telegram_via_socks5("1.1.1.1", 1080) is None
        assert pm.test_telegram_via_socks5("1.1.1.1", 1080, sink=lambda r: None) is None
    with _PatchedGates(lambda h, p, t=None, **k: None,
                       lambda h, p, t=None, **k: 400):
        assert pm.test_telegram_via_socks5("1.1.1.1", 1080) is None
        assert pm.test_telegram_via_socks5("1.1.1.1", 1080, sink=lambda r: None) is None


def test_sink_receives_one_record_per_node():
    records = []
    with _PatchedGates(lambda h, p, t=None, **k: None,
                       lambda h, p, t=None, **k: 400):
        pm.test_telegram_via_socks5("1.1.1.1", 1080, sink=records.append)
    assert len(records) == 1
    assert records[0]["stage"] == "dc_fail"
    assert records[0]["web_ms"] is None


# --------------------------------------------------------------------------
# 管理器侧：一次 TG 阶段跑完，分布落到 state
# --------------------------------------------------------------------------

def test_manager_records_gate_stats():
    manager = pm.ProxyManager()
    entries = [_entry("1.1.1.1"), _entry("2.2.2.2"), _entry("3.3.3.3")]

    def fake_dc(host, port, timeout=None, **k):
        return None if host == "1.1.1.1" else 120      # 1 号卡第一关

    def fake_web(host, port, timeout=None, **k):
        return None if host == "2.2.2.2" else 300      # 2 号卡第二关

    with _PatchedGates(fake_dc, fake_web):
        manager._test_telegram_entries(entries)

    stats = manager.get_state()["tg_gate_stats"]
    assert stats["total"] == 3, stats
    assert stats["dc_fail"]["count"] == 1, stats
    assert stats["web_fail"]["count"] == 1, stats
    assert stats["ok"]["count"] == 1, stats


def test_manager_clears_gate_stats_when_no_entries():
    manager = pm.ProxyManager()
    with _PatchedGates(lambda h, p, t=None, **k: 100,
                       lambda h, p, t=None, **k: 200):
        manager._test_telegram_entries([_entry("1.1.1.1")])
        assert manager.get_state()["tg_gate_stats"]["total"] == 1
        manager._test_telegram_entries([])
    assert manager.get_state()["tg_gate_stats"] == {}


def test_mtproto_entries_are_not_counted_as_gates():
    """MTProto 不参与 TG 实测，不该出现在两关分布里（否则分母被污染）。"""
    manager = pm.ProxyManager()
    entries = [_entry("1.1.1.1", type_="socks5"),
               _entry("2.2.2.2", type_="mtproto")]
    with _PatchedGates(lambda h, p, t=None, **k: 120,
                       lambda h, p, t=None, **k: 300):
        results = manager._test_telegram_entries(entries)
    stats = manager.get_state()["tg_gate_stats"]
    assert stats["total"] == 1
    assert stats["ok"]["count"] == 1
    assert len(results) == 2   # 两个都返回，MTProto 标为未验证
    assert [r["tg_ok"] for r in results] == [True, None]


if __name__ == "__main__":
    import inspect
    fns = [(n, f) for n, f in sorted(globals().items())
           if n.startswith("test_") and inspect.isfunction(f)]
    for name, fn in fns:
        fn()
        print("PASS", name)
    print("ALL %d TESTS PASSED" % len(fns))
