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


def test_tg_dc_budget_derives_from_latency_limit():
    """第一关预算随门槛派生，而不是沿用固定常量。"""
    calls = []
    orig = pm.test_socks5_telegram_dc
    pm.test_socks5_telegram_dc = lambda h, p, t=None, **k: calls.append(t) or None
    try:
        pm.test_telegram_via_socks5("1.2.3.4", 1080, max_latency_ms=1000)
        pm.test_telegram_via_socks5("1.2.3.4", 1080, max_latency_ms=5000)
    finally:
        pm.test_socks5_telegram_dc = orig
    assert calls == [2.0, 6.0], "第一关预算没跟着门槛变：%r" % (calls,)


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
        assert out.stdout.strip() == "2.0", "非法值 %r 应回落 2.0，实际 %r" % (
            bad, out.stdout.strip())


def test_defaults_unchanged_without_env():
    out = _run_with_env(
        "import proxy_manager as m;"
        "print(m.FETCH_TIMEOUT_SEC, m.TEST_TIMEOUT_SEC,"
        "      m.TG_TEST_TIMEOUT_SEC, m.TG_TEST_DEADLINE_SEC)",
        {},
    )
    assert out.returncode == 0, out.stderr
    assert out.stdout.split() == ["15", "2.0", "8.0", "20.0"]


def test_tcp_workers_default_and_env_override():
    """TCP 并发默认 300，可用 TGPROXY_TCP_WORKERS 覆盖（便于限流时回退）。"""
    code = "import proxy_manager as m; print(m.TEST_MAX_WORKERS)"
    assert _run_with_env(code, {}).stdout.strip() == "300"
    assert _run_with_env(code, {"TGPROXY_TCP_WORKERS": "200"}).stdout.strip() == "200"
    # 非法值回落默认，不能让一个笔误把并发变成 0 或负数
    assert _run_with_env(code, {"TGPROXY_TCP_WORKERS": "abc"}).stdout.strip() == "300"


# --------------------------------------------------------------------------
# 3. 预筛超时从延迟门槛派生（方案 B）
# --------------------------------------------------------------------------

def test_tcp_timeout_derives_from_latency_limit():
    """门槛越高，预筛越宽——保证「能过门槛的节点不会在预筛被冤杀」。"""
    assert pm.tcp_timeout_for(500) == 2.0     # 0.5+1.0=1.5 → 抬到下限 2.0
    assert pm.tcp_timeout_for(1000) == 2.0    # 1.0+1.0=2.0，正好等于下限
    assert pm.tcp_timeout_for(5000) == 6.0    # 5.0+1.0
    assert pm.tcp_timeout_for(60000) == 61.0  # 上限 60s → 61s


def test_tcp_timeout_floor_is_not_hardcoded():
    """下限必须来自 TEST_TIMEOUT_SEC，不能写死 2.0。"""
    original = pm.TEST_TIMEOUT_SEC
    try:
        pm.TEST_TIMEOUT_SEC = 4.0
        assert pm.tcp_timeout_for(1000) == 4.0   # 派生 2.0 被下限抬到 4.0
        pm.TEST_TIMEOUT_SEC = 0.5
        assert pm.tcp_timeout_for(1000) == 2.0   # 派生 2.0 压过下限
    finally:
        pm.TEST_TIMEOUT_SEC = original


def test_invalid_latency_limit_falls_back():
    for bad in (None, "abc", 0, -1):
        assert pm.tcp_timeout_for(bad) == 2.0, "非法门槛 %r 应回落默认" % (bad,)


def test_pre_screen_uses_derived_timeout():
    """集成：门槛 5000ms 时 TCP 预筛必须收到 6.0s，而不是常量默认值。"""
    recorded = []
    original = pm.test_tcp_latency
    pm.test_tcp_latency = lambda h, p, t=None: recorded.append(t) or 12
    try:
        manager = pm.ProxyManager(max_tg_latency_ms=5000)
        manager._test_entries([{"host": "1.2.3.4", "port": 1080}])
    finally:
        pm.test_tcp_latency = original
    assert recorded == [6.0], "预筛没收到派生超时：%r" % (recorded,)


if __name__ == "__main__":
    import inspect
    fns = [(n, f) for n, f in sorted(globals().items())
           if n.startswith("test_") and inspect.isfunction(f)]
    for name, fn in fns:
        fn()
        print("PASS", name)
    print("ALL %d TESTS PASSED" % len(fns))
