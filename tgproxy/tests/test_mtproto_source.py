# -*- coding: utf-8 -*-
"""MTProto 专用数据源的配置位与状态上报。

背景：默认 5 个源实测 5753 行、`t.me/proxy` 命中 0，所以 MTProto 真校验在默认配置下
永远不执行。这一组测试锁住的是「能不能把这条能力接上」，以及「未配置」和
「配置了但没候选」必须可区分。
"""

import os
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
_ROOT = os.path.dirname(_HERE)
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

import app  # noqa: E402
import proxy_manager as pm  # noqa: E402


# ---------------------------------------------------------------------------
# 配置归一化


def test_normalize_allows_empty():
    """空列表必须保持为空 —— 不能像普通源那样回落到默认值。"""
    assert app.normalize_mtproto_list_urls([]) == []
    assert app.normalize_mtproto_list_urls(None) == []
    assert app.normalize_mtproto_list_urls("") == []


def test_normalize_accepts_single_string():
    assert app.normalize_mtproto_list_urls("https://e.com/mt.txt") == [
        "https://e.com/mt.txt"
    ]


def test_normalize_filters_non_http_and_dupes():
    got = app.normalize_mtproto_list_urls([
        "https://e.com/mt.txt", "ftp://e.com/x.txt", "  ", 123,
        "https://e.com/mt.txt", "http://e.com/b.txt",
    ])
    assert got == ["https://e.com/mt.txt", "http://e.com/b.txt"]


def test_normalize_caps_length():
    urls = ["https://e.com/%d.txt" % i for i in range(app.MAX_PROXY_LIST_URLS + 5)]
    assert len(app.normalize_mtproto_list_urls(urls)) == app.MAX_PROXY_LIST_URLS


def test_normalize_config_keeps_empty_default():
    cfg = app.normalize_config({})
    assert cfg["mtproto_list_urls"] == []
    # 普通源仍然有默认值，两者行为不同是有意的
    assert cfg["proxy_list_urls"]


def test_normalize_config_preserves_user_value():
    cfg = app.normalize_config({"mtproto_list_urls": ["https://e.com/mt.txt"]})
    assert cfg["mtproto_list_urls"] == ["https://e.com/mt.txt"]


# ---------------------------------------------------------------------------
# 管理器侧


def _manager(**kwargs):
    return pm.ProxyManager(proxy_list_urls=["https://a.com/p.txt"], **kwargs)


def test_state_reports_not_configured_by_default():
    assert _manager().get_state()["mtproto_configured"] is False


def test_state_reports_configured_when_provided():
    mgr = _manager(mtproto_list_urls=["https://e.com/mt.txt"])
    assert mgr.get_state()["mtproto_configured"] is True


def test_all_source_urls_merges_and_dedupes():
    mgr = _manager(mtproto_list_urls=["https://e.com/mt.txt", "https://a.com/p.txt"])
    assert mgr._all_source_urls() == ["https://a.com/p.txt", "https://e.com/mt.txt"]


def test_all_source_urls_caps_total():
    many = ["https://e.com/%d.txt" % i for i in range(pm.FETCH_MAX_SOURCES)]
    mgr = _manager(mtproto_list_urls=many)
    assert len(mgr._all_source_urls()) == pm.FETCH_MAX_SOURCES


def test_set_mtproto_list_urls_hot_update():
    mgr = _manager()
    assert mgr.get_state()["mtproto_configured"] is False
    mgr.set_mtproto_list_urls(["https://e.com/mt.txt"])
    assert mgr.get_state()["mtproto_configured"] is True
    mgr.set_mtproto_list_urls([])
    assert mgr.get_state()["mtproto_configured"] is False


def test_configured_but_no_candidates_is_distinguishable():
    """配了源但池里没 MTProto：configured=True 且 in_pool=0，与「未配置」不同。"""
    mgr = _manager(mtproto_list_urls=["https://e.com/mt.txt"])
    mgr._test_telegram_entries([], in_pool=0)

    state = mgr.get_state()
    assert state["mtproto_configured"] is True
    assert state["mtproto_gate_stats"]["in_pool"] == 0


if __name__ == "__main__":
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            try:
                fn()
                print("PASS", name)
            except AssertionError as exc:
                print("FAIL", name, exc)
