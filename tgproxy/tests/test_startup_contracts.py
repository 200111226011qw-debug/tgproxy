# -*- coding: utf-8 -*-
"""启动期契约测试（2026-10-28，阶段 0 ②④）。

每条都对应一个「不测就会在打包后炸」的点：
- windowed 下 `sys.stdout is None`，而 `send_response()` 先调 `log_request`
  → `log_message` 直接 write 会让**每个请求在发响应之前**抛 AttributeError。
- `/api/shutdown` 必须 POST + 同源，否则任意网页能关掉本地服务。
- 单实例探测只认 `/api/version` 的 `app` 标识：端口被别的软件占用是正常情况
  （`create_server` 会顺延），不能据此判定为重复实例；反过来本程序自己也可能
  因为顺延跑在 8766 上，所以不能只探固定端口。

既可用 pytest 运行，也可直接执行本文件。
"""
import json
import os
import sys
import tempfile
import threading
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), os.pardir)))

import app as app_module  # noqa: E402
import proxy_manager as pm  # noqa: E402


class _StubManager:
    def get_state(self):
        return {"updated_at": None, "fetching": False, "error": None, "notice": None,
                "stats": {}, "sources": [], "proxies": []}

    def refresh_async(self):
        return True

    def set_proxy_list_urls(self, urls):
        pass


class _ForeignHandler(BaseHTTPRequestHandler):
    """冒充「别的软件占着端口」：200 但不是 tgproxy。"""

    def do_GET(self):
        body = b'{"hello": 1}'
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


def _serve_tgproxy(settings=None):
    server = app_module.TgProxyServer(
        ("127.0.0.1", 0), app_module.ApiHandler, _StubManager(), settings or {}
    )
    server.scheduler = None
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server, thread, server.server_address[1]


