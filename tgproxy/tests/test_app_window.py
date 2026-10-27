# -*- coding: utf-8 -*-
"""独立窗口（浏览器 --app 模式）与「关窗口即退出」的契约。

两个都是**失败时静默**的分支，所以值得单独测：

1. 找不到浏览器时返回 False，调用方退回 webbrowser；找到就返回 True 且**不再**
   退回 webbrowser —— 否则会同时开一个 app 窗口 + 一个浏览器标签（踩过）。
2. 页面关掉 → `/api/bye` → 宽限 3s 退出；宽限期内任何一次 `/api/proxies` 轮询
   都要能取消（否则刷新页面会把整个应用关掉）。

不测「子进程退出 = 窗口关闭」：实测 Edge 启动器把窗口交给浏览器主进程后自己
1.5s 内就退出（Popen.poll() 返回 0），这个句柄代表不了窗口，所以方案里根本没
用它 —— 生命周期改由页面 pagehide 上报。
"""
import os
import sys
import threading
import time

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), os.pardir)))

import app  # noqa: E402


class _FakeProc:
    def poll(self):
        return 0  # Edge 启动器交接后就是这个样子


class _FakeServer:
    """只借用 TgProxyServer 的三个方法与状态，不真的开 socket。"""

    def __init__(self):
        self._bye_lock = threading.Lock()
        self._bye_timer = None
        self._served_ui = False

    def _exit_after_ui_closed(self):
        pass


def _patch(monkeypatch, tmp_path, browser):
    monkeypatch.setattr(app, "_find_app_browser", lambda: browser)
    monkeypatch.setattr(app, "LOCAL_DATA_DIR", str(tmp_path))


def test_no_browser_returns_false(monkeypatch, tmp_path):
    _patch(monkeypatch, tmp_path, browser=None)
    assert app.open_app_window("http://127.0.0.1:1/") is False


def test_popen_failure_returns_false(monkeypatch, tmp_path):
    _patch(monkeypatch, tmp_path, browser="C:/fake/msedge.exe")

    def boom(*a, **k):
        raise OSError("拒绝访问")

    monkeypatch.setattr(app.subprocess, "Popen", boom)
    assert app.open_app_window("http://127.0.0.1:1/") is False


def test_browser_found_returns_true(monkeypatch, tmp_path):
    """找到浏览器就算成功 —— 即便 Popen 出来的进程立刻退出（Edge 启动器行为）。"""
    _patch(monkeypatch, tmp_path, browser="C:/fake/msedge.exe")
    captured = {}

    def fake_popen(argv, **kwargs):
        captured["argv"] = argv
        return _FakeProc()

    monkeypatch.setattr(app.subprocess, "Popen", fake_popen)
    assert app.open_app_window("http://127.0.0.1:8765/") is True
    argv = captured["argv"]
    assert "--app=http://127.0.0.1:8765/" in argv
    # 独立 profile 必须有，否则 Edge 会把窗口交接给既有实例
    assert any(a.startswith("--user-data-dir=") for a in argv)


def test_app_browser_candidates_exist_on_this_machine():
    exe = app._find_app_browser()
    if exe is not None:
        assert os.path.isfile(exe)
        assert exe.lower().endswith(("msedge.exe", "chrome.exe"))


# ----------------------------------------------------------------------
# 关窗口即退出
# ----------------------------------------------------------------------

def test_bye_ignored_before_any_ui_poll():
    """还没服务过任何页面就收到 bye（比如脚本探测）→ 不安排退出。"""
    srv = _FakeServer()
    assert app.TgProxyServer.schedule_exit(srv, 0.05) is False
    assert srv._bye_timer is None


def test_poll_cancels_pending_exit():
    """刷新页面会先 pagehide 再重新轮询：轮询必须能把退出取消掉。"""
    srv = _FakeServer()
    app.TgProxyServer.note_ui_alive(srv)
    assert app.TgProxyServer.schedule_exit(srv, 5.0) is True
    assert srv._bye_timer is not None
    app.TgProxyServer.note_ui_alive(srv)
    assert srv._bye_timer is None


def test_bye_without_followup_exits():
    srv = _FakeServer()
    fired = []
    srv._exit_after_ui_closed = lambda: fired.append(True)  # 实例属性遮蔽类方法
    app.TgProxyServer.note_ui_alive(srv)
    assert app.TgProxyServer.schedule_exit(srv, 0.05) is True
    time.sleep(0.2)
    assert fired == [True]
