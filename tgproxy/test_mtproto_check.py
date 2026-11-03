# -*- coding: utf-8 -*-
"""单节点 MTProto 真校验 CLI（Phase 1）。

用法：
    python test_mtproto_check.py "https://t.me/proxy?server=1.2.3.4&port=443&secret=..."
    python test_mtproto_check.py 1.2.3.4 443 4fc30d1222972a04067e805a5134cb57
    python test_mtproto_check.py --mock            # 离线自检：本地起 mock 服务端跑端到端

设计约束：
- 失败只打印错误码与一句人话，不打印异常栈（死节点是常态，不是事故）。
- 退出码：0 通过，2 失败，3 参数错误。便于脚本/CI 直接判断。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.parse

_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)
_TESTS = os.path.join(_HERE, "tests")
if _TESTS not in sys.path:
    sys.path.insert(0, _TESTS)

import mtproto_handshake as mh  # noqa: E402

EXIT_OK = 0
EXIT_FAIL = 2
EXIT_USAGE = 3


def parse_proxy_url(url: str) -> tuple[str, int, str]:
    parsed = urllib.parse.urlparse(url)
    query = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)

    def first(key: str) -> str:
        values = query.get(key)
        return values[0] if values else ""

    host = first("server")
    port_text = first("port")
    secret = first("secret")

    if not host or not port_text or not secret:
        raise ValueError("URL 缺少 server / port / secret 参数")
    try:
        port = int(port_text)
    except ValueError as exc:
        raise ValueError(f"URL 里的 port 不是整数: {port_text}") from exc
    return host, port, secret


def render(result: mh.CheckResult) -> str:
    where = f"{result.host}:{result.port}"
    if result.ok:
        return (
            f"[OK]   {where}  kind={result.secret_kind}  DC{result.dc_id}  "
            f"RTT {result.rtt_ms}ms  server_nonce={result.server_nonce}"
        )
    return (
        f"[FAIL] {where}  kind={result.secret_kind}  "
        f"code={result.error_code}  {result.error_message}"
    )


def run_mock(dc_id: int, timeout: float) -> int:
    """离线自检：本地 mock 服务端 + 真实客户端实现，跑完整握手。"""
    from mock_mtproxy import MockMtproxyServer

    secret = bytes.fromhex("4fc30d1222972a04067e805a5134cb57")
    with MockMtproxyServer(secret, dc_id=dc_id) as srv:
        result = mh.check_mtproto_proxy(
            "127.0.0.1",
            srv.port,
            secret.hex(),
            dc_id=dc_id,
            connect_timeout=timeout,
            response_timeout=timeout,
        )
        print(f"[mock] 本地 MTProxy 服务端 127.0.0.1:{srv.port}")
        print(f"[mock] AES 后端: {mh.aes_backend_name() or '(none)'}")
        print(render(result))
        if srv.errors:
            print(f"[mock] 服务端记录到 {len(srv.errors)} 个异常:")
            for exc in srv.errors:
                print(f"       - {type(exc).__name__}: {exc}")
            return EXIT_FAIL
    return EXIT_OK if result.ok else EXIT_FAIL


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="单节点 MTProto(MTProxy) 真校验：obfuscated2 握手 + req_pq_multi + resPQ",
        add_help=True,
    )
    parser.add_argument("target", nargs="*", help="proxy URL，或 host port secret 三个参数")
    parser.add_argument("--mock", action="store_true", help="离线自检：本地 mock 服务端")
    parser.add_argument("--dc", type=int, default=mh.DEFAULT_DC, help="Telegram DC 编号（默认 2）")
    parser.add_argument("--timeout", type=float, default=2.0, help="连接/响应超时秒数（默认 2.0）")
    parser.add_argument("--json", action="store_true", help="以 JSON 输出结果")
    args = parser.parse_args(argv)

    if args.mock:
        code = run_mock(args.dc, args.timeout)
        return code

    if not args.target:
        parser.print_help()
        return EXIT_USAGE

    try:
        if len(args.target) == 1:
            host, port, secret = parse_proxy_url(args.target[0])
        elif len(args.target) == 3:
            host, port, secret = args.target[0], int(args.target[1]), args.target[2]
        else:
            raise ValueError("参数要么是 1 个 proxy URL，要么是 host port secret 三个")
    except ValueError as exc:
        print(f"[USAGE] {exc}", file=sys.stderr)
        return EXIT_USAGE

    result = mh.check_mtproto_proxy(
        host,
        port,
        secret,
        dc_id=args.dc,
        connect_timeout=args.timeout,
        response_timeout=args.timeout,
    )

    if args.json:
        print(json.dumps(result.__dict__, ensure_ascii=False))
    else:
        print(render(result))
        print(f"[info] AES 后端: {mh.aes_backend_name() or '(none)'}")

    return EXIT_OK if result.ok else EXIT_FAIL


if __name__ == "__main__":
    raise SystemExit(main())
