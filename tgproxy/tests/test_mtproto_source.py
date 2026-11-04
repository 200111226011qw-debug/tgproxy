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


def test_all_source_urls_caps_proxy_only():
    """截断只作用于普通源；MTProto 源被一起切掉等于整个能力静默失效。"""
    many = ["https://e.com/%d.txt" % i for i in range(pm.FETCH_MAX_SOURCES + 5)]
    mgr = pm.ProxyManager(proxy_list_urls=many,
                          mtproto_list_urls=["https://m.com/mt.txt"])
    urls = mgr._all_source_urls()
    assert len(urls) == pm.FETCH_MAX_SOURCES + 1
    assert urls[-1] == "https://m.com/mt.txt"


def test_mtproto_source_survives_proxy_cap():
    """普通源配满上限（20）时 MTProto 源仍须被抓。

    否则界面显示「已配置 · 无候选」，与「源里没有 t.me/proxy」无法区分 ——
    正是这套状态位要消除的二义。
    """
    proxy = ["https://p.com/%d.txt" % i for i in range(pm.FETCH_MAX_SOURCES)]
    mt = "https://m.com/mt.txt"
    mgr = pm.ProxyManager(proxy_list_urls=proxy, mtproto_list_urls=[mt])
    urls = mgr._all_source_urls()
    assert mt in urls
    assert len(urls) == pm.FETCH_MAX_SOURCES + 1


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


# ---------------------------------------------------------------------------
# HTTP 侧：PUT /api/settings 必须真的能写进去


class _FakeServer:
    def __init__(self):
        self.calls = {}

    def update_settings(self, **kwargs):
        self.calls = kwargs
        return {"ok": True}


class _FakeHandler:
    """只实现 _handle_settings_update 用到的三个接口，避免起真服务。"""

    def __init__(self, body):
        self._body = body
        self.sent = None
        self.status = 200
        self.server = _FakeServer()

    def _read_json_body(self):
        return self._body

    def _send_json(self, payload, status=200):
        self.sent = payload
        self.status = status


def test_put_passes_mtproto_list_urls_through():
    """这条是防「分支存在但生产不可达」：曾经 update_settings 收这个参数，
    但 _handle_settings_update 从没传过，等于写不进去。"""
    h = _FakeHandler({"mtproto_list_urls": ["https://e.com/mt.txt"]})
    app.ApiHandler._handle_settings_update(h)

    assert h.status == 200, h.sent
    assert h.server.calls.get("mtproto_list_urls") == ["https://e.com/mt.txt"]


def test_put_can_clear_mtproto_list_urls():
    h = _FakeHandler({"mtproto_list_urls": []})
    app.ApiHandler._handle_settings_update(h)

    assert h.status == 200, h.sent
    assert h.server.calls.get("mtproto_list_urls") == []


def test_put_rejects_bad_mtproto_url():
    h = _FakeHandler({"mtproto_list_urls": ["ftp://e.com/x"]})
    app.ApiHandler._handle_settings_update(h)

    assert h.status == 400
    assert "http" in h.sent["error"]
    assert h.server.calls == {}


def test_put_ignores_body_without_mtproto_key():
    """不传这个键 = 不改动，不能把它当成「清空」。"""
    h = _FakeHandler({"interval_minutes": 5})
    app.ApiHandler._handle_settings_update(h)

    assert h.status == 200, h.sent
    assert h.server.calls.get("mtproto_list_urls") is None


# ---------------------------------------------------------------------------
# 校验函数（与配置文件那条走静默丢弃不同：API 必须报错）


def test_validate_accepts_list():
    assert app.validate_mtproto_list_urls(["https://e.com/a.txt"]) == (
        ["https://e.com/a.txt"], None)


def test_validate_rejects_non_list():
    _, err = app.validate_mtproto_list_urls("https://e.com/a.txt")
    assert err


def test_validate_reports_bad_scheme_instead_of_dropping():
    got, err = app.validate_mtproto_list_urls(["https://e.com/a.txt", "ftp://e.com/b"])
    assert got is None and err


def test_validate_dedupes_and_caps():
    got, err = app.validate_mtproto_list_urls(["https://e.com/a.txt", "https://e.com/a.txt"])
    assert got == ["https://e.com/a.txt"] and err is None

    too_many = ["https://e.com/%d.txt" % i for i in range(app.MAX_PROXY_LIST_URLS + 1)]
    got, err = app.validate_mtproto_list_urls(too_many)
    assert got is None and "最多" in err


if __name__ == "__main__":
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            try:
                fn()
                print("PASS", name)
            except AssertionError as exc:
                print("FAIL", name, exc)
