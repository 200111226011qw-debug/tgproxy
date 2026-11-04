# -*- coding: utf-8 -*-
"""MTProto(MTProxy) 真校验：obfuscated2 握手 + req_pq_multi + resPQ nonce 校验。

Phase 1 覆盖 raw 16 字节 与 dd 开头的 secure secret；ee(FakeTLS) 留待 Phase 1b。

密码学边界（刻意为之）：
- AES-256-CTR 只调用成熟库，绝不手写。优先 cryptography，退路 pycryptodome。
- 两者都不可用时抛 CRYPTO_UNAVAILABLE，调用方据此把节点标为「未验证」，
  而不是用错误的算法给出一个假的通过结论。

与参考实现的对应关系（字节级）：
- 头布局 / 密钥派生 / 帧格式 / resPQ 解析 均对齐 alimghmi/mtproxychecker
  的 protocol.py；语义对齐 @erfan_bahramali/tping（真实握手而非端口探活）。
"""

from __future__ import annotations

import base64
import hashlib
import os
import socket
import struct
import threading
import time
from dataclasses import dataclass
from typing import Any, Callable, Optional

# ---------------------------------------------------------------------------
# 协议常量

PROTO_ABRIDGED = b"\xef\xef\xef\xef"
PROTO_INTERMEDIATE = b"\xee\xee\xee\xee"
PROTO_SECURE = b"\xdd\xdd\xdd\xdd"

REQ_PQ_MULTI = 0xBE7E8EF1
RES_PQ = 0x05162463

MAX_FRAME = 2 * 1024 * 1024
DEFAULT_DC = 2
INNER_MODE = "secure"
FAKETLS_DEFAULT_DOMAIN = "www.google.com"


class ErrorCode:
    """CLI / 上层可直接展示的错误码，避免把异常栈丢给用户。"""

    INVALID_INPUT = "INVALID_INPUT"
    INVALID_SECRET = "INVALID_SECRET"
    UNSUPPORTED_MODE = "UNSUPPORTED_MODE"
    # ee/FakeTLS 尚未实现：既不能算成功，也不能算「节点失败」—— 记为「无法判定」，
    # 上游据此保持「未验证」，避免把一大批主流格式的节点误判失败后连败拉黑。
    FAKETLS_UNSUPPORTED = "FAKETLS_UNSUPPORTED"
    CONNECT_TIMEOUT = "CONNECT_TIMEOUT"
    RESPONSE_TIMEOUT = "RESPONSE_TIMEOUT"
    NETWORK_ERROR = "NETWORK_ERROR"
    PROTOCOL_ERROR = "PROTOCOL_ERROR"
    CRYPTO_UNAVAILABLE = "CRYPTO_UNAVAILABLE"
    INTERNAL_ERROR = "INTERNAL_ERROR"


class MtprotoError(Exception):
    """带错误码的协议异常。CLI 只打印 code + message，不打印栈。"""

    def __init__(self, code: str, message: str = "") -> None:
        self.code = code
        self.message = message or code
        super().__init__(self.message)


# ---------------------------------------------------------------------------
# AES-256-CTR 适配层


class AesCtrStream:
    """统一 cryptography 的 update() 与 pycryptodome 的 encrypt() 语义。"""

    __slots__ = ("_impl", "_method", "backend")

    def __init__(self, impl: Any, method: str, backend: str) -> None:
        self._impl = impl
        self._method = method
        self.backend = backend

    def update(self, data: bytes) -> bytes:
        return getattr(self._impl, self._method)(data)


_AES_BACKEND: Optional[Callable[[bytes, bytes], tuple]] = None
_AES_RESOLVED = False
# 首次解析要 import cryptography 并加载 OpenSSL DLL（几十到几百毫秒）。
# 这里的锁不是性能优化，是正确性要求：300 并发下若先置 _AES_RESOLVED 再填
# _AES_BACKEND，等待窗口内到达的线程会读到 None，从而误判 CRYPTO_UNAVAILABLE。
_AES_LOCK = threading.Lock()


