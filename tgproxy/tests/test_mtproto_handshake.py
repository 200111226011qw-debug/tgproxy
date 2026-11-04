# -*- coding: utf-8 -*-
"""Phase 1：MTProto obfuscated2 真校验的离线验证。

三层验证，缺一不可：
1. **密码学原语**：AES-256-CTR 对齐 NIST SP 800-38A F.5.5 向量 —— 证明后端库用对了；
2. **协议拼装**：头布局、反向切片密钥派生、帧格式、resPQ 解析 —— 纯函数可逐字节断言；
3. **端到端闭环**：本地 mock MTProxy 服务端跑完整握手 —— 证明「客户端实现 + 服务端约定」一致。

没有第 3 层，前两层都可能在「各自正确但约定不同」的地方翻车。
"""

import hashlib
import os
import struct
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
_ROOT = os.path.dirname(_HERE)
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

import mtproto_handshake as mh  # noqa: E402
from mock_mtproxy import MockMtproxyServer  # noqa: E402

SECRET_HEX = "4fc30d1222972a04067e805a5134cb57"
SECRET = bytes.fromhex(SECRET_HEX)
SERVER_NONCE = b"S" * 16


# ---------------------------------------------------------------------------
# 1. 密码学原语


def test_aes256_ctr_matches_nist_sp800_38a_f5_5():
    key = bytes.fromhex("603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4")
    iv = bytes.fromhex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff")
    plain = bytes.fromhex(
        "6bc1bee22e409f96e93d7e117393172a"
        "ae2d8a571e03ac9c9eb76fac45af8e51"
        "30c81c46a35ce411e5fbc1191a0a52ef"
        "f69f2445df4f9b17ad2b417be66c3710"
    )
    expected = (
        "601ec313775789a5b7a7f504bbf3d228"
        "f443e3ca4d62b59aca84e990cacaf5c5"
        "2b0930daa23de94ce87017ba2d84988d"
        "dfc9c58db67aada613c2dd08457941a6"
    )
    assert mh.aes_ctr(key, iv).update(plain).hex() == expected


def test_aes_backend_is_available():
    assert mh.aes_backend_name() in ("cryptography", "pycryptodome")


def test_aes_ctr_is_streaming_across_updates():
    key, iv = b"k" * 32, b"i" * 16
    whole = mh.aes_ctr(key, iv).update(b"0123456789abcdef")
    split = mh.aes_ctr(key, iv)
    assert split.update(b"01234567") + split.update(b"89abcdef") == whole


def test_crypto_unavailable_is_reported_as_error_code():
    old = (mh._AES_RESOLVED, mh._AES_BACKEND)
    mh._AES_RESOLVED, mh._AES_BACKEND = True, None
    try:
        try:
            mh.aes_ctr(b"\x00" * 32, b"\x00" * 16)
        except mh.MtprotoError as exc:
            assert exc.code == mh.ErrorCode.CRYPTO_UNAVAILABLE
        else:
            raise AssertionError("缺 AES 后端时必须抛 CRYPTO_UNAVAILABLE")
    finally:
        mh._AES_RESOLVED, mh._AES_BACKEND = old


# ---------------------------------------------------------------------------
# 2. secret 解析


def test_parse_secret_raw_hex():
    parsed = mh.parse_secret(SECRET_HEX)
    assert parsed.kind == "raw"
    assert parsed.raw == SECRET


def test_parse_secret_uppercase_is_normalized():
    assert mh.parse_secret(SECRET_HEX.upper()).raw == SECRET


def test_parse_secret_dd_prefix():
    parsed = mh.parse_secret("dd" + SECRET_HEX)
    assert parsed.kind == "dd"
    assert parsed.raw == SECRET


def test_parse_secret_ee_prefix_and_domain():
    domain_hex = "www.example.com".encode().hex()
    parsed = mh.parse_secret("ee" + SECRET_HEX + domain_hex)
    assert parsed.kind == "ee"
    assert parsed.raw == SECRET
    assert parsed.faketls_domain == "www.example.com"


def test_parse_secret_ee_without_domain_uses_default():
    parsed = mh.parse_secret("ee" + SECRET_HEX)
    assert parsed.faketls_domain == mh.FAKETLS_DEFAULT_DOMAIN


