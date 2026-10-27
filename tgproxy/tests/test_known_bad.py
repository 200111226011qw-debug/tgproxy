# -*- coding: utf-8 -*-
"""连续失败拉黑回归测试（v1.9.0，与安卓 v2.1.0 对齐，共 8 个用例）。

既可用 pytest 运行（python -m pytest tgproxy/tests -q），也可直接执行本文件。
"""
import json
import os
import sys
import tempfile

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), os.pardir)))
import proxy_manager as pm  # noqa: E402
from proxy_manager import (  # noqa: E402
    KNOWN_BAD_EXPIRY_SEC,
    KNOWN_BAD_MAX_ENTRIES,
    KNOWN_BAD_STREAK_LIMIT,
    ProxyManager,
)


def make_manager(tmpdir, name, **kwargs):
    now = [1_000_000.0]
    defaults = dict(
        known_good_path=os.path.join(tmpdir, "kg_%s.json" % name),
        known_bad_path=os.path.join(tmpdir, "kb_%s.json" % name),
        _clock=lambda: now[0],
    )
    defaults.update(kwargs)
    return ProxyManager(**defaults), now


def test_streak_reaches_limit_then_blacklisted():
    m, _now = make_manager(tempfile.mkdtemp(), "streak")
    for i in range(KNOWN_BAD_STREAK_LIMIT - 1):
        m._record_bad_fail("1.2.3.4", 1080)
        assert not m._is_blacklisted("1.2.3.4", 1080)
    m._record_bad_fail("1.2.3.4", 1080)
    assert m._is_blacklisted("1.2.3.4", 1080)


def test_pass_clears_streak():
    m, _now = make_manager(tempfile.mkdtemp(), "clear")
    m._record_bad_fail("1.2.3.4", 1080)
    m._record_bad_fail("1.2.3.4", 1080)
    m._record_bad_pass("1.2.3.4", 1080)
    m._record_bad_fail("1.2.3.4", 1080)
    assert not m._is_blacklisted("1.2.3.4", 1080)


def test_expiry_unblocks_after_48h():
    m, now = make_manager(tempfile.mkdtemp(), "expiry")
    for _ in range(KNOWN_BAD_STREAK_LIMIT):
        m._record_bad_fail("1.2.3.4", 1080)
    assert m._is_blacklisted("1.2.3.4", 1080)
    now[0] += KNOWN_BAD_EXPIRY_SEC - 60
    assert m._is_blacklisted("1.2.3.4", 1080)
    now[0] += 120
    assert not m._is_blacklisted("1.2.3.4", 1080)


def test_persist_reload_and_corrupt_tolerance():
    d = tempfile.mkdtemp()
    kb = os.path.join(d, "kb_persist.json")
    m, _now = make_manager(d, "persist")
    for _ in range(KNOWN_BAD_STREAK_LIMIT):
        m._record_bad_fail("5.6.7.8", 1080)
    m._save_known_bad()
    assert os.path.isfile(kb)

    reloaded, _now2 = make_manager(d, "persist")
    assert reloaded._is_blacklisted("5.6.7.8", 1080)
    assert not reloaded._is_blacklisted("9.9.9.9", 1080)

    with open(kb, "w", encoding="utf-8") as fh:
        fh.write("not-json{")
    corrupt, _now3 = make_manager(d, "persist")
    assert corrupt._known_bad == {}


def test_capacity_evicts_oldest_fail():
    m, now = make_manager(tempfile.mkdtemp(), "cap")
    m._record_bad_fail("1.0.0.1", 1080)
    now[0] += 1
    for i in range(2, KNOWN_BAD_MAX_ENTRIES + 2):
        m._record_bad_fail("1.0.0.%d" % i, 1080)
        now[0] += 1
    m._save_known_bad()
    assert len(m._known_bad) == KNOWN_BAD_MAX_ENTRIES
    assert "1.0.0.1:1080" not in m._known_bad
    assert "1.0.0.%d:1080" % (KNOWN_BAD_MAX_ENTRIES + 1) in m._known_bad


