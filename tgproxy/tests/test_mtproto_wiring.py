# -*- coding: utf-8 -*-
"""Phase 4：MTProto 真校验「接入主流程」的接线测试。

只测接线，不测协议本体（协议在 test_mtproto_handshake.py）。全部 mock，无网络。

要锁住的核心语义：
1. link 里能反解出 secret，缺失时不发起连接；
2. tg_ok 三态正确：True / False / None（缺库、缺 secret 必须是 None，不能算失败）；
3. 两关漏斗（dc_fail/web_fail）只收 SOCKS5，MTProto 单独进 mtproto_gate_stats；
4. 预算随延迟门槛派生。
"""

import os
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
_ROOT = os.path.dirname(_HERE)
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

import mtproto_handshake as mh  # noqa: E402
import proxy_manager as pm  # noqa: E402
from mock_mtproxy import MockMtproxyServer  # noqa: E402

SECRET_HEX = "4fc30d1222972a04067e805a5134cb57"
TG_LINK = "tg://proxy?server=2.2.2.2&port=443&secret=" + SECRET_HEX
HTTPS_LINK = "https://t.me/proxy?server=2.2.2.2&port=443&secret=" + SECRET_HEX


def _entry(host, port=443, type_="mtproto", link=None):
    item = {"host": host, "port": port, "type": type_}
    if link is not None:
        item["link"] = link
    return item


class _Patched:
    """临时替换模块级调用点，退出时还原（与被测代码的 monkeypatch 风格一致）。"""

    def __init__(self, mt=None, tg=None):
        self._mt = mt
        self._tg = tg

    def __enter__(self):
        self._orig_mt = pm.check_mtproto_proxy
        self._orig_tg = pm.test_telegram_via_socks5
        if self._mt is not None:
            pm.check_mtproto_proxy = self._mt
        if self._tg is not None:
            pm.test_telegram_via_socks5 = self._tg
        return self

    def __exit__(self, *exc_info):
        pm.check_mtproto_proxy = self._orig_mt
        pm.test_telegram_via_socks5 = self._orig_tg
        return False


def _ok(host, port, secret, **kwargs):
    return mh.CheckResult(True, host, port, "raw", rtt_ms=42.5)


def _protocol_fail(host, port, secret, **kwargs):
    return mh.CheckResult(False, host, port, "raw", error_code="PROTOCOL_ERROR")


def _no_crypto(host, port, secret, **kwargs):
    return mh.CheckResult(False, host, port, "raw", error_code="CRYPTO_UNAVAILABLE")


# ---------------------------------------------------------------------------
# secret 反解


def test_secret_from_link_tg_scheme():
    assert pm._secret_from_link(TG_LINK) == SECRET_HEX


def test_secret_from_link_https_scheme():
    assert pm._secret_from_link(HTTPS_LINK) == SECRET_HEX


def test_secret_from_link_missing_or_malformed():
    for link in (None, "", "   ", "tg://proxy?server=1.2.3.4&port=443", "not a url"):
        assert pm._secret_from_link(link) is None, link


# ---------------------------------------------------------------------------
# 三态映射


def test_mtproto_ok_marks_verified():
    manager = pm.ProxyManager()
    with _Patched(mt=_ok):
        results = manager._test_telegram_entries([_entry("2.2.2.2", link=TG_LINK)])

    assert results[0]["tg_ok"] is True
    assert results[0]["tg_latency_ms"] == 42.5
    assert manager.get_state()["mtproto_gate_stats"] == {
        "in_pool": 1, "candidates": 1,
        "tested": 1, "ok": 1, "fail": 0, "unavailable": 0,
    }


def test_mtproto_protocol_failure_marks_failed():
    manager = pm.ProxyManager()
    with _Patched(mt=_protocol_fail):
        results = manager._test_telegram_entries([_entry("2.2.2.2", link=TG_LINK)])

    assert results[0]["tg_ok"] is False
    assert results[0]["tg_latency_ms"] is None
    assert manager.get_state()["mtproto_gate_stats"]["fail"] == 1


def test_crypto_unavailable_stays_unverified():
    """缺加密库不是节点的错：必须 None，否则三轮后被误拉黑。"""
    manager = pm.ProxyManager()
    with _Patched(mt=_no_crypto):
        results = manager._test_telegram_entries([_entry("2.2.2.2", link=TG_LINK)])

    assert results[0]["tg_ok"] is None
    assert results[0]["tg_latency_ms"] is None
    assert manager.get_state()["mtproto_gate_stats"]["unavailable"] == 1


