# -*- coding: utf-8 -*-
"""read_frame（sync）与 read_frame_async 的模糊对拍。

为什么需要：两者是两份实现（刻意不合并，换取 Phase 1 冻结文件零改动）。
只要有一方在「高位标记 / 非法长度 / abridged 两段式 / 截断」上处理不同，
异步侧的错误码就会与桌面版漂移，统计口径随之失真。

做法：同一段字节流喂给两个实现，断言「返回值逐字节相同」或「错误码相同」。
"""

import asyncio
import os
import random
import struct
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
_ROOT = os.path.dirname(_HERE)
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

import mtproto_async as ma  # noqa: E402
import mtproto_handshake as mh  # noqa: E402


class _Identity:
    def update(self, data):
        return data


class _SyncBytes:
    def __init__(self, data):
        self._buf = bytearray(data)

    def read_exact(self, n):
        if len(self._buf) < n:
            raise mh.MtprotoError(mh.ErrorCode.PROTOCOL_ERROR, "连接被对端关闭")
        out = bytes(self._buf[:n])
        del self._buf[:n]
        return out


class _AsyncBytes:
    def __init__(self, data):
        self._buf = bytearray(data)

    async def read_exact(self, n):
        if len(self._buf) < n:
            raise mh.MtprotoError(mh.ErrorCode.PROTOCOL_ERROR, "连接被对端关闭")
        out = bytes(self._buf[:n])
        del self._buf[:n]
        return out


def _sync_case(data, mode):
    try:
        return ("ok", mh.read_frame(_SyncBytes(data), _Identity(), mode))
    except mh.MtprotoError as exc:
        return ("err", exc.code)


async def _async_case(data, mode):
    try:
        return ("ok", await ma.read_frame_async(_AsyncBytes(data), _Identity(), mode))
    except mh.MtprotoError as exc:
        return ("err", exc.code)


def _make_cases(seed=20261104, count=1000):
    rng = random.Random(seed)
    cases = []

    # secure 合法帧：长度含 padding
    for _ in range(400):
        payload = bytes(rng.getrandbits(8) for _ in range(rng.randint(1, 200)))
        pad = rng.randint(0, 3)
        cases.append((struct.pack("<I", len(payload) + pad) + payload + b"\x00" * pad, "secure"))

    # intermediate 合法帧
    for _ in range(200):
        payload = bytes(rng.getrandbits(8) for _ in range(rng.randint(1, 200)))
        cases.append((struct.pack("<I", len(payload)) + payload, "intermediate"))

    # abridged 合法帧（短长度 / 长长度两段式）
    for i in range(200):
        words = rng.randint(1, 200) if i % 2 == 0 else rng.randint(127, 500)
        payload = bytes(rng.getrandbits(8) for _ in range(words * 4))
        header = (
            struct.pack("<B", words)
            if words < 127
            else b"\x7f" + struct.pack("<I", words)[:3]
        )
        cases.append((header + payload, "abridged"))

    # 非法长度：0 / 超出上限 / 高位标记
    for _ in range(100):
        choice = rng.choice(["zero", "huge", "highbit"])
        if choice == "zero":
            data = struct.pack("<I", 0)
        elif choice == "huge":
            data = struct.pack("<I", mh.MAX_FRAME + rng.randint(1, 1000))
        else:
            n = rng.randint(1, 500)
            data = struct.pack("<I", 0x80000000 | n) + bytes(rng.getrandbits(8) for _ in range(n))
        cases.append((data, rng.choice(["secure", "intermediate"])))

    # 截断：声明长度大于实际字节
    for _ in range(100):
        declared = rng.randint(10, 200)
        actual = rng.randint(0, declared - 1)
        data = struct.pack("<I", declared) + b"\xaa" * actual
        cases.append((data, rng.choice(["secure", "intermediate"])))

    assert len(cases) == count
    return cases


def test_sync_and_async_frame_readers_agree_on_1000_cases():
    cases = _make_cases()

    async def run_all():
        out = []
        for data, mode in cases:
            out.append(await _async_case(data, mode))
        return out

    async_results = asyncio.run(run_all())

    mismatches = []
    for index, ((data, mode), async_result) in enumerate(zip(cases, async_results)):
        sync_result = _sync_case(data, mode)
        if sync_result != async_result:
            mismatches.append((index, mode, data[:16].hex(), sync_result, async_result))

    assert not mismatches, "前 5 条漂移: %r" % (mismatches[:5],)


def test_parity_covers_both_outcomes():
    """防止用例退化成「全部报错」或「全部成功」的假绿。"""
    cases = _make_cases(count=1000)
    outcomes = {_sync_case(data, mode)[0] for data, mode in cases}
    assert outcomes == {"ok", "err"}


def test_unsupported_mode_agrees():
    assert _sync_case(b"", "nope") == ("err", mh.ErrorCode.UNSUPPORTED_MODE)
    assert asyncio.run(_async_case(b"", "nope")) == ("err", mh.ErrorCode.UNSUPPORTED_MODE)


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
