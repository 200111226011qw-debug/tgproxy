# -*- coding: utf-8 -*-
"""MTProto 异步引擎：obfuscated2 握手 + req_pq_multi + resPQ，走 asyncio streams。

设计边界（与 Phase 1 的分工）：
- 字节级协议裁决全部复用 `mtproto_handshake` 的纯函数（密钥派生 / 头构造 / 帧 /
  resPQ 解析），本模块只重写「怎么读写 socket」这一层。
- `read_frame_async` 与 sync 版 `read_frame` 是两份实现，重复约 12 行。
  这是刻意的：换来的 Phase 1 冻结文件零改动。两者由 `tests/test_frame_parity.py`
  做 1000 组模糊对拍，防止悄悄漂移。

Windows 实测注意事项（踩过，勿改）：
- 默认事件循环是 ProactorEventLoop，`loop.add_reader()` 直接抛 NotImplementedError；
  所以这里只用 `open_connection` + `StreamReader.readexactly`，不自造 transport。
- `asyncio.IncompleteReadError` **不是** OSError。不单独捕获会掉进兜底分支，
  错误码从 PROTOCOL_ERROR 漂移成 INTERNAL_ERROR。
- `asyncio.timeout` / `wait_for` 抛的都是内建 TimeoutError，无法靠异常类型区分
  「连接超时」与「响应超时」；只能靠 phase 标记裁决。
- 定时器粒度约 15.6ms，测试断言必须留 ≥100ms 余量，禁止断言精确耗时。
"""

from __future__ import annotations

import asyncio
import os
import socket
import struct
import time
from dataclasses import dataclass
from typing import Any, Callable, Optional, Sequence

from mtproto_handshake import (
    DEFAULT_DC,
    INNER_MODE,
    MAX_FRAME,
    CheckResult,
    ErrorCode,
    MtprotoError,
    ParsedSecret,
    aes_ctr,
    derive_keys,
    frame_message,
    make_obfuscated2_handshake,
    make_unencrypted_req_pq_multi,
    parse_res_pq,
    parse_secret,
)

DEFAULT_CONCURRENCY = 200
DEFAULT_TOTAL_BUDGET = 2.0
DEFAULT_CONNECT_BUDGET = 1.0
# 超过这个规模就分块，避免 waiter 队列与 Task 对象无限膨胀
CHUNK_THRESHOLD = 5000
CHUNK_FACTOR = 4


# ---------------------------------------------------------------------------
# 传输层


