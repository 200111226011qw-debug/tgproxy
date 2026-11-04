# -*- coding: utf-8 -*-
"""并发首次解析 AES 后端时不得出现「假 CRYPTO_UNAVAILABLE」。

背景（实测复现）：原 `_aes_backend()` 先把 `_AES_RESOLVED = True`，再去
import `cryptography`。首次 import 要加载 OpenSSL DLL（几十到几百毫秒），
这期间别的线程看到 `_AES_RESOLVED` 已置位，直接返回仍是 `None` 的
`_AES_BACKEND`，于是被判 `CRYPTO_UNAVAILABLE`。

生产影响：程序刚启动的第一轮刷新，300 并发下几乎只有 1 个 MTProto 节点
能出结果，其余全部落进「无法判定」，看起来像打包漏了 cryptography。
"""

import os
import sys
import threading
import time

_HERE = os.path.dirname(os.path.abspath(__file__))
_ROOT = os.path.dirname(_HERE)
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

import mtproto_handshake as mh  # noqa: E402


def _reset():
    mh._AES_RESOLVED = False
    mh._AES_BACKEND = None


def _run_concurrently(n, fn):
    barrier = threading.Barrier(n)
    out = []
    errors = []
    lock = threading.Lock()

    def worker():
        barrier.wait()
        try:
            value = fn()
            with lock:
                out.append(value)
        except BaseException as exc:  # noqa: BLE001 - 收集下来断言
            with lock:
                errors.append(getattr(exc, "code", None) or type(exc).__name__)

    threads = [threading.Thread(target=worker) for _ in range(n)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    return out, errors


def test_concurrent_first_resolve_never_false_negative():
    """模拟首次 import 的耗时窗口，16 线程同时首次调用不得报 CRYPTO_UNAVAILABLE。"""
    _reset()
    orig = mh._load_aes_factory

    def slow_factory():
        time.sleep(0.05)  # 真实场景是 import cryptography + 加载 libcrypto DLL
        return orig()

    mh._load_aes_factory = slow_factory
    try:
        values, errors = _run_concurrently(
            16, lambda: mh.aes_ctr(b"\x00" * 32, b"\x00" * 16).update(b"A" * 16)
        )
    finally:
        mh._load_aes_factory = orig
        _reset()

    assert not errors, "并发首次解析出现异常码：%r" % (errors,)
    assert len(values) == 16
    assert len(set(values)) == 1, "同一 key/iv 下密文应完全一致"


def test_resolved_state_only_visible_after_backend_filled():
    """不变量：一旦 _AES_RESOLVED 为 True，_AES_BACKEND 必须已确定（非「还没填」）。"""
    _reset()
    factory = mh._aes_backend()
    assert factory is not None
    assert mh._AES_RESOLVED is True
    assert mh._AES_BACKEND is factory
    _reset()


def test_backend_cached_across_calls():
    """解析成功后应命中缓存，不重复 import。"""
    _reset()
    calls = []
    orig = mh._load_aes_factory

    def counting():
        calls.append(1)
        return orig()

    mh._load_aes_factory = counting
    try:
        for _ in range(5):
            mh.aes_ctr(b"\x00" * 32, b"\x00" * 16)
    finally:
        mh._load_aes_factory = orig
        _reset()

    assert len(calls) == 1, "应只解析一次，实际 %d 次" % len(calls)


if __name__ == "__main__":
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            try:
                fn()
                print("PASS", name)
            except AssertionError as exc:
                print("FAIL", name, exc)