def _load_aes_factory() -> Optional[Callable[[bytes, bytes], tuple]]:
    """探测可用的 AES 后端，无可用时返回 None。不做缓存（由 _aes_backend 加锁缓存）。"""
    try:
        from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

        def _new(key: bytes, iv: bytes) -> tuple:
            stream = Cipher(algorithms.AES(key), modes.CTR(iv)).encryptor()
            return stream, "update", "cryptography"

        return _new
    except Exception:
        pass

    try:
        from Crypto.Cipher import AES
        from Crypto.Util import Counter

        def _new(key: bytes, iv: bytes) -> tuple:
            counter = Counter.new(128, initial_value=int.from_bytes(iv, "big"))
            stream = AES.new(key, AES.MODE_CTR, counter=counter)
            return stream, "encrypt", "pycryptodome"

        return _new
    except Exception:
        pass

    return None


def _aes_backend() -> Optional[Callable[[bytes, bytes], tuple]]:
    global _AES_BACKEND, _AES_RESOLVED
    if _AES_RESOLVED:
        return _AES_BACKEND
    with _AES_LOCK:
        if _AES_RESOLVED:  # 双检：等锁期间可能已被别的线程解析完
            return _AES_BACKEND
        _AES_BACKEND = _load_aes_factory()
        # 必须最后置位：任何看到 _AES_RESOLVED=True 的线程，都能拿到终值
        # （含「确实没有可用后端」的 None），不会读到半成品状态。
        _AES_RESOLVED = True
        return _AES_BACKEND


def aes_ctr(key: bytes, iv: bytes) -> AesCtrStream:
    if len(key) != 32:
        raise MtprotoError(ErrorCode.INTERNAL_ERROR, f"AES-256 需要 32 字节 key，得到 {len(key)}")
    if len(iv) != 16:
        raise MtprotoError(ErrorCode.INTERNAL_ERROR, f"AES-CTR 需要 16 字节 iv，得到 {len(iv)}")
    factory = _aes_backend()
    if factory is None:
        raise MtprotoError(
            ErrorCode.CRYPTO_UNAVAILABLE,
            "缺少 AES 实现：请安装 cryptography 或 pycryptodome",
        )
    impl, method, backend = factory(key, iv)
    return AesCtrStream(impl, method, backend)


def aes_backend_name() -> str:
    """当前生效的 AES 后端（cryptography / pycryptodome / 空）。"""
    factory = _aes_backend()
    if factory is None:
        return ""
    _impl, _method, backend = factory(b"\x00" * 32, b"\x00" * 16)
    return backend


# ---------------------------------------------------------------------------
# secret 解析


@dataclass(frozen=True)
class ParsedSecret:
    raw: bytes                 # 固定 16 字节
    kind: str                  # "raw" | "dd" | "ee"
    display: str
    faketls_domain: str = ""


def _secret_from_base64(text: str) -> ParsedSecret:
    normalized = text.replace("-", "+").replace("_", "/")
    padding = "=" * ((4 - len(normalized) % 4) % 4)
    try:
        raw = base64.b64decode(normalized + padding, validate=False)
    except Exception as exc:
        raise MtprotoError(
            ErrorCode.INVALID_SECRET, f"secret 既不是合法 hex 也不是 base64: {exc}"
        ) from exc

    if len(raw) == 16:
        return ParsedSecret(raw, "raw", raw.hex())
    if len(raw) >= 17 and raw[0] == 0xDD:
        return ParsedSecret(raw[1:17], "dd", "dd" + raw[1:17].hex())
    if len(raw) >= 17 and raw[0] == 0xEE:
        return ParsedSecret(raw[1:17], "ee", "ee" + raw[1:17].hex(), FAKETLS_DEFAULT_DOMAIN)
    if len(raw) > 16:
        return ParsedSecret(raw[:16], "raw", raw[:16].hex())

    raise MtprotoError(
        ErrorCode.INVALID_SECRET, f"base64 secret 解码为 {len(raw)} 字节，期望至少 16"
    )


