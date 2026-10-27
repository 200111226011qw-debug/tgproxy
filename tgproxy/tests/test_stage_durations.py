# -*- coding: utf-8 -*-
"""分阶段耗时埋点（2026-10-28）。

目的：P2（流水线化）要不要做，取决于 TCP 与 TG 各占多少。没有这个度量，
做完 P2 只能说"感觉快了"，无法证明。

纯度量、零行为改变：这些数字不参与任何判定。

验收口径（用户定的）：fetch + prepare + tcp + tg + commit 之和必须
≈ total，差额只允许记账误差与未计时的间隙。
"""
import os
import sys
import time

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), os.pardir)))

import proxy_manager as pm  # noqa: E402

KEYS = ("fetch", "prepare", "tcp", "tg", "commit", "total")


def _text(count=20):
    return "\n".join("socks5://10.0.0.%d:1080" % i for i in range(1, count + 1))


class _Patched:
    """把三个网络入口换成可控延时的假件，退出时还原。"""

    def __init__(self, fetch_delay=0.02, tcp_delay=0.05, tg_delay=0.03):
        self.fetch_delay = fetch_delay
        self.tcp_delay = tcp_delay
        self.tg_delay = tg_delay

    def __enter__(self):
        self._orig = (pm.fetch_proxy_text, pm.test_tcp_latency,
                      pm.test_telegram_via_socks5)
        text = _text()

        def fake_fetch(url, timeout=None):
            time.sleep(self.fetch_delay)
            return text, False

        def fake_tcp(host, port, timeout=None):
            time.sleep(self.tcp_delay)
            # 偶数尾号判死：制造一批 dropped，让 TCP/TG 两阶段都有量
            return 30 if int(host.rsplit(".", 1)[-1]) % 2 else None

        def fake_tg(host, port, timeout=None, deadline=None, sink=None,
                    max_latency_ms=None):
            time.sleep(self.tg_delay)
            if sink is not None:
                sink({"stage": "ok", "dc_ms": 30.0, "web_ms": 40.0})
            return 40

        pm.fetch_proxy_text = fake_fetch
        pm.test_tcp_latency = fake_tcp
        pm.test_telegram_via_socks5 = fake_tg
        return self

    def __exit__(self, *exc):
        (pm.fetch_proxy_text, pm.test_tcp_latency,
         pm.test_telegram_via_socks5) = self._orig
        return False


def test_stage_durations_sum_matches_total():
    manager = pm.ProxyManager()
    with _Patched():
        assert manager.refresh() is True
    stages = manager.get_state()["stage_durations"]

    for key in KEYS:
        assert key in stages, "缺少阶段 %s：%r" % (key, stages)
        assert stages[key] >= 0, "%s 不应为负" % key

    # 假件各睡 0.02/0.05/0.03，证明计时器真的在量，不是恒 0
    assert stages["fetch"] >= 0.015, stages
    assert stages["tcp"] >= 0.04, stages
    assert stages["tg"] >= 0.02, stages

    parts = sum(stages[k] for k in ("fetch", "prepare", "tcp", "tg", "commit"))
    assert abs(stages["total"] - parts) < 0.05, (
        "各阶段之和 %.3f 与总耗时 %.3f 差得太多" % (parts, stages["total"]))


def test_stage_durations_are_per_round_not_cumulative():
    manager = pm.ProxyManager()
    with _Patched():
        manager.refresh()
        first = dict(manager.get_state()["stage_durations"])
        manager.refresh()
        second = dict(manager.get_state()["stage_durations"])

    # 每轮重置：第二轮不该是第一轮的两倍
    assert second["total"] < first["total"] * 1.8, (
        "分阶段耗时在累加：第一轮 %.3f，第二轮 %.3f" % (first["total"], second["total"]))
    assert second["tcp"] < first["tcp"] * 2.0


def test_stage_durations_empty_before_first_round():
    assert pm.ProxyManager().get_state()["stage_durations"] == {}


def test_stage_durations_survive_zero_available():
    """全死节点（0 达标）时也要有完整的阶段记录，不能因为没结果就缺失。"""
    manager = pm.ProxyManager()

    def fake_fetch(url, timeout=None):
        return _text(), False

    def fake_tcp(host, port, timeout=None):
        return None          # 全部 TCP 不通

    def fake_tg(host, port, timeout=None, deadline=None, sink=None,
                max_latency_ms=None):
        raise AssertionError("TG 阶段不该被调用：没有节点通过 TCP")

    orig = (pm.fetch_proxy_text, pm.test_tcp_latency, pm.test_telegram_via_socks5)
    pm.fetch_proxy_text, pm.test_tcp_latency, pm.test_telegram_via_socks5 = (
        fake_fetch, fake_tcp, fake_tg)
    try:
        manager.refresh()
    finally:
        (pm.fetch_proxy_text, pm.test_tcp_latency,
         pm.test_telegram_via_socks5) = orig

    stages = manager.get_state()["stage_durations"]
    for key in KEYS:
        assert key in stages, stages
    assert stages["total"] > 0
    assert manager.get_state()["stats"]["available"] == 0


if __name__ == "__main__":
    import inspect
    fns = [(n, f) for n, f in sorted(globals().items())
           if n.startswith("test_") and inspect.isfunction(f)]
    for name, fn in fns:
        fn()
        print("PASS", name)
    print("ALL %d TESTS PASSED" % len(fns))