class AsyncSocketTransport:
    """只补 `read_exact`，读写复用 StreamReader / StreamWriter。"""

    __slots__ = ("_reader", "_writer")

    def __init__(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        self._reader = reader
        self._writer = writer

    def write(self, data: bytes) -> None:
        self._writer.write(data)

    async def drain(self) -> None:
        await self._writer.drain()

    async def read_exact(self, n: int) -> bytes:
        try:
            return await self._reader.readexactly(n)
        except asyncio.IncompleteReadError as exc:
            # 不是 OSError —— 必须在这里翻译，否则错误码会漂移成 INTERNAL_ERROR
            raise MtprotoError(ErrorCode.PROTOCOL_ERROR, "连接被对端关闭") from exc


async def read_frame_async(
    transport: AsyncSocketTransport,
    dec: Any,
    mode: str = INNER_MODE,
) -> bytes:
    """`mtproto_handshake.read_frame` 的异步孪生实现（语义逐项对齐）。"""
    if mode in ("secure", "intermediate"):
        length_bytes = dec.update(await transport.read_exact(4))
        frame_len = struct.unpack("<I", length_bytes)[0]
        if frame_len > 0x80000000:
            frame_len -= 0x80000000
        if frame_len <= 0 or frame_len > MAX_FRAME:
            raise MtprotoError(
                ErrorCode.PROTOCOL_ERROR,
                f"帧长度非法: {frame_len}（解密后长度字节 {length_bytes.hex()}）",
            )
        return dec.update(await transport.read_exact(frame_len))

    if mode == "abridged":
        first = dec.update(await transport.read_exact(1))[0]
        if first < 127:
            frame_len = first * 4
        else:
            rest = dec.update(await transport.read_exact(3))
            frame_len = struct.unpack("<I", rest + b"\x00")[0] * 4
        if frame_len <= 0 or frame_len > MAX_FRAME:
            raise MtprotoError(ErrorCode.PROTOCOL_ERROR, f"abridged 帧长度非法: {frame_len}")
        return dec.update(await transport.read_exact(frame_len))

    raise MtprotoError(ErrorCode.UNSUPPORTED_MODE, f"不支持的模式 {mode}")


async def _aclose(writer: asyncio.StreamWriter) -> None:
    """close → wait_closed(0.5s) → 失败则 abort，避免句柄泄漏。"""
    try:
        writer.close()
    except Exception:
        pass
    try:
        await asyncio.wait_for(writer.wait_closed(), 0.5)
        return
    except BaseException:
        # 取消 / 超时 / 已关闭都在这里收口；真正的兜底是 abort
        pass
    try:
        transport = writer.transport
        if transport is not None:
            transport.abort()
    except Exception:
        pass


# ---------------------------------------------------------------------------
# 单节点


def _is_ipv4_literal(host: str) -> bool:
    try:
        socket.inet_aton(host)
    except OSError:
        return False
    return host.count(".") == 3


def _invalid(host: str, port: int, kind: str, dc_id: int, code: str, message: str) -> CheckResult:
    return CheckResult(
        False, str(host), port, kind, dc_id, error_code=code, error_message=message
    )


async def _check_parsed_async(
    parsed: ParsedSecret,
    host: str,
    port: Any,
    *,
    dc_id: int = DEFAULT_DC,
    total_budget: float = DEFAULT_TOTAL_BUDGET,
    connect_budget: Optional[float] = None,
    coalesce: bool = True,
    rng: Callable[[int], bytes] = os.urandom,
    clock: Callable[[], float] = time.time,
) -> CheckResult:
    kind = parsed.kind

    if not host:
        return _invalid(host, 0, kind, dc_id, ErrorCode.INVALID_INPUT, "host 为空")

    try:
        port_int = int(port)
    except (TypeError, ValueError):
        return _invalid(host, 0, kind, dc_id, ErrorCode.INVALID_INPUT, f"端口非法: {port!r}")
    if not 1 <= port_int <= 65535:
        return _invalid(
            host, port_int, kind, dc_id, ErrorCode.INVALID_INPUT, f"端口越界: {port_int}"
        )

    if connect_budget is None:
        connect_budget = min(total_budget * 0.5, DEFAULT_CONNECT_BUDGET)

    phase = "connect"
    started = time.monotonic()
    writer: Optional[asyncio.StreamWriter] = None

    try:
        async with asyncio.timeout(total_budget):
            family = socket.AF_INET if _is_ipv4_literal(host) else 0
            reader, writer = await asyncio.wait_for(
                asyncio.open_connection(host, port_int, family=family),
                timeout=connect_budget,
            )

            phase = "handshake"
            transport = AsyncSocketTransport(reader, writer)

            init_packet, enc, dec = make_obfuscated2_handshake(
                parsed.raw, dc_id, rng=rng
            )
            nonce, request = make_unencrypted_req_pq_multi(rng=rng, clock=clock)
            encrypted_req = enc.update(frame_message(request, INNER_MODE, rng=rng))

            if coalesce:
                # 64B 头与首帧合并成一次 write：loopback 实测服务端恒为 1 次 recv
                transport.write(init_packet + encrypted_req)
                await transport.drain()
            else:
                transport.write(init_packet)
                await transport.drain()
                transport.write(encrypted_req)
                await transport.drain()

            phase = "read"
            frame = await read_frame_async(transport, dec, INNER_MODE)
            server_nonce = parse_res_pq(frame, nonce)

            phase = "close"
            elapsed_ms = (time.monotonic() - started) * 1000.0
            return CheckResult(
                True,
                str(host),
                port_int,
                kind,
                dc_id,
                rtt_ms=round(elapsed_ms, 1),
                server_nonce=server_nonce,
            )

    except TimeoutError:
        code = ErrorCode.CONNECT_TIMEOUT if phase == "connect" else ErrorCode.RESPONSE_TIMEOUT
        message = (
            f"连接超时（>{connect_budget}s）"
            if phase == "connect"
            else f"响应超时（总预算 {total_budget}s）"
        )
        return _invalid(host, port_int, kind, dc_id, code, message)
    except MtprotoError as exc:
        return _invalid(host, port_int, kind, dc_id, exc.code, exc.message)
    except OSError as exc:
        return _invalid(host, port_int, kind, dc_id, ErrorCode.NETWORK_ERROR, str(exc))
    except Exception as exc:
        return _invalid(
            host, port_int, kind, dc_id, ErrorCode.INTERNAL_ERROR,
            f"{type(exc).__name__}: {exc}",
        )
    finally:
        if writer is not None:
            await _aclose(writer)


async def check_mtproto_proxy_async(
    host: str,
    port: Any,
    secret: str,
    *,
    dc_id: int = DEFAULT_DC,
    total_budget: float = DEFAULT_TOTAL_BUDGET,
    connect_budget: Optional[float] = None,
    coalesce: bool = True,
    rng: Callable[[int], bytes] = os.urandom,
    clock: Callable[[], float] = time.time,
) -> CheckResult:
    """单节点 MTProto 真校验（异步）。永不抛异常，失败统一转 CheckResult。"""
    try:
        parsed = parse_secret(secret)
    except MtprotoError as exc:
        return _invalid(host, 0, "unknown", dc_id, exc.code, exc.message)

    return await _check_parsed_async(
        parsed,
        host,
        port,
        dc_id=dc_id,
        total_budget=total_budget,
        connect_budget=connect_budget,
        coalesce=coalesce,
        rng=rng,
        clock=clock,
    )


# ---------------------------------------------------------------------------
# 批量编排


@dataclass(frozen=True)
class ProxyNode:
    host: str
    port: int
    secret: str
    tag: Any = None


async def check_many(
    nodes: Sequence[ProxyNode],
    *,
    concurrency: int = DEFAULT_CONCURRENCY,
    total_budget: float = DEFAULT_TOTAL_BUDGET,
    dc_id: int = DEFAULT_DC,
    on_result: Optional[Callable[[int, CheckResult], None]] = None,
    rng: Callable[[int], bytes] = os.urandom,
    clock: Callable[[], float] = time.time,
) -> list[CheckResult]:
    """有界并发校验。返回顺序严格等于输入顺序（gather 保序，与完成顺序无关）。

    - `parse_secret` 在拿信号量之前做：非法 secret 不占槽位、不建连接。
    - 同一个 secret 只解析一次（免费池里重复 secret 很多）。
    - 不用 TaskGroup：任一子任务异常会取消其余全部，而「死节点是常态」，
      这里要的是逐节点隔离，不是快速失败。
    """
    items = list(nodes)
    if not items:
        return []

    limit = max(1, int(concurrency))
    semaphore = asyncio.Semaphore(limit)
    secret_cache: dict[str, Any] = {}

    def parse_cached(secret: str) -> Any:
        if secret not in secret_cache:
            try:
                secret_cache[secret] = parse_secret(secret)
            except MtprotoError as exc:
                secret_cache[secret] = exc
        return secret_cache[secret]

    async def one(index: int, node: ProxyNode) -> CheckResult:
        parsed = parse_cached(node.secret)
        if isinstance(parsed, MtprotoError):
            result = _invalid(
                node.host, 0, "unknown", dc_id, parsed.code, parsed.message
            )
        else:
            async with semaphore:
                result = await _check_parsed_async(
                    parsed,
                    node.host,
                    node.port,
                    dc_id=dc_id,
                    total_budget=total_budget,
                    rng=rng,
                    clock=clock,
                )

        if on_result is not None:
            try:
                on_result(index, result)
            except Exception:
                # 回调是 UI 增量刷新用的，不能让它打断整轮
                pass
        return result

    if len(items) > CHUNK_THRESHOLD:
        chunk = limit * CHUNK_FACTOR
        results: list[CheckResult] = []
        for start in range(0, len(items), chunk):
            batch = items[start : start + chunk]
            results.extend(
                await asyncio.gather(
                    *(one(start + offset, node) for offset, node in enumerate(batch))
                )
            )
        return results

    return list(await asyncio.gather(*(one(i, node) for i, node in enumerate(items))))


def check_many_sync(nodes: Sequence[ProxyNode], **kwargs: Any) -> list[CheckResult]:
    """同步壳子：桌面端现有线程模型零改造也能调用。"""
    return asyncio.run(check_many(nodes, **kwargs))


__all__ = [
    "AsyncSocketTransport",
    "DEFAULT_CONCURRENCY",
    "DEFAULT_TOTAL_BUDGET",
    "ProxyNode",
    "check_many",
    "check_many_sync",
    "check_mtproto_proxy_async",
    "read_frame_async",
]
