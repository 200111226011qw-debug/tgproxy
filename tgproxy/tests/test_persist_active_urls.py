# -*- coding: utf-8 -*-
"""源体检持久化修复的回归测试（2026-10-27）。

修复前：`app.persist_active_urls` 是 `main()` 内的闭包，引用了不存在的
`settings`，调用即抛 `NameError`，又被 `proxy_manager` 里的
`except Exception: pass` 吞掉 —— 体检换源从未落盘，重启即回到原样。
实测证据：`source_health.json` 已标记 PARKED，而 `config.json` 的源列表一字未改。

本文件锁住四件事：
1. 换源后 config.json 真的被改写，且补位的是内置候选；
2. 未变化时不写盘（回调每轮都会触发，无条件写会变成每轮重写配置）；
3. 非法项被过滤、超量被截断；
4. 回调抛异常时留 ERROR 日志，且本轮流程不中断。

既可用 pytest 运行，也可直接执行本文件。
"""
import json
import logging
import os
import sys
import tempfile

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), os.pardir)))

import app as app_module  # noqa: E402
from proxy_manager import SOURCE_CANDIDATES, ProxyManager  # noqa: E402


def rec(url, status, error=None):
    return {"url": url, "status": status, "kept": 0, "via_mirror": False, "error": error}


def make_manager(tmpdir, name, on_changed):
    return ProxyManager(
        known_good_path=os.path.join(tmpdir, "kg_%s.json" % name),
        source_health_path=os.path.join(tmpdir, "sh_%s.json" % name),
        on_sources_changed=on_changed,
    )


class _CfgPath:
    """临时把 app.CONFIG_PATH 指到 tmp 目录，退出时还原。"""

    def __init__(self, tmpdir):
        self.path = os.path.join(tmpdir, "config.json")

    def __enter__(self):
        self._old = app_module.CONFIG_PATH
        app_module.CONFIG_PATH = self.path
        return self.path

    def __exit__(self, *exc):
        app_module.CONFIG_PATH = self._old
        return False


def _park_one_source(m, urls, zero_url):
    """跑 3 轮，让 zero_url 因「连续 3 轮 0 产出」被停用并补位。"""
    per_src = {u: (0 if u == zero_url else 5) for u in urls}
    for _ in range(3):
        m._update_source_health([rec(u, "ok") for u in urls], per_src)


def test_persist_has_no_undefined_settings_reference():
    """回归：原实现引用不存在的 settings，调用即 NameError。"""
    d = tempfile.mkdtemp()
    with _CfgPath(d) as cfg_path:
        config = {"proxy_list_urls": [], "port": 8765}
        app_module.persist_active_urls(config, ["https://A/x"])
        assert os.path.isfile(cfg_path)
        assert config["proxy_list_urls"] == ["https://A/x"]


def test_parking_persists_to_config_json():
    """端到端：源被停用后，config.json 的列表换成「剩余源 + 内置候选」。"""
    d = tempfile.mkdtemp()
    urls = ["https://A/x", "https://B/x"]
    with _CfgPath(d) as cfg_path:
        config = {"proxy_list_urls": list(urls), "port": 8765}
        m = make_manager(
            d, "persist",
            lambda new: app_module.persist_active_urls(config, new),
        )
        m.set_proxy_list_urls(list(urls))
        _park_one_source(m, urls, "https://A/x")

        saved = json.loads(open(cfg_path, encoding="utf-8").read())
        assert "https://A/x" not in saved["proxy_list_urls"]
        assert "https://B/x" in saved["proxy_list_urls"]
        assert SOURCE_CANDIDATES[0] in saved["proxy_list_urls"]
        # 内存里的 config 与磁盘一致（create_server 用的就是同一个 dict）
        assert config["proxy_list_urls"] == saved["proxy_list_urls"]
        # 与 manager 内部的活跃列表也一致
        assert config["proxy_list_urls"] == m._proxy_list_urls


def test_unchanged_list_does_not_rewrite():
    """回调每轮都会触发，未变化时必须一次都不写盘。"""
    d = tempfile.mkdtemp()
    with _CfgPath(d) as cfg_path:
        config = {"proxy_list_urls": ["https://A/x"], "port": 8765}
        app_module.persist_active_urls(config, ["https://A/x"])
        assert not os.path.exists(cfg_path), "未变化时不应创建 config.json"

        app_module.persist_active_urls(config, ["https://B/x"])
        assert os.path.isfile(cfg_path)
        mtime = os.path.getmtime(cfg_path)

        app_module.persist_active_urls(config, ["https://B/x"])
        assert os.path.getmtime(cfg_path) == mtime, "重复相同列表不应重写"


def test_persist_filters_invalid_and_caps():
    """非法 scheme 被丢弃；超过上限被截断。"""
    d = tempfile.mkdtemp()
    with _CfgPath(d) as cfg_path:
        config = {"proxy_list_urls": ["https://A/x"], "port": 8765}
        urls = ["ftp://nope", "", None] + ["https://s%d/x" % i for i in range(30)]
        app_module.persist_active_urls(config, urls)

        saved = json.loads(open(cfg_path, encoding="utf-8").read())
        assert all(u.startswith(("http://", "https://"))
                   for u in saved["proxy_list_urls"])
        assert len(saved["proxy_list_urls"]) == app_module.MAX_PROXY_LIST_URLS


def test_callback_failure_is_logged_and_round_continues():
    """回调抛异常：内存换源照常生效，但必须留 ERROR 日志（不再静默）。"""
    d = tempfile.mkdtemp()
    records = []

    class Capture(logging.Handler):
        def emit(self, record):
            records.append(record)

    handler = Capture()
    pm_logger = logging.getLogger("tgproxy.proxy_manager")
    old_level = pm_logger.level
    pm_logger.addHandler(handler)
    pm_logger.setLevel(logging.DEBUG)
    try:
        def boom(_urls):
            raise NameError("name 'settings' is not defined")

        urls = ["https://A/x", "https://B/x"]
        m = make_manager(d, "boom", boom)
        m.set_proxy_list_urls(list(urls))
        _park_one_source(m, urls, "https://A/x")

        assert m._proxy_list_urls == ["https://B/x", SOURCE_CANDIDATES[0]]
        errs = [r for r in records
                if r.levelno >= logging.ERROR and "回调失败" in r.getMessage()]
        assert errs, "回调异常必须留下 ERROR 日志，不能被静默吞掉"
    finally:
        pm_logger.removeHandler(handler)
        pm_logger.setLevel(old_level)


if __name__ == "__main__":
    import inspect
    fns = [(n, f) for n, f in sorted(globals().items())
           if n.startswith("test_") and inspect.isfunction(f)]
    for name, fn in fns:
        fn()
        print("PASS", name)
    print("ALL %d TESTS PASSED" % len(fns))