def test_parse_secret_base64():
    import base64

    encoded = base64.b64encode(SECRET).decode()
    assert mh.parse_secret(encoded).raw == SECRET


def test_parse_secret_rejects_empty_and_too_short():
    for bad in ("", "   ", "abc", "dd12"):
        try:
            mh.parse_secret(bad)
        except mh.MtprotoError as exc:
            assert exc.code == mh.ErrorCode.INVALID_SECRET, bad
        else:
            raise AssertionError("应当拒绝非法 secret: %r" % bad)


# ---------------------------------------------------------------------------
# 3. 头布局与密钥派生


def test_derive_keys_uses_reversed_slices():
    init = bytes(range(64))
    enc_key, enc_iv, dec_key, dec_iv = mh.derive_keys(init, SECRET)

    assert enc_key == hashlib.sha256(init[8:40] + SECRET).digest()
    assert enc_iv == init[40:56]
    assert dec_key == hashlib.sha256(init[55:23:-1] + SECRET).digest()
    assert dec_iv == init[23:7:-1]

    assert len(dec_key) == 32 and len(dec_iv) == 16
    # 反向切片必须真的反向，不能等同于正向材料
    assert init[55:23:-1] != init[8:40]


def test_handshake_sets_proto_tag_and_dc_in_last_8_bytes():
    raw = bytes([0x11] * 64)
    packet, _enc, _dec = mh.make_obfuscated2_handshake(
        SECRET, dc_id=4, rng=lambda n: raw[:n]
    )
    assert len(packet) == 64
    # 前 56 字节明文不动
    assert packet[:56] == raw[:56]
    # 后 8 字节必须是密文，且不等于原文
    assert packet[56:64] != raw[56:64]

    enc_key, enc_iv, _, _ = mh.derive_keys(raw, SECRET)
    # 期望密文要用「改写 proto_tag / dc 之后」的明文算，
    # 而不是原始 raw —— 56:64 的明文变了，密文自然变。
    expected_plain = raw[:56] + mh.PROTO_SECURE + bytes([4]) + b"\x00" * 3
    expected = mh.aes_ctr(enc_key, enc_iv).update(expected_plain)[56:64]
    assert packet[56:64] == expected


def test_handshake_advances_cipher_by_full_64_bytes():
    """只有 8 字节发出，但 cipher 必须走满 64 字节 —— 差这一步后续帧全错。"""
    raw = bytes([0x11] * 64)
    _packet, enc, _dec = mh.make_obfuscated2_handshake(
        SECRET, dc_id=2, rng=lambda n: raw[:n]
    )
    enc_key, enc_iv, _, _ = mh.derive_keys(raw, SECRET)
    reference = mh.aes_ctr(enc_key, enc_iv)
    reference.update(raw)
    assert enc.update(b"\xaa") == reference.update(b"\xaa")


def test_handshake_retries_forbidden_prefixes():
    sequence = iter(
        [
            b"\xef" + b"a" * 63,                    # 首字节 0xEF
            b"GET " + b"a" * 60,                    # HTTP 方法
            b"abcd" + b"\x00" * 4 + b"a" * 56,      # [4:8] 全零
            b"\xdd" * 4 + b"a" * 60,                # proto tag 本身
            b"\x11" * 64,                           # 合法
        ]
    )
    packet, _, _ = mh.make_obfuscated2_handshake(
        SECRET, dc_id=2, rng=lambda n: next(sequence)[:n]
    )
    assert len(packet) == 64


def test_handshake_rejects_bad_secret_length():
    try:
        mh.make_obfuscated2_handshake(b"short", dc_id=2)
    except mh.MtprotoError as exc:
        assert exc.code == mh.ErrorCode.INVALID_SECRET
    else:
        raise AssertionError("secret 非 16 字节时必须拒绝")


# ---------------------------------------------------------------------------
# 4. req_pq_multi / 帧 / resPQ