def _faketls(host, port, secret, **kwargs):
    return mh.CheckResult(False, host, port, "ee", error_code="FAKETLS_UNSUPPORTED")


def test_faketls_stays_unverified():
    """ee/FakeTLS 是主流分享格式：未实现必须记「无法判定」，否则全被误拉黑。"""
    manager = pm.ProxyManager()
    link = "tg://proxy?server=2.2.2.2&port=443&secret=ee" + SECRET_HEX
    with _Patched(mt=_faketls):
        results = manager._test_telegram_entries([_entry("2.2.2.2", link=link)])

    assert results[0]["tg_ok"] is None
    assert results[0]["tg_latency_ms"] is None
    assert manager.get_state()["mtproto_gate_stats"]["unavailable"] == 1


def test_missing_module_degrades_to_unverified():
    manager = pm.ProxyManager()
    with _Patched():
        pm.check_mtproto_proxy = None
        results = manager._test_telegram_entries([_entry("2.2.2.2", link=TG_LINK)])

    assert results[0]["tg_ok"] is None
    assert manager.get_state()["mtproto_gate_stats"]["unavailable"] == 1


def test_missing_link_does_not_attempt_handshake():
    manager = pm.ProxyManager()
    calls = []

    def must_not_call(*args, **kwargs):
        calls.append(args)
        raise AssertionError("没有 secret 时不该发起握手")

    with _Patched(mt=must_not_call):
        results = manager._test_telegram_entries([_entry("2.2.2.2")])

    assert calls == []
    assert results[0]["tg_ok"] is None
    assert manager.get_state()["mtproto_gate_stats"]["unavailable"] == 1


def test_unknown_type_stays_unverified():
    manager = pm.ProxyManager()
    with _Patched(mt=_ok):
        results = manager._test_telegram_entries([_entry("4.4.4.4", 8080, "http")])

    assert results[0]["tg_ok"] is None
    assert manager.get_state()["mtproto_gate_stats"]["tested"] == 0


# ---------------------------------------------------------------------------
# 统计隔离


def test_mtproto_not_in_two_gate_funnel():
    manager = pm.ProxyManager()
    entries = [
        _entry("1.1.1.1", 1080, "socks5"),
        _entry("2.2.2.2", link=TG_LINK),
    ]

    def fake_tg(host, port, **kwargs):
        sink = kwargs.get("sink")
        if sink is not None:
            sink({"stage": "ok", "dc_ms": 30.0, "web_ms": 40.0})
        return 40

    with _Patched(mt=_ok, tg=fake_tg):
        manager._test_telegram_entries(entries)

    state = manager.get_state()
    assert state["tg_gate_stats"]["total"] == 1, state["tg_gate_stats"]
    assert state["mtproto_gate_stats"]["tested"] == 1, state["mtproto_gate_stats"]


def test_mtproto_stats_bucket_counts_three_states():
    manager = pm.ProxyManager()
    entries = [
        _entry("2.2.2.1", link=TG_LINK),
        _entry("2.2.2.2", link=TG_LINK),
        _entry("2.2.2.3", link=TG_LINK),
    ]

    def by_host(host, port, secret, **kwargs):
        if host.endswith(".1"):
            return mh.CheckResult(True, host, port, "raw", rtt_ms=10.0)
        if host.endswith(".2"):
            return mh.CheckResult(False, host, port, "raw", error_code="PROTOCOL_ERROR")
        return mh.CheckResult(False, host, port, "raw", error_code="CRYPTO_UNAVAILABLE")

    with _Patched(mt=by_host):
        manager._test_telegram_entries(entries)

    assert manager.get_state()["mtproto_gate_stats"] == {
        "in_pool": 3, "candidates": 3,
        "tested": 3, "ok": 1, "fail": 1, "unavailable": 1,
    }


def test_empty_entries_clears_both_stats():
    manager = pm.ProxyManager()
    with _Patched(mt=_ok):
        manager._test_telegram_entries([_entry("2.2.2.2", link=TG_LINK)])
    manager._test_telegram_entries([])
    state = manager.get_state()
    assert state["tg_gate_stats"] == {}
    # 空池也给完整形状：candidates=0 表示「没有节点进入闸门」，不是「全部通过」
    assert state["mtproto_gate_stats"] == {
        "in_pool": 0, "candidates": 0,
        "tested": 0, "ok": 0, "fail": 0, "unavailable": 0,
    }


