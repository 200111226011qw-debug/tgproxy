# -*- coding: utf-8 -*-
"""实时进度（2026-10-28）。

Hero 卡片之前只有一个不确定态流光条——44 秒里用户看不到任何进展。
这里补上 done/total，并锁住三件事：

1. 阶段序列与计数正确（tcp/tg 各自 0→total）；
2. 中途能观测到中间态（0 < done < total），不是只有 0 和 total 两个值；
3. 无论成功还是异常，结束后必须回到 idle —— 否则界面会永久卡在一个
   不动的阶段上。
"""
import os
import sys
import threading
import time

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), os.pardir)))

import proxy_manager as pm  # noqa: E402


def _text(count=60):
    return "\n".join("socks5://10.0.0.%d:1080" % i for i in range(1, count + 1))


class _Patched:
    """慢速假件 + 小并发，让进度有足够时间被采样到。"""

    def __init__(self, tcp_delay=0.01, tg_delay=0.01, workers=2):
        self.tcp_delay = tcp_delay
        self.tg_delay = tg_delay
        self.workers = workers

    def __enter__(self):
        self._orig = (pm.fetch_proxy_text, pm.test_tcp_latency,
                      pm.test_telegram_via_socks5, pm.TEST_MAX_WORKERS,
                      pm.TG_TEST_MAX_WORKERS)
        text = _text()

        def fake_fetch(url, timeout=None):
            return text, False

        def fake_tcp(host, port, timeout=None):
            time.sleep(self.tcp_delay)
            # 偶数尾号通过 → 让 TG 阶段也有活干
            return 30 if int(host.rsplit(".", 1)[-1]) % 2 else None

        def fake_tg(host, port, dc_timeout=None, web_timeout=None,
                    deadline=None, sink=None, max_latency_ms=None):
            time.sleep(self.tg_delay)
            if sink is not None:
                sink({"stage": "ok", "dc_ms": 30.0, "web_ms": 40.0})
            return 40

        pm.fetch_proxy_text = fake_fetch
        pm.test_tcp_latency = fake_tcp
        pm.test_telegram_via_socks5 = fake_tg
        pm.TEST_MAX_WORKERS = self.workers
        pm.TG_TEST_MAX_WORKERS = self.workers
        return self

    def __exit__(self, *exc):
        (pm.fetch_proxy_text, pm.test_tcp_latency,
         pm.test_telegram_via_socks5, pm.TEST_MAX_WORKERS,
         pm.TG_TEST_MAX_WORKERS) = self._orig
        return False


def _run_and_sample(manager, interval=0.003, timeout=20):
    """在后台跑一轮，同时高频采样 progress。"""
    samples = []
    thread = threading.Thread(target=manager.refresh, daemon=True)
    thread.start()
    end = time.time() + timeout
    while thread.is_alive() and time.time() < end:
        samples.append(dict(manager.get_state()["progress"]))
        time.sleep(interval)
    thread.join(timeout)
    samples.append(dict(manager.get_state()["progress"]))
    return samples


def test_progress_idle_before_round():
    assert pm.ProxyManager().get_state()["progress"] == {
        "stage": "idle", "done": 0, "total": 0}


def test_progress_returns_to_idle_after_round():
    manager = pm.ProxyManager()
    with _Patched():
        manager.refresh()
    assert manager.get_state()["progress"]["stage"] == "idle"


def test_progress_returns_to_idle_on_exception():
    """抓取抛异常时也必须回到 idle，否则界面卡在一个不动的阶段上。"""
    manager = pm.ProxyManager()
    orig = pm.fetch_proxy_text

    def boom(url, timeout=None):
        raise RuntimeError("boom")

    pm.fetch_proxy_text = boom
    try:
        manager.refresh()
    finally:
        pm.fetch_proxy_text = orig
    assert manager.get_state()["progress"]["stage"] == "idle"
    assert manager.get_state()["error"]


def test_progress_reports_both_test_stages_with_intermediate_counts():
    manager = pm.ProxyManager()
    with _Patched():
        samples = _run_and_sample(manager)

    stages = {s["stage"] for s in samples}
    assert "tcp" in stages, "没观测到 TCP 阶段：%r" % (stages,)
    assert "tg" in stages, "没观测到 TG 阶段：%r" % (stages,)

    for stage, expect_total in (("tcp", 60), ("tg", 30)):
        rows = [s for s in samples if s["stage"] == stage]
        assert rows, stage
        for row in rows:
            assert row["total"] == expect_total, (stage, row)
            assert 0 <= row["done"] <= row["total"], (stage, row)
        # 中间态：不是只有 0 和 total
        assert any(0 < r["done"] < r["total"] for r in rows), (
            "%s 阶段没观测到中间态：%r" % (stage, sorted({r["done"] for r in rows})))
        # 同一阶段内 done 单调不减
        dones = [r["done"] for r in rows]
        assert dones == sorted(dones), "%s 阶段 done 回退了：%r" % (stage, dones)


def test_progress_tg_total_counts_only_socks5():
    """MTProto 不参与 TG 实测，不该计入进度分母。"""
    manager = pm.ProxyManager()
    entries = [
        {"host": "1.1.1.1", "port": 1080, "type": "socks5"},
        {"host": "2.2.2.2", "port": 1080, "type": "mtproto"},
        {"host": "3.3.3.3", "port": 1080, "type": "socks5"},
    ]
    seen = []
    orig = pm.test_telegram_via_socks5

    def slow(host, port, **kwargs):
        seen.append(dict(manager.get_state()["progress"]))
        time.sleep(0.01)
        return 40

    pm.test_telegram_via_socks5 = slow
    try:
        manager._test_telegram_entries(entries)
    finally:
        pm.test_telegram_via_socks5 = orig

    tg_rows = [s for s in seen if s["stage"] == "tg"]
    assert tg_rows, seen
    assert all(r["total"] == 2 for r in tg_rows), tg_rows


if __name__ == "__main__":
    import inspect
    fns = [(n, f) for n, f in sorted(globals().items())
           if n.startswith("test_") and inspect.isfunction(f)]
    for name, fn in fns:
        fn()
        print("PASS", name)
    print("ALL %d TESTS PASSED" % len(fns))