def _serve_foreign():
    server = ThreadingHTTPServer(("127.0.0.1", 0), _ForeignHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server, thread, server.server_address[1]


def _stop(server, thread):
    try:
        server.shutdown()
    except Exception:
        pass
    try:
        server.server_close()
    except Exception:
        pass
    thread.join(3)


def _request(port, path, method="GET", origin=None):
    req = urllib.request.Request(
        "http://127.0.0.1:%d%s" % (port, path), method=method
    )
    if origin is not None:
        req.add_header("Origin", origin)
    try:
        with urllib.request.urlopen(req, timeout=3) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as exc:
        return exc.code, json.loads(exc.read().decode("utf-8") or "{}")


# --------------------------------------------------------------------------
# ② log_message 短路
# --------------------------------------------------------------------------

def test_log_message_survives_none_stdout():
    """回归：windowed 下 sys.stdout 为 None，log_message 不得抛异常。"""

    class FakeHandler:
        def address_string(self):
            return "127.0.0.1"

        def log_date_time_string(self):
            return "00:00:00"

    original = sys.stdout
    sys.stdout = None
    try:
        app_module.ApiHandler.log_message(FakeHandler(), "%s %s", "GET", "/api/proxies")
    finally:
        sys.stdout = original


# --------------------------------------------------------------------------
# ④ VERSION 单一源 + /api/version
# --------------------------------------------------------------------------

def test_version_file_matches_app_version():
    with open(app_module.VERSION_PATH, encoding="utf-8") as fh:
        assert fh.read().strip() == app_module.APP_VERSION
    assert app_module.APP_VERSION != "unknown"


def test_version_endpoint_returns_app_id_and_version():
    server, thread, port = _serve_tgproxy()
    try:
        status, body = _request(port, "/api/version")
        assert status == 200
        assert body.get("app") == "tgproxy"
        assert body.get("version") == app_module.APP_VERSION
    finally:
        _stop(server, thread)


# --------------------------------------------------------------------------
# ② 单实例探测
# --------------------------------------------------------------------------

def test_probe_instance_detects_tgproxy():
    server, thread, port = _serve_tgproxy()
    try:
        assert app_module.probe_instance(port) is True
    finally:
        _stop(server, thread)


def test_probe_instance_ignores_foreign_service():
    """端口被别的软件占用不是重复实例——create_server 会顺延。"""
    server, thread, port = _serve_foreign()
    try:
        assert app_module.probe_instance(port) is False
    finally:
        _stop(server, thread)


def test_probe_instance_false_when_nothing_listens():
    # 取一个几乎肯定没人监听的端口
    assert app_module.probe_instance(65400) is False


def test_singleton_lock_is_exclusive():
    """锁端口独占：第二次 bind 必须失败；释放后可重新拿到。"""
    original = app_module._singleton_socket
    try:
        app_module._singleton_socket = None
        assert app_module.acquire_single_instance() is True
        assert app_module.acquire_single_instance() is False
        app_module._singleton_socket.close()
        app_module._singleton_socket = None
        assert app_module.acquire_single_instance() is True
    finally:
        if app_module._singleton_socket is not None:
            app_module._singleton_socket.close()
        app_module._singleton_socket = original


# --------------------------------------------------------------------------
# ② /api/shutdown
# --------------------------------------------------------------------------

def test_shutdown_rejects_get():
    server, thread, port = _serve_tgproxy()
    try:
        status, _ = _request(port, "/api/shutdown", method="GET")
        assert status == 404
        assert thread.is_alive()
    finally:
        _stop(server, thread)


def test_shutdown_rejects_cross_origin_post():
    server, thread, port = _serve_tgproxy()
    try:
        status, body = _request(
            port, "/api/shutdown", method="POST", origin="http://evil.example"
        )
        assert status == 403
        assert thread.is_alive(), "跨站请求不得关掉服务"
    finally:
        _stop(server, thread)


def test_shutdown_accepts_same_origin_post_and_stops_server():
    server, thread, port = _serve_tgproxy()
    try:
        status, body = _request(
            port, "/api/shutdown", method="POST",
            origin="http://127.0.0.1:%d" % port,
        )
        assert status == 200 and body.get("ok") is True
        thread.join(5)
        assert not thread.is_alive(), "shutdown 后 serve_forever 应已退出"
    finally:
        _stop(server, thread)


# --------------------------------------------------------------------------
# 原子写：强制退出 / 取消时不得留下截断的 JSON
# --------------------------------------------------------------------------

def test_save_known_good_is_atomic():
    d = tempfile.mkdtemp()
    kg = os.path.join(d, "kg.json")
    m = pm.ProxyManager(known_good_path=kg)
    m._save_known_good([{"host": "1.1.1.1", "port": 1080,
                         "tg_ok": True, "tg_latency_ms": 500}])
    assert not os.path.exists(kg + ".tmp"), "正常写完后不应残留 .tmp"
    good = json.load(open(kg, encoding="utf-8"))

    original_dump = pm.json.dump

    def boom(*_args, **_kwargs):
        raise OSError("disk full")

    pm.json.dump = boom
    try:
        m._save_known_good([{"host": "2.2.2.2", "port": 1080,
                             "tg_ok": True, "tg_latency_ms": 400}])
    finally:
        pm.json.dump = original_dump

    assert json.load(open(kg, encoding="utf-8")) == good, "写入失败不得破坏原文件"
    assert not os.path.exists(kg + ".tmp")


def test_save_config_is_atomic():
    d = tempfile.mkdtemp()
    cfg_path = os.path.join(d, "config.json")
    old_path = app_module.CONFIG_PATH
    app_module.CONFIG_PATH = cfg_path
    try:
        config = {"proxy_list_urls": ["https://A/x"], "port": 8765}
        app_module.save_config(config)
        assert not os.path.exists(cfg_path + ".tmp")
        good = json.load(open(cfg_path, encoding="utf-8"))

        original_dump = app_module.json.dump

        def boom(*_args, **_kwargs):
            raise OSError("disk full")

        app_module.json.dump = boom
        try:
            config["port"] = 9999
            app_module.save_config(config)
        finally:
            app_module.json.dump = original_dump

        assert json.load(open(cfg_path, encoding="utf-8")) == good
        assert not os.path.exists(cfg_path + ".tmp")
    finally:
        app_module.CONFIG_PATH = old_path


if __name__ == "__main__":
    import inspect
    fns = [(n, f) for n, f in sorted(globals().items())
           if n.startswith("test_") and inspect.isfunction(f)]
    for name, fn in fns:
        fn()
        print("PASS", name)
    print("ALL %d TESTS PASSED" % len(fns))