def parse_secret(secret: str) -> ParsedSecret:
    """解析 tg://proxy 的 secret：raw / dd(secure) / ee(FakeTLS)。"""
    if not secret or not str(secret).strip():
        raise MtprotoError(ErrorCode.INVALID_SECRET, "secret 为空")

    text = str(secret).strip()
    lower = text.lower()

    try:
        raw_hex = bytes.fromhex(lower)
    except ValueError:
        return _secret_from_base64(text)

    if lower.startswith("ee"):
        if len(lower) < 34:
            raise MtprotoError(ErrorCode.INVALID_SECRET, "ee/FakeTLS secret 长度不足 34")
        domain = ""
        if len(lower) > 34:
            try:
                domain = bytes.fromhex(lower[34:]).decode("ascii", "ignore").rstrip("\x00")
            except ValueError:
                domain = ""
        return ParsedSecret(
            bytes.fromhex(lower[2:34]),
            "ee",
            lower,
            domain or FAKETLS_DEFAULT_DOMAIN,
        )

    if lower.startswith("dd"):
        if len(lower) < 34:
            raise MtprotoError(ErrorCode.INVALID_SECRET, "dd secret 长度不足 34")
        return ParsedSecret(bytes.fromhex(lower[2:34]), "dd", lower)

    if len(raw_hex) > 16:
        return ParsedSecret(raw_hex[:16], "raw", raw_hex[:16].hex())
    if len(raw_hex) != 16:
        raise MtprotoError(
            ErrorCode.INVALID_SECRET, f"secret 解码为 {len(raw_hex)} 字节，期望 16"
        )
    return ParsedSecret(raw_hex, "raw", lower)


# ---------------------------------------------------------------------------
# obfuscated2 握手

_FORBIDDEN_FIRST4 = (
    b"GET ",
    b"POST",
    b"HEAD",
    b"OPTI",
    b"\x00\x00\x00\x00",
    PROTO_ABRIDGED,
    PROTO_INTERMEDIATE,
    PROTO_SECURE,
)


def derive_keys(init: bytes, secret: bytes) -> tuple[bytes, bytes, bytes, bytes]:
    """从 64 字节 init 派生 (enc_key, enc_iv, dec_key, dec_iv)。

    解密侧刻意用反向切片：init[55:23:-1] / init[23:7:-1]。
    这是 obfuscated2 的对称约定，不是笔误。
    """
    if len(init) != 64:
        raise MtprotoError(ErrorCode.INTERNAL_ERROR, f"init 必须 64 字节，得到 {len(init)}")
    if len(secret) != 16:
        raise MtprotoError(ErrorCode.INVALID_SECRET, f"secret 必须 16 字节，得到 {len(secret)}")

    enc_key = hashlib.sha256(init[8:40] + secret).digest()
    enc_iv = init[40:56]
    dec_key = hashlib.sha256(init[55:23:-1] + secret).digest()
    dec_iv = init[23:7:-1]
    return enc_key, enc_iv, dec_key, dec_iv