def test_full_refresh_blacklists_after_three_rounds(monkeypatch):
    """整链路：连续 3 轮 TG 全不过 -> 第 4 轮拉黑跳过测试；48 小时后解封重测。"""
    d = tempfile.mkdtemp()
    m, now = make_manager(d, "e2e")
    monkeypatch.setattr(
        pm, "fetch_proxy_text",
        lambda url, timeout=15: ("socks5://7.7.7.7:1080\nsocks5://7.7.7.8:1080", False))
    monkeypatch.setattr(pm, "test_tcp_latency", lambda host, port, timeout=5: 50.0)
    monkeypatch.setattr(pm, "test_telegram_via_socks5",
                        lambda host, port, sink=None: None)

    for _ in range(3):
        m.refresh()
    stats = m.get_state()["stats"]
    assert stats["blacklisted"] == 0
    assert stats["tg_failed"] == 2  # stats 为最后一轮口径：每轮 2 个全 TG 失败

    m.refresh()
    stats = m.get_state()["stats"]
    assert stats["blacklisted"] == 2
    assert stats["tg_failed"] == 0  # 本轮没有任何节点参与测试
    assert stats["dropped"] == 0
    # 拉黑表已落盘
    with open(os.path.join(d, "kb_e2e.json"), encoding="utf-8") as fh:
        persisted = json.load(fh)
    assert len(persisted) == 2
    assert all(v["fails"] >= KNOWN_BAD_STREAK_LIMIT for v in persisted.values())


def test_full_refresh_pass_clears_and_over_threshold_counts_pass(monkeypatch):
    """整链路：失败两轮后过闸（含超门槛）清零连败；继续失败从 0 重计不误拉黑。"""
    d = tempfile.mkdtemp()
    m, _now = make_manager(d, "reset")
    monkeypatch.setattr(
        pm, "fetch_proxy_text",
        lambda url, timeout=15: ("socks5://7.7.7.7:1080", False))
    monkeypatch.setattr(pm, "test_tcp_latency", lambda host, port, timeout=5: 50.0)
    fail = {"ok": False}
    monkeypatch.setattr(
        pm, "test_telegram_via_socks5",
        lambda host, port, sink=None: None if not fail["ok"] else 1500)  # 过两关但超门槛

    m.refresh()
    m.refresh()
    fail["ok"] = True  # 第三轮：超门槛但两关通过 -> 清零
    m.refresh()
    fail["ok"] = False
    m.refresh()
    stats = m.get_state()["stats"]
    assert stats["blacklisted"] == 0
    assert stats["tg_failed"] == 1

    m.refresh()  # 连败仅 2，不拉黑
    stats = m.get_state()["stats"]
    assert stats["blacklisted"] == 0
    assert stats["tg_failed"] == 1


def test_blacklist_skips_even_known_good_entries(monkeypatch):
    """拉黑优先于 known_good 补入：连续失败的沉淀节点同样跳过测试。"""
    d = tempfile.mkdtemp()
    m, _now = make_manager(d, "kg")
    monkeypatch.setattr(
        pm, "fetch_proxy_text", lambda url, timeout=15: ("", False))
    monkeypatch.setattr(pm, "test_tcp_latency", lambda host, port, timeout=5: 50.0)
    monkeypatch.setattr(pm, "test_telegram_via_socks5",
                        lambda host, port, sink=None: None)
    # 沉淀了一个节点（须带 tg_ok=True 才会真正写入）：每轮靠 known_good 补入复测，但 TG 恒不过
    m._save_known_good([{"host": "3.3.3.3", "port": 1080, "tg_ok": True,
                         "tg_latency_ms": 400}])
    for _ in range(KNOWN_BAD_STREAK_LIMIT):
        m.refresh()
    m.refresh()
    stats = m.get_state()["stats"]
    assert stats["blacklisted"] == 1
    assert stats["tg_failed"] == 0


if __name__ == "__main__":
    import inspect
    fns = [(n, f) for n, f in sorted(globals().items())
           if n.startswith("test_") and inspect.isfunction(f)]
    for name, fn in fns:
        if "monkeypatch" in fn.__code__.co_varnames:
            class MP:  # pytest monkeypatch 最小实现
                def setattr(self, obj, name, value):
                    setattr(obj, name, value)
            fn(MP())
        else:
            fn()
        print("PASS", name)
    print("ALL %d TESTS PASSED" % len(fns))
