# -*- coding: utf-8 -*-
"""asyncio 版 mock MTProxy 服务端 / 服务端集群。

为什么不用 tests/mock_mtproxy.py 的线程版做基准：
100 个线程版 mock 会与客户端抢 GIL，测出来的「异步 vs 线程」差异里有一半是
GIL 抖动，不是引擎差异。基准一律用本模块；线程版只作为「服务端最坏情况」标注。

协议逻辑与线程版逐字节一致（同一套 derive_keys / aes_ctr / resPQ 构造）。
"""

from __future__ import annotations

import asyncio
import os
import struct
import sys
import time

_HERE = os.path.dirname(os.path.abspath(__file__))
_ROOT = os.path.dirname(_HERE)
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

from mtproto_handshake import (  # noqa: E402
    MAX_FRAME,
    PROTO_SECURE,
    REQ_PQ_MULTI,
    RES_PQ,
    aes_ctr,
    derive_keys,
)


class AsyncMockMtproxyServer:
    def __init__(
        self,
        secret: bytes,
        dc_id: int = 2,
        *,
        corrupt_nonce: bool = False,
        silent: bool = False,
        hold_s: float = 1.5,
        wrong_secret: bool = False,
        server_nonce: bytes = b"S" * 16,
        delay_ms: float = 0.0,
        host: str = "127.0.0.1",
        port: int = 0,
    ) -> None:
        if len(secret) != 16:
            raise ValueError("secret 必须 16 字节")
        self._secret = bytes(b ^ 0xFF for b in secret) if wrong_secret else bytes(secret)
        self._dc_id = dc_id
        self._corrupt_nonce = corrupt_nonce
        self._silent = silent
        self._hold_s = hold_s
        self._server_nonce = server_nonce
        self._delay_s = max(0.0, delay_ms) / 1000.0
        self._host = host
        self._requested_port = port

        self._server: asyncio.AbstractServer | None = None
        self._tasks: set[asyncio.Task] = set()
        self.errors: list[BaseException] = []
        self.served = 0
        # 用于断言「峰值并发不超过信号量上限」——在服务端量，比 monkeypatch 客户端更真实
        self.active = 0
        self.peak_active = 0

    @property
    def port(self) -> int:
        if self._server is None or not self._server.sockets:
            return self._requested_port
        return self._server.sockets[0].getsockname()[1]

    async def start(self) -> "AsyncMockMtproxyServer":
        self._server = await asyncio.start_server(
            self._on_client, self._host, self._requested_port
        )
        return self

    async def stop(self) -> None:
        if self._server is not None:
            self._server.close()
            try:
                await self._server.wait_closed()
            except Exception:
                pass
            self._server = None
        for task in list(self._tasks):
            task.cancel()
        if self._tasks:
            await asyncio.gather(*self._tasks, return_exceptions=True)

    async def __aenter__(self) -> "AsyncMockMtproxyServer":
        return await self.start()

    async def __aexit__(self, *exc_info) -> bool:
        await self.stop()
        return False

    # -- 内部 -------------------------------------------------------------

    def _on_client(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        task = asyncio.create_task(self._handle(reader, writer))
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)

    async def _handle(
        self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter
    ) -> None:
        self.active += 1
        if self.active > self.peak_active:
            self.peak_active = self.active
        try:
            init = await reader.readexactly(64)

            if self._delay_s:
                await asyncio.sleep(self._delay_s)

            enc_key, enc_iv, dec_key, dec_iv = derive_keys(init, self._secret)
            dec = aes_ctr(enc_key, enc_iv)   # 解客户端发来的
            enc = aes_ctr(dec_key, dec_iv)   # 加密回包

            plain_init = dec.update(init)
            if plain_init[56:60] != PROTO_SECURE:
                raise AssertionError(
                    f"proto tag 不匹配: {plain_init[56:60].hex()}（secret 错误？）"
                )
            if plain_init[60] != (self._dc_id & 0xFF):
                raise AssertionError(f"DC 不匹配: {plain_init[60]}")

            frame_len = struct.unpack("<I", dec.update(await reader.readexactly(4)))[0]
            if frame_len <= 0 or frame_len > MAX_FRAME:
                raise AssertionError(f"帧长度非法: {frame_len}")

            payload = dec.update(await reader.readexactly(frame_len))
            nonce = self._extract_nonce(payload)

            if self._silent:
                await asyncio.sleep(self._hold_s)
                return

            echoed = b"\x00" * 16 if self._corrupt_nonce else nonce
            inner = struct.pack("<I", RES_PQ) + echoed + self._server_nonce
            msg_id = int(time.time() * (2 ** 32)) & ~3
            packet = (
                b"\x00" * 8
                + struct.pack("<Q", msg_id)
                + struct.pack("<I", len(inner))
                + inner
            )
            writer.write(enc.update(struct.pack("<I", len(packet)) + packet))
            await writer.drain()
            self.served += 1
        except asyncio.CancelledError:
            raise
        except BaseException as exc:
            self.errors.append(exc)
        finally:
            self.active -= 1
            try:
                writer.close()
                await asyncio.wait_for(writer.wait_closed(), 0.5)
            except BaseException:
                try:
                    writer.transport.abort()
                except Exception:
                    pass

    @staticmethod
    def _extract_nonce(payload: bytes) -> bytes:
        if len(payload) < 40:
            raise AssertionError(f"req 载荷过短: {len(payload)}")
        if payload[:8] != b"\x00" * 8:
            raise AssertionError("req 不是未加密 MTProto 包")
        msg_len = struct.unpack("<I", payload[16:20])[0]
        if msg_len <= 0 or msg_len > len(payload) - 20:
            raise AssertionError(f"req msg_len 非法: {msg_len}")
        inner = payload[20 : 20 + msg_len]
        constructor = struct.unpack("<I", inner[:4])[0]
        if constructor != REQ_PQ_MULTI:
            raise AssertionError(f"constructor 不是 req_pq_multi: 0x{constructor:08x}")
        return inner[4:20]


class AsyncMockFarm:
    """在固定端口段上起 count 个 mock 节点。

    端口固定而不是 bind(0)：Windows 临时端口只有 16384 个（49152 起），
    且客户端源端口与监听端口共用一个池 —— 自动分配会挤压客户端端口。
    """

    def __init__(
        self,
        count: int,
        secret: bytes,
        *,
        base_port: int = 20000,
        **kwargs,
    ) -> None:
        self._count = count
        self._secret = secret
        self._base_port = base_port
        self._kwargs = kwargs
        self._servers: list[AsyncMockMtproxyServer] = []
        self._ports: list[int] = []

    @property
    def ports(self) -> list[int]:
        return list(self._ports)

    @property
    def errors(self) -> list[BaseException]:
        out: list[BaseException] = []
        for server in self._servers:
            out.extend(server.errors)
        return out

    async def start(self) -> "AsyncMockFarm":
        port = self._base_port
        for _ in range(self._count):
            server = None
            for _attempt in range(64):
                candidate = AsyncMockMtproxyServer(
                    self._secret, port=port, **self._kwargs
                )
                try:
                    await candidate.start()
                except OSError:
                    port += 1
                    continue
                server = candidate
                break
            if server is None:
                await self.stop()
                raise OSError(f"从 {self._base_port} 起找不到可用端口")
            self._servers.append(server)
            self._ports.append(server.port)
            port += 1
        return self

    async def stop(self) -> None:
        for server in self._servers:
            await server.stop()
        self._servers.clear()

    async def __aenter__(self) -> "AsyncMockFarm":
        return await self.start()

    async def __aexit__(self, *exc_info) -> bool:
        await self.stop()
        return False