def make_obfuscated2_handshake(
    secret: bytes,
    dc_id: int = DEFAULT_DC,
    rng: Callable[[int], bytes] = os.urandom,
) -> tuple[bytes, AesCtrStream, AesCtrStream]:
    """构造 64 字节 obfuscated2 头，返回 (待发送包, enc, dec)。

    注意：只有 init[56:64] 被加密后发出，前 56 字节保持明文；
    但 cipher 状态按完整 64 字节推进 —— 后续帧必须接着这个状态加密。
    """
    if len(secret) != 16:
        raise MtprotoError(ErrorCode.INVALID_SECRET, f"secret 必须 16 字节，得到 {len(secret)}")

    init = bytearray()
    for _ in range(64):
        candidate = bytearray(rng(64))
        if len(candidate) != 64:
            raise MtprotoError(ErrorCode.INTERNAL_ERROR, "rng 未返回 64 字节")
        if candidate[0] == 0xEF:
            continue
        if bytes(candidate[:4]) in _FORBIDDEN_FIRST4:
            continue
        if bytes(candidate[4:8]) == b"\x00\x00\x00\x00":
            continue
        init = candidate
        break
    else:
        raise MtprotoError(ErrorCode.INTERNAL_ERROR, "连续 64 次都未生成合法 obfuscated2 头")

    init[56:60] = PROTO_SECURE
    init[60] = dc_id & 0xFF
    init[61:64] = b"\x00\x00\x00"

    enc_key, enc_iv, dec_key, dec_iv = derive_keys(bytes(init), secret)
    enc = aes_ctr(enc_key, enc_iv)
    dec = aes_ctr(dec_key, dec_iv)

    encrypted_init = enc.update(bytes(init))
    init[56:64] = encrypted_init[56:64]
    return bytes(init), enc, dec


# ---------------------------------------------------------------------------
# req_pq_multi / 帧 / resPQ


def make_unencrypted_req_pq_multi(
    rng: Callable[[int], bytes] = os.urandom,
    clock: Callable[[], float] = time.time,
) -> tuple[bytes, bytes]:
    """未鉴权 req_pq_multi：auth_key_id=0 + msg_id + msg_len + constructor + nonce。"""
    nonce = bytes(rng(16))
    if len(nonce) != 16:
        raise MtprotoError(ErrorCode.INTERNAL_ERROR, "rng 未返回 16 字节 nonce")

    payload = struct.pack("<I", REQ_PQ_MULTI) + nonce
    msg_id = int(clock() * (2 ** 32)) & ~3
    packet = (
        b"\x00" * 8
        + struct.pack("<Q", msg_id)
        + struct.pack("<I", len(payload))
        + payload
    )
    return nonce, packet


def frame_message(
    data: bytes,
    mode: str = INNER_MODE,
    rng: Callable[[int], bytes] = os.urandom,
) -> bytes:
    if mode == "secure":
        padding_len = rng(1)[0] % 4
        padding = bytes(rng(padding_len)) if padding_len else b""
        return struct.pack("<I", len(data) + padding_len) + data + padding

    if mode == "intermediate":
        return struct.pack("<I", len(data)) + data

    if mode == "abridged":
        if len(data) % 4:
            raise MtprotoError(ErrorCode.PROTOCOL_ERROR, "abridged 载荷长度必须是 4 的倍数")
        words = len(data) // 4
        header = struct.pack("<B", words) if words < 127 else b"\x7f" + struct.pack("<I", words)[:3]
        return header + data

    raise MtprotoError(ErrorCode.UNSUPPORTED_MODE, f"不支持的模式 {mode}")


class ReadableTransport:
    """只要求 read_exact(n)，便于用 BytesTransport 做纯内存测试。"""

    def read_exact(self, n: int) -> bytes:  # pragma: no cover - 接口声明
        raise NotImplementedError


def read_frame(
    transport: ReadableTransport,
    dec: AesCtrStream,
    mode: str = INNER_MODE,
) -> bytes:
    if mode in ("secure", "intermediate"):
        length_bytes = dec.update(transport.read_exact(4))
        frame_len = struct.unpack("<I", length_bytes)[0]
        if frame_len > 0x80000000:
            frame_len -= 0x80000000
        if frame_len <= 0 or frame_len > MAX_FRAME:
            raise MtprotoError(
                ErrorCode.PROTOCOL_ERROR,
                f"帧长度非法: {frame_len}（解密后长度字节 {length_bytes.hex()}）",
            )
        return dec.update(transport.read_exact(frame_len))

    if mode == "abridged":
        first = dec.update(transport.read_exact(1))[0]
        if first < 127:
            frame_len = first * 4
        else:
            rest = dec.update(transport.read_exact(3))
            frame_len = struct.unpack("<I", rest + b"\x00")[0] * 4
        if frame_len <= 0 or frame_len > MAX_FRAME:
            raise MtprotoError(ErrorCode.PROTOCOL_ERROR, f"abridged 帧长度非法: {frame_len}")
        return dec.update(transport.read_exact(frame_len))

    raise MtprotoError(ErrorCode.UNSUPPORTED_MODE, f"不支持的模式 {mode}")


