# -*- coding: utf-8 -*-
"""本地 HTTP mock：用于离线验证镜像竞速的完整性判定。

覆盖五种真实世界里会遇到的服务端行为：
- ok         正常 200 + Content-Length 正确
- truncated  Content-Length 声明得比实际大（最常见的「半截响应」）
- empty      200 但 body 为空
- blackhole  接受连接、读完请求、永不响应（对应「直连被墙」）
- status     非 200
"""

from __future__ import annotations

import asyncio
import os
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))


class AsyncHttpMock:
    def __init__(
        self,
        body: bytes = b"".join(b"%d.%d.%d.%d:1080\n" % (i, i, i, i) for i in range(1, 11)),
        *,
        mode: str = "ok",
        status: int = 200,
        delay_ms: float = 0.0,
        hold_s: float = 5.0,
        declared_length: int | None = None,
        host: str = "127.0.0.1",
        port: int = 0,
    ) -> None:
        self._body = body
        self._mode = mode
        self._status = status
        self._delay_s = max(0.0, delay_ms) / 1000.0
        self._hold_s = hold_s
        self._declared_length = declared_length
        self._host = host
        self._requested_port = port
        self._server: asyncio.AbstractServer | None = None
        self._tasks: set[asyncio.Task] = set()

    @property
    def port(self) -> int:
        if self._server is None or not self._server.sockets:
            return self._requested_port
        return self._server.sockets[0].getsockname()[1]

    async def start(self) -> "AsyncHttpMock":
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

    async def __aenter__(self) -> "AsyncHttpMock":
        return await self.start()

    async def __aexit__(self, *exc_info) -> bool:
        await self.stop()
        return False

    def _on_client(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        task = asyncio.create_task(self._handle(reader, writer))
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)

    async def _handle(
        self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter
    ) -> None:
        try:
            try:
                await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), 2.0)
            except Exception:
                pass

            if self._mode == "blackhole":
                await asyncio.sleep(self._hold_s)
                return

            if self._delay_s:
                await asyncio.sleep(self._delay_s)

            if self._mode == "truncated":
                declared = self._declared_length or (len(self._body) + 100)
                head = (
                    "HTTP/1.1 200 OK\r\n"
                    f"Content-Length: {declared}\r\n"
                    "Connection: close\r\n\r\n"
                )
                writer.write(head.encode("ascii"))
                writer.write(self._body[: max(1, len(self._body) // 4)])
                await writer.drain()
                return

            if self._mode == "empty":
                head = (
                    "HTTP/1.1 200 OK\r\n"
                    "Content-Length: 0\r\n"
                    "Connection: close\r\n\r\n"
                )
                writer.write(head.encode("ascii"))
                await writer.drain()
                return

            status = self._status if self._mode == "status" else 200
            reason = "OK" if status == 200 else "Mock Error"
            head = (
                f"HTTP/1.1 {status} {reason}\r\n"
                f"Content-Length: {len(self._body)}\r\n"
                "Content-Type: text/plain; charset=utf-8\r\n"
                "Connection: close\r\n\r\n"
            )
            writer.write(head.encode("ascii"))
            writer.write(self._body)
            await writer.drain()
        except asyncio.CancelledError:
            raise
        except Exception:
            pass
        finally:
            try:
                writer.close()
                await asyncio.wait_for(writer.wait_closed(), 0.5)
            except BaseException:
                try:
                    writer.transport.abort()
                except Exception:
                    pass
