# -*- coding: utf-8 -*-
"""P1-A 前置：超时必须「调用期求值」（2026-10-28）。

原实现是 def 期绑定：

    def test_tcp_latency(host, port, timeout=TEST_TIMEOUT_SEC):

默认值在函数定义时就固化成当时的常量值，运行期再改 TEST_TIMEOUT_SEC
对已定义的函数**没有任何影响**。后果不是"改不动"，而是更隐蔽的一种：
A/B 实验跑出来没差异，于是把「配置没生效」记成「降超时无效」。

本文件锁三件事：
1. 改模块常量后，下一次调用必须用新值（旧实现恒为定义时的 5.0）；
2. 显式传参优先于常量；
3. 环境变量覆盖能真的到达常量（不重建 exe 做 A/B 的前提）。

既可用 pytest 运行，也可直接执行本文件。
"""
import os
import subprocess
import sys

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), os.pardir)))

import proxy_manager as pm  # noqa: E402

PKG_DIR = os.path.dirname(os.path.abspath(pm.__file__))


def _fake_create_connection(records):
    """记录收到的 timeout 后立刻抛 OSError——不真的发网络请求。"""

    def fake(address, timeout=None, **kwargs):
        records.append(timeout)
        raise OSError("fake: 不连真实网络")

    return fake


class _Patched:
    """临时替换 socket.create_connection，退出时还原。"""

    def __init__(self, records):
        self.records = records

    def __enter__(self):
        self._orig = pm.socket.create_connection
        pm.socket.create_connection = _fake_create_connection(self.records)
        return self.records

    def __exit__(self, *exc):
        pm.socket.create_connection = self._orig
        return False


def _run_with_env(code, extra_env):
    env = dict(os.environ)
    for key in ("TGPROXY_FETCH_TIMEOUT", "TGPROXY_TCP_TIMEOUT",
                "TGPROXY_TG_TIMEOUT", "TGPROXY_TG_DEADLINE"):
        env.pop(key, None)
    env.update(extra_env)
    return subprocess.run(
        [sys.executable, "-c", code],
        cwd=PKG_DIR, env=env, capture_output=True, text=True, timeout=60,
    )


# --------------------------------------------------------------------------
# 1. 调用期求值
# --------------------------------------------------------------------------

def test_tcp_timeout_is_read_at_call_time():
    """改常量后下一次调用必须用新值（旧实现恒为定义时的 5.0）。"""
    original = pm.TEST_TIMEOUT_SEC
    try:
        with _Patched([]) as records:
            pm.TEST_TIMEOUT_SEC = 0.05
            pm.test_tcp_latency("127.0.0.1", 1)
            pm.TEST_TIMEOUT_SEC = 2.0
            pm.test_tcp_latency("127.0.0.1", 1)
    finally:
        pm.TEST_TIMEOUT_SEC = original
    assert records == [0.05, 2.0], "超时仍在 def 期绑定，改常量无效：%r" % (records,)


def test_explicit_timeout_overrides_constant():
    original = pm.TEST_TIMEOUT_SEC
    try:
        with _Patched([]) as records:
            pm.TEST_TIMEOUT_SEC = 9.0
            pm.test_tcp_latency("127.0.0.1", 1, timeout=0.25)
    finally:
        pm.TEST_TIMEOUT_SEC = original
    assert records == [0.25]


def test_tg_pipeline_reads_timeout_and_deadline_at_call_time():
    """两关管线共享的 stage 预算也必须随常量变化。"""
    calls = []
    orig_dc = pm.test_socks5_telegram_dc
    orig_to = pm.TG_TEST_TIMEOUT_SEC
    orig_dl = pm.TG_TEST_DEADLINE_SEC
    pm.test_socks5_telegram_dc = lambda h, p, t=None, **k: calls.append(t) or None
    try:
        pm.TG_TEST_TIMEOUT_SEC = 3.0
        pm.TG_TEST_DEADLINE_SEC = 3.0
        pm.test_telegram_via_socks5("1.2.3.4", 1080)
        pm.TG_TEST_TIMEOUT_SEC = 6.0
        pm.TG_TEST_DEADLINE_SEC = 6.0
        pm.test_telegram_via_socks5("1.2.3.4", 1080)
    finally:
        pm.test_socks5_telegram_dc = orig_dc
        pm.TG_TEST_TIMEOUT_SEC = orig_to
        pm.TG_TEST_DEADLINE_SEC = orig_dl
    assert calls == [3.0, 6.0], "stage 预算没跟着常量变：%r" % (calls,)


# --------------------------------------------------------------------------
# 2. 环境变量覆盖（不重建 exe 做 A/B 的前提）
# --------------------------------------------------------------------------

def test_env_override_reaches_constants():
    out = _run_with_env(
        "import proxy_manager as m;"
        "print(m.TEST_TIMEOUT_SEC, m.TG_TEST_DEADLINE_SEC,"
        "      m.FETCH_TIMEOUT_SEC, m.TG_TEST_TIMEOUT_SEC)",
        {
            "TGPROXY_TCP_TIMEOUT": "2.5",
            "TGPROXY_TG_DEADLINE": "12",
            "TGPROXY_FETCH_TIMEOUT": "7",
            "TGPROXY_TG_TIMEOUT": "4.5",
        },
    )
    assert out.returncode == 0, out.stderr
    assert out.stdout.split() == ["2.5", "12.0", "7.0", "4.5"]


def test_invalid_env_falls_back_to_default():
    for bad in ("abc", "-1", "0", ""):
        out = _run_with_env(
            "import proxy_manager as m; print(m.TEST_TIMEOUT_SEC)",
            {"TGPROXY_TCP_TIMEOUT": bad},
        )
        assert out.returncode == 0, out.stderr
        assert out.stdout.strip() == "5.0", "非法值 %r 应回落 5.0，实际 %r" % (
            bad, out.stdout.strip())


def test_defaults_unchanged_without_env():
    out = _run_with_env(
        "import proxy_manager as m;"
        "print(m.FETCH_TIMEOUT_SEC, m.TEST_TIMEOUT_SEC,"
        "      m.TG_TEST_TIMEOUT_SEC, m.TG_TEST_DEADLINE_SEC)",
        {},
    )
    assert out.returncode == 0, out.stderr
    assert out.stdout.split() == ["15", "5.0", "8.0", "20.0"]


if __name__ == "__main__":
    import inspect
    fns = [(n, f) for n, f in sorted(globals().items())
           if n.startswith("test_") and inspect.isfunction(f)]
    for name, fn in fns:
        fn()
        print("PASS", name)
    print("ALL %d TESTS PASSED" % len(fns))