def parse_res_pq(frame: bytes, expected_nonce: bytes) -> str:
    """校验并解析 resPQ，返回 server_nonce（hex）。

    只有 resPQ 真的回显了我们发出的 nonce，才说明对端是活的 Telegram 出口，
    而不是一个「端口开着但什么都不做」的假节点。
    """
    if len(frame) < 40:
        raise MtprotoError(ErrorCode.PROTOCOL_ERROR, f"resPQ 过短: {len(frame)} 字节")

    if frame[:8] != b"\x00" * 8:
        raise MtprotoError(
            ErrorCode.PROTOCOL_ERROR,
            "回包不是未加密 MTProto 包（auth_key_id 非 0）",
        )

    msg_len = struct.unpack("<I", frame[16:20])[0]
    if msg_len <= 0 or msg_len > len(frame) - 20:
        raise MtprotoError(
            ErrorCode.PROTOCOL_ERROR,
            f"MTProto 消息长度非法: {msg_len}（帧长 {len(frame)}）",
        )

    body = frame[20 : 20 + msg_len]
    if len(body) < 36:
        raise MtprotoError(ErrorCode.PROTOCOL_ERROR, f"MTProto body 过短: {len(body)} 字节")

    constructor = struct.unpack("<I", body[:4])[0]
    if constructor != RES_PQ:
        raise MtprotoError(
            ErrorCode.PROTOCOL_ERROR,
            f"constructor 不是 resPQ: 0x{constructor:08x}",
        )

    if body[4:20] != expected_nonce:
        raise MtprotoError(
            ErrorCode.PROTOCOL_ERROR,
            "resPQ 未回显 nonce（疑似假节点或 secret 不匹配）",
        )

    return body[20:36].hex()


# ---------------------------------------------------------------------------
# 单节点校验入口


class SocketTransport(ReadableTransport):
    def __init__(self, sock: socket.socket, deadline: float) -> None:
        self._sock = sock
        self._deadline = deadline

    def write(self, data: bytes) -> None:
        self._sock.sendall(data)

    def read_exact(self, n: int) -> bytes:
        out = bytearray()
        while len(out) < n:
            remaining = self._deadline - time.monotonic()
            if remaining <= 0:
                raise MtprotoError(ErrorCode.RESPONSE_TIMEOUT, "读取响应超时")
            self._sock.settimeout(remaining)
            chunk = self._sock.recv(n - len(out))
            if not chunk:
                raise MtprotoError(ErrorCode.PROTOCOL_ERROR, "连接被对端关闭")
            out += chunk
        return bytes(out)


@dataclass(frozen=True)
class CheckResult:
    ok: bool
    host: str
    port: int
    secret_kind: str
    dc_id: int = DEFAULT_DC
    mode: str = INNER_MODE
    rtt_ms: Optional[float] = None
    server_nonce: Optional[str] = None
    error_code: Optional[str] = None
    error_message: Optional[str] = None