def test_req_pq_multi_layout():
    nonce, packet = mh.make_unencrypted_req_pq_multi(
        rng=lambda n: b"n" * n, clock=lambda: 10.0
    )
    assert len(packet) == 40
    assert packet[:8] == b"\x00" * 8
    assert struct.unpack("<Q", packet[8:16])[0] == int(10.0 * (2 ** 32)) & ~3
    assert struct.unpack("<I", packet[16:20])[0] == 20
    assert struct.unpack("<I", packet[20:24])[0] == mh.REQ_PQ_MULTI
    assert packet[24:40] == nonce == b"n" * 16


def test_frame_message_secure_without_padding():
    assert mh.frame_message(b"abc", "secure", rng=lambda n: b"\x00" * n) == (
        b"\x03\x00\x00\x00abc"
    )


def test_frame_message_secure_with_padding():
    frame = mh.frame_message(b"abc", "secure", rng=lambda n: b"\x02" * n)
    assert struct.unpack("<I", frame[:4])[0] == 5
    assert frame[4:] == b"abc\x02\x02"


def test_frame_message_intermediate_and_abridged():
    assert mh.frame_message(b"abc", "intermediate") == b"\x03\x00\x00\x00abc"
    assert mh.frame_message(b"abcd", "abridged") == b"\x01abcd"

    large = b"a" * (127 * 4)
    frame = mh.frame_message(large, "abridged")
    assert frame[:4] == b"\x7f\x7f\x00\x00"
    assert frame[4:] == large


def test_frame_message_rejects_bad_input():
    for data, mode in ((b"abc", "abridged"), (b"abc", "nope")):
        try:
            mh.frame_message(data, mode)
        except mh.MtprotoError as exc:
            assert exc.code in (mh.ErrorCode.PROTOCOL_ERROR, mh.ErrorCode.UNSUPPORTED_MODE)
        else:
            raise AssertionError("应当拒绝: %r/%r" % (data, mode))


class _Identity:
    def update(self, data):
        return data


class _BytesTransport:
    def __init__(self, data):
        self._buf = bytearray(data)

    def read_exact(self, n):
        out = bytes(self._buf[:n])
        del self._buf[:n]
        return out


def test_read_frame_secure_and_high_bit_adjustment():
    assert mh.read_frame(
        _BytesTransport(struct.pack("<I", 4) + b"data"), _Identity(), "secure"
    ) == b"data"
    assert mh.read_frame(
        _BytesTransport(struct.pack("<I", 0x80000004) + b"more"), _Identity(), "intermediate"
    ) == b"more"


def test_read_frame_rejects_bad_lengths():
    for frame_len in (0, mh.MAX_FRAME + 1):
        try:
            mh.read_frame(_BytesTransport(struct.pack("<I", frame_len)), _Identity(), "secure")
        except mh.MtprotoError as exc:
            assert exc.code == mh.ErrorCode.PROTOCOL_ERROR
        else:
            raise AssertionError("长度 %d 必须被拒绝" % frame_len)


def test_parse_res_pq_accepts_valid_frame():
    nonce = b"n" * 16
    body = struct.pack("<I", mh.RES_PQ) + nonce + SERVER_NONCE
    frame = b"\x00" * 8 + b"\x01" * 8 + struct.pack("<I", len(body)) + body
    assert mh.parse_res_pq(frame, nonce) == SERVER_NONCE.hex()


def test_parse_res_pq_rejects_each_malformation():
    nonce = b"n" * 16
    valid_body = struct.pack("<I", mh.RES_PQ) + nonce + SERVER_NONCE
    valid_frame = b"\x00" * 8 + b"\x01" * 8 + struct.pack("<I", len(valid_body)) + valid_body

    cases = {
        "过短": (b"short", nonce),
        "auth_key_id 非零": (b"bad-auth" + b"\x00" * 40, nonce),
        "nonce 不匹配": (valid_frame, b"x" * 16),
    }
    for name, (frame, expect) in cases.items():
        try:
            mh.parse_res_pq(frame, expect)
        except mh.MtprotoError as exc:
            assert exc.code == mh.ErrorCode.PROTOCOL_ERROR, name
        else:
            raise AssertionError("应当拒绝: %s" % name)

    bad_constructor = (
        b"\x00" * 8
        + b"\x01" * 8
        + struct.pack("<I", 36)
        + struct.pack("<I", 0)
        + nonce
        + SERVER_NONCE
    )
    try:
        mh.parse_res_pq(bad_constructor, nonce)
    except mh.MtprotoError as exc:
        assert exc.code == mh.ErrorCode.PROTOCOL_ERROR
    else:
        raise AssertionError("constructor 非 resPQ 时必须拒绝")


