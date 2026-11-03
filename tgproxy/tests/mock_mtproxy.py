# -*- coding: utf-8 -*-
"""本地 mock MTProxy 服务端：只用于离线端到端验证，不参与生产。

它站在真实 MTProxy 服务端的视角工作：
1. 收 64 字节 init，用**前 56 字节明文**重算客户端 enc/dec 密钥；
2. 用客户端 enc 密钥解出后续帧（等价服务端的 decryptor）；
3. 校验 req_pq_multi 的 constructor 与 nonce；
4. 用客户端 dec 密钥加密一条 resPQ（回显 nonce）发回。

为什么要它：沙箱到 Telegram DC（149.154.167.51:443）不通，
而静态向量只能验证「片段」，验证不了「客户端实现 + 服务端约定」是否闭环。
这个 mock 能在 127.0.0.1 上把整条链路跑通，且完全离线。
"""

from __future__ import annotations

import hashlib
import os
import socket
import struct
import sys
import threading
import time
import os as _os

_HERE = _os.path.dirname(_os.path.abspath(__file__))
_ROOT = _os.path.dirname(_HERE)
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


class MockMtproxyServer:
    """上下文管理器：with MockMtproxyServer(secret) as srv: ... srv.port"""

    def __init__(
        self,
        secret: bytes,
        dc_id: int = 2,
        *,
        corrupt_nonce: bool = False,
        silent: bool = False,
        hold_s: float = 1.0,
        wrong_secret: bool = False,
        server_nonce: bytes = b"S" * 16,
    ) -> None:
        if len(secret) != 16:
            raise ValueError("secret 必须 16 字节")
        self._secret = (
            bytes(b ^ 0xFF for b in secret) if wrong_secret else bytes(secret)
        )
        self._dc_id = dc_id
        self._corrupt_nonce = corrupt_nonce
        self._silent = silent
        self._hold_s = hold_s
        self._server_nonce = server_nonce

        self._listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._listener.bind(("127.0.0.1", 0))
        self._listener.listen(8)
        self._port = self._listener.getsockname()[1]

        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._serve, name="mock-mtproxy", daemon=True)
        self.errors: list[BaseException] = []
        self.served = 0

    @property
    def port(self) -> int:
        return self._port

    def __enter__(self) -> "MockMtproxyServer":
        self._thread.start()
        return self

    def __exit__(self, *exc_info) -> bool:
        self._stop.set()
        try:
            self._listener.close()
        except OSError:
            pass
        self._thread.join(timeout=3.0)
        return False

    # -- 内部 -------------------------------------------------------------

    def _serve(self) -> None:
        while not self._stop.is_set():
            try:
                conn, _addr = self._listener.accept()
            except OSError:
                return
            try:
                self._handle(conn)
                self.served += 1
            except BaseException as exc:  # 记录但不断服务
                self.errors.append(exc)
            finally:
                try:
                    conn.close()
                except OSError:
                    pass

    @staticmethod
    def _recv_exact(conn: socket.socket, n: int) -> bytes:
        out = bytearray()
        while len(out) < n:
            chunk = conn.recv(n - len(out))
            if not chunk:
                raise ConnectionError(f"对端提前关闭，剩余 {n - len(out)} 字节未读")
            out += chunk
        return bytes(out)

    def _handle(self, conn: socket.socket) -> None:
        conn.settimeout(5.0)

        init = self._recv_exact(conn, 64)

        # 客户端用 init[8:40]+secret / init[40:56] 加密；
        # 用 init[55:23:-1]+secret / init[23:7:-1] 解密。
        enc_key, enc_iv, dec_key, dec_iv = derive_keys(init, self._secret)
        dec = aes_ctr(enc_key, enc_iv)   # 解客户端发来的
        enc = aes_ctr(dec_key, dec_iv)   # 加密回包

        # 客户端把完整 64B 都过了 cipher，但只有 56:64 是密文。
        # 这里同样解完整 64B：0:56 解出垃圾无所谓，目的是把状态推进 64B。
        plain_init = dec.update(init)
        if plain_init[56:60] != PROTO_SECURE:
            raise AssertionError(
                f"proto tag 不匹配: {plain_init[56:60].hex()}（secret 错误？）"
            )
        if plain_init[60] != (self._dc_id & 0xFF):
            raise AssertionError(f"DC 不匹配: {plain_init[60]}")

        frame_len = struct.unpack("<I", dec.update(self._recv_exact(conn, 4)))[0]
        if frame_len <= 0 or frame_len > MAX_FRAME:
            raise AssertionError(f"帧长度非法: {frame_len}")

        payload = dec.update(self._recv_exact(conn, frame_len))  # = req + padding
        nonce = self._extract_nonce(payload)

        if self._silent:
            # 不回包，但要保持连接让客户端自己超时（立刻 close 会变成 PROTOCOL_ERROR）
            self._stop.wait(self._hold_s)
            return

        echoed = b"\x00" * 16 if self._corrupt_nonce else nonce
        inner = struct.pack("<I", RES_PQ) + echoed + self._server_nonce
        # 真实 MTProto 包 = auth_key_id(8) + msg_id(8) + msg_len(4) + body
        # 少了这 20 字节包头，客户端的 parse_res_pq 会判「过短」。
        msg_id = int(time.time() * (2 ** 32)) & ~3
        packet = (
            b"\x00" * 8
            + struct.pack("<Q", msg_id)
            + struct.pack("<I", len(inner))
            + inner
        )
        conn.sendall(enc.update(struct.pack("<I", len(packet)) + packet))

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