def check_mtproto_proxy(
    host: str,
    port: int,
    secret: str,
    *,
    dc_id: int = DEFAULT_DC,
    connect_timeout: float = 2.0,
    response_timeout: float = 2.0,
    rng: Callable[[int], bytes] = os.urandom,
    clock: Callable[[], float] = time.time,
) -> CheckResult:
    """单节点 MTProto 真校验。永不抛异常，失败统一转成 CheckResult。"""
    kind = "unknown"
    try:
        parsed = parse_secret(secret)
        kind = parsed.kind
    except MtprotoError as exc:
        return CheckResult(
            False, str(host), 0, kind, dc_id,
            error_code=exc.code, error_message=exc.message,
        )

    if not host:
        return CheckResult(
            False, str(host), 0, kind, dc_id,
            error_code=ErrorCode.INVALID_INPUT, error_message="host 为空",
        )

    try:
        port_int = int(port)
    except (TypeError, ValueError):
        return CheckResult(
            False, str(host), 0, kind, dc_id,
            error_code=ErrorCode.INVALID_INPUT, error_message=f"端口非法: {port!r}",
        )

    if not 1 <= port_int <= 65535:
        return CheckResult(
            False, str(host), port_int, kind, dc_id,
            error_code=ErrorCode.INVALID_INPUT, error_message=f"端口越界: {port_int}",
        )

    if parsed.kind == "ee":
        # FakeTLS 未实现：不能拿那 16 字节当 dd 去硬连 —— 对端必然断开，
        # 把「我们用错算法」记成「节点失败」。返回无法判定，让上游保持未验证。
        # 放在入参校验之后：host/port 非法时应先报 INVALID_INPUT，而不是被 ee 抢答。
        return CheckResult(
            False, str(host), 0, kind, dc_id,
            error_code=ErrorCode.FAKETLS_UNSUPPORTED,
            error_message="ee/FakeTLS 未实现，无法判定（不计失败）",
        )

    started = time.monotonic()
    sock = None
    try:
        sock = socket.create_connection((host, port_int), timeout=connect_timeout)
    except TimeoutError:
        return CheckResult(
            False, str(host), port_int, kind, dc_id,
            error_code=ErrorCode.CONNECT_TIMEOUT,
            error_message=f"连接超时（>{connect_timeout}s）",
        )
    except OSError as exc:
        return CheckResult(
            False, str(host), port_int, kind, dc_id,
            error_code=ErrorCode.NETWORK_ERROR, error_message=str(exc),
        )

    try:
        deadline = time.monotonic() + response_timeout
        transport = SocketTransport(sock, deadline)

        init_packet, enc, dec = make_obfuscated2_handshake(parsed.raw, dc_id, rng=rng)
        transport.write(init_packet)

        nonce, request = make_unencrypted_req_pq_multi(rng=rng, clock=clock)
        transport.write(enc.update(frame_message(request, INNER_MODE, rng=rng)))

        frame = read_frame(transport, dec, INNER_MODE)
        server_nonce = parse_res_pq(frame, nonce)

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
        return CheckResult(
            False, str(host), port_int, kind, dc_id,
            error_code=ErrorCode.RESPONSE_TIMEOUT,
            error_message=f"响应超时（>{response_timeout}s）",
        )
    except MtprotoError as exc:
        return CheckResult(
            False, str(host), port_int, kind, dc_id,
            error_code=exc.code, error_message=exc.message,
        )
    except OSError as exc:
        return CheckResult(
            False, str(host), port_int, kind, dc_id,
            error_code=ErrorCode.NETWORK_ERROR, error_message=str(exc),
        )
    except Exception as exc:  # 兜底：绝不让单节点异常打断整轮
        return CheckResult(
            False, str(host), port_int, kind, dc_id,
            error_code=ErrorCode.INTERNAL_ERROR,
            error_message=f"{type(exc).__name__}: {exc}",
        )
    finally:
        if sock is not None:
            try:
                sock.close()
            except OSError:
                pass


__all__ = [
    "AesCtrStream",
    "CheckResult",
    "ErrorCode",
    "INNER_MODE",
    "MtprotoError",
    "ParsedSecret",
    "REQ_PQ_MULTI",
    "RES_PQ",
    "aes_backend_name",
    "aes_ctr",
    "check_mtproto_proxy",
    "derive_keys",
    "frame_message",
    "make_obfuscated2_handshake",
    "make_unencrypted_req_pq_multi",
    "parse_res_pq",
    "parse_secret",
    "read_frame",
]