# ---------------------------------------------------------------------------
# 5. 端到端（本地 mock 服务端）


def test_end_to_end_handshake_against_local_mock():
    with MockMtproxyServer(SECRET, dc_id=2, server_nonce=SERVER_NONCE) as srv:
        result = mh.check_mtproto_proxy(
            "127.0.0.1",
            srv.port,
            SECRET_HEX,
            dc_id=2,
            connect_timeout=2.0,
            response_timeout=2.0,
        )

    assert result.ok, result
    assert result.rtt_ms is not None and result.rtt_ms >= 0
    assert result.server_nonce == SERVER_NONCE.hex()
    assert result.secret_kind == "raw"
    assert srv.served == 1
    assert srv.errors == [], srv.errors


def test_end_to_end_wrong_secret_is_rejected():
    with MockMtproxyServer(SECRET, wrong_secret=True) as srv:
        result = mh.check_mtproto_proxy(
            "127.0.0.1", srv.port, SECRET_HEX, response_timeout=1.0
        )

    assert not result.ok
    assert result.error_code in (
        mh.ErrorCode.PROTOCOL_ERROR,
        mh.ErrorCode.NETWORK_ERROR,
        mh.ErrorCode.RESPONSE_TIMEOUT,
    ), result


def test_end_to_end_silent_server_times_out():
    with MockMtproxyServer(SECRET, silent=True, hold_s=1.5) as srv:
        result = mh.check_mtproto_proxy(
            "127.0.0.1", srv.port, SECRET_HEX, response_timeout=0.3
        )

    assert not result.ok
    assert result.error_code == mh.ErrorCode.RESPONSE_TIMEOUT, result


def test_end_to_end_corrupt_nonce_is_rejected():
    with MockMtproxyServer(SECRET, corrupt_nonce=True) as srv:
        result = mh.check_mtproto_proxy(
            "127.0.0.1", srv.port, SECRET_HEX, response_timeout=1.0
        )

    assert not result.ok
    assert result.error_code == mh.ErrorCode.PROTOCOL_ERROR, result
    assert "nonce" in (result.error_message or "")


def test_closed_port_is_reported_without_exception():
    result = mh.check_mtproto_proxy("127.0.0.1", 1, SECRET_HEX, connect_timeout=0.5)
    assert not result.ok
    assert result.error_code in (mh.ErrorCode.NETWORK_ERROR, mh.ErrorCode.CONNECT_TIMEOUT)


def test_invalid_port_and_secret_return_error_codes():
    assert mh.check_mtproto_proxy("127.0.0.1", 0, SECRET_HEX).error_code == (
        mh.ErrorCode.INVALID_INPUT
    )
    assert mh.check_mtproto_proxy("127.0.0.1", 443, "zz").error_code == (
        mh.ErrorCode.INVALID_SECRET
    )


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


def test_check_mtproto_proxy_faketls_short_circuits_without_connect(monkeypatch):
    """ee/FakeTLS 未实现：不拿那 16 字节当 dd 硬连，也不该发起任何连接。"""
    def boom(*args, **kwargs):
        raise AssertionError("ee 不应发起连接")

    monkeypatch.setattr(mh.socket, "create_connection", boom)
    result = mh.check_mtproto_proxy("127.0.0.1", 443, "ee" + SECRET_HEX)

    assert result.ok is False
    assert result.error_code == mh.ErrorCode.FAKETLS_UNSUPPORTED
    assert result.secret_kind == "ee"

def test_faketls_invalid_input_still_reports_invalid_input():
    """入参校验优先于 ee 短路：host 为空时应报 INVALID_INPUT，而不是被 ee 抢答。"""
    result = mh.check_mtproto_proxy("", 0, "ee" + SECRET_HEX)
    assert result.error_code == mh.ErrorCode.INVALID_INPUT