# ---------------------------------------------------------------------------
# 预算与参数传递


def test_mtproto_budget_derives_from_threshold():
    assert pm.mtproto_budget_for(1000) == 2.0
    assert pm.mtproto_budget_for(5000) == 6.0
    assert pm.mtproto_budget_for(0) == 2.0
    assert pm.mtproto_budget_for("bad") == 2.0


def test_check_entry_passes_budget_to_handshake():
    captured = {}

    def fake(host, port, secret, **kwargs):
        captured.update(kwargs)
        return mh.CheckResult(True, host, port, "raw", rtt_ms=1.0)

    with _Patched(mt=fake):
        pm._check_mtproto_entry(
            {"host": "2.2.2.2", "port": 443, "link": TG_LINK}, 7.0
        )

    assert captured["connect_timeout"] == 7.0
    assert captured["response_timeout"] == 7.0


def test_end_to_end_real_handshake_through_pipeline():
    """不 mock 协议：本地起真 MTProxy，走完 link→secret→握手→resPQ 全链路。"""
    secret = bytes.fromhex(SECRET_HEX)
    with MockMtproxyServer(secret) as server:
        link = "tg://proxy?server=127.0.0.1&port=%d&secret=%s" % (
            server.port, SECRET_HEX,
        )
        manager = pm.ProxyManager()
        results = manager._test_telegram_entries(
            [_entry("127.0.0.1", server.port, link=link)]
        )

    assert results[0]["tg_ok"] is True, results[0]
    assert results[0]["tg_latency_ms"] is not None
    assert manager.get_state()["mtproto_gate_stats"] == {
        "in_pool": 1, "candidates": 1,
        "tested": 1, "ok": 1, "fail": 0, "unavailable": 0,
    }
    assert server.errors == [], server.errors


def test_in_pool_defaults_to_candidates_when_unknown():
    """直接调用（没传 in_pool）时，视为「池里的都进了闸门」，不能凭空少算。"""
    manager = pm.ProxyManager()
    with _Patched(mt=_ok):
        manager._test_telegram_entries([_entry("2.2.2.2", link=TG_LINK)])

    stats = manager.get_state()["mtproto_gate_stats"]
    assert stats["in_pool"] == 1
    assert stats["candidates"] == 1


def test_in_pool_greater_than_candidates_when_tcp_prefilter_drops_them():
    """池里 5 个 MTProto，只有 1 个通过 TCP：差额说明其余死在预筛，不是「没测」。"""
    manager = pm.ProxyManager()
    with _Patched(mt=_ok):
        manager._test_telegram_entries([_entry("2.2.2.2", link=TG_LINK)], in_pool=5)

    stats = manager.get_state()["mtproto_gate_stats"]
    assert stats["in_pool"] == 5
    assert stats["candidates"] == 1
    assert stats["tested"] == 1


def test_zero_in_pool_means_not_covered():
    """源里没有 tg:// 链接：in_pool=0。此时 tested=0 是「未覆盖」而不是「全部通过」。"""
    manager = pm.ProxyManager()
    manager._test_telegram_entries([], in_pool=0)

    stats = manager.get_state()["mtproto_gate_stats"]
    assert stats["in_pool"] == 0
    assert stats["candidates"] == 0
    assert stats["tested"] == 0


def test_pool_nonzero_but_gate_empty_means_all_dropped_by_tcp():
    """池里有 4 个 MTProto，一个都没进闸门：差额只能是 TCP 预筛淘汰，不是「没测」。"""
    manager = pm.ProxyManager()
    manager._test_telegram_entries([], in_pool=4)

    stats = manager.get_state()["mtproto_gate_stats"]
    assert stats["in_pool"] == 4
    assert stats["candidates"] == 0
    assert stats["tested"] == 0


if __name__ == "__main__":
    import inspect

    fns = [
        (name, fn)
        for name, fn in sorted(globals().items())
        if name.startswith("test_") and inspect.isfunction(fn)
    ]
    for name, fn in fns:
        fn()
        print("PASS", name)
    print("ALL %d TESTS PASSED" % len(fns))
