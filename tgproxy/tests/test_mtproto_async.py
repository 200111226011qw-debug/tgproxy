# -*- coding: utf-8 -*-
"""Phase 2：异步引擎的等价性、批量编排、限流、取消、保序。

测试哲学：先证明「异步与同步对同一场景给出同一个结论」，再谈性能。
否则跑得再快，只要错误码漂了，桌面版的统计口径就废了。

所有用例都是同步函数（内部 asyncio.run），这样 pytest 与 `python 本文件`
两种跑法行为一致，也不依赖 pytest-asyncio 的 strict/auto 模式。
"""

import asyncio
import os
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
_ROOT = os.path.dirname(_HERE)
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

import mtproto_async as ma  # noqa: E402
import mtproto_handshake as mh  # noqa: E402
from mock_mtproxy import MockMtproxyServer  # noqa: E402
from mock_mtproxy_async import AsyncMockFarm, AsyncMockMtproxyServer  # noqa: E402

SECRET_HEX = "4fc30d1222972a04067e805a5134cb57"
SECRET = bytes.fromhex(SECRET_HEX)
SERVER_NONCE = b"S" * 16


# ---------------------------------------------------------------------------
# 单节点


def test_async_single_node_success():
    async def body():
        async with AsyncMockMtproxyServer(SECRET, server_nonce=SERVER_NONCE) as srv:
            return await ma.check_mtproto_proxy_async(
                "127.0.0.1", srv.port, SECRET_HEX
            )

    result = asyncio.run(body())
    assert result.ok, result
    assert result.rtt_ms is not None and result.rtt_ms >= 0
    assert result.server_nonce == SERVER_NONCE.hex()
    assert result.secret_kind == "raw"


def test_async_coalesce_false_also_works():
    """分两次 write 的路径也要通（合并写只是优化，不是前提）。"""

    async def body():
        async with AsyncMockMtproxyServer(SECRET) as srv:
            return await ma.check_mtproto_proxy_async(
                "127.0.0.1", srv.port, SECRET_HEX, coalesce=False
            )

    assert asyncio.run(body()).ok


def test_corrupt_nonce_matches_sync_error_code():
    with MockMtproxyServer(SECRET, corrupt_nonce=True) as sync_srv:
        sync_result = mh.check_mtproto_proxy(
            "127.0.0.1", sync_srv.port, SECRET_HEX, response_timeout=1.0
        )

    async def body():
        async with AsyncMockMtproxyServer(SECRET, corrupt_nonce=True) as srv:
            return await ma.check_mtproto_proxy_async(
                "127.0.0.1", srv.port, SECRET_HEX, total_budget=1.0
            )

    async_result = asyncio.run(body())

    assert sync_result.ok is False and async_result.ok is False
    assert sync_result.error_code == async_result.error_code == mh.ErrorCode.PROTOCOL_ERROR


def test_silent_server_matches_sync_as_response_timeout():
    with MockMtproxyServer(SECRET, silent=True, hold_s=1.5) as sync_srv:
        sync_result = mh.check_mtproto_proxy(
            "127.0.0.1", sync_srv.port, SECRET_HEX, response_timeout=0.3
        )

    async def body():
        async with AsyncMockMtproxyServer(SECRET, silent=True, hold_s=1.5) as srv:
            return await ma.check_mtproto_proxy_async(
                "127.0.0.1", srv.port, SECRET_HEX, total_budget=0.3
            )

    async_result = asyncio.run(body())

    assert sync_result.error_code == async_result.error_code == mh.ErrorCode.RESPONSE_TIMEOUT


def test_wrong_secret_fails_on_both_engines():
    with MockMtproxyServer(SECRET, wrong_secret=True) as sync_srv:
        sync_result = mh.check_mtproto_proxy(
            "127.0.0.1", sync_srv.port, SECRET_HEX, response_timeout=1.0
        )

    async def body():
        async with AsyncMockMtproxyServer(SECRET, wrong_secret=True) as srv:
            return await ma.check_mtproto_proxy_async(
                "127.0.0.1", srv.port, SECRET_HEX, total_budget=1.0
            )

    async_result = asyncio.run(body())
    allowed = {
        mh.ErrorCode.PROTOCOL_ERROR,
        mh.ErrorCode.NETWORK_ERROR,
        mh.ErrorCode.RESPONSE_TIMEOUT,
    }
    assert sync_result.ok is False and async_result.ok is False
    assert sync_result.error_code in allowed
    assert async_result.error_code in allowed


def test_invalid_input_matches_sync():
    sync_secret = mh.check_mtproto_proxy("127.0.0.1", 443, "zz")
    async_secret = asyncio.run(ma.check_mtproto_proxy_async("127.0.0.1", 443, "zz"))
    assert (sync_secret.error_code, async_secret.error_code) == (
        mh.ErrorCode.INVALID_SECRET,
        mh.ErrorCode.INVALID_SECRET,
    )

    sync_port = mh.check_mtproto_proxy("127.0.0.1", 0, SECRET_HEX)
    async_port = asyncio.run(ma.check_mtproto_proxy_async("127.0.0.1", 0, SECRET_HEX))
    assert (sync_port.error_code, async_port.error_code) == (
        mh.ErrorCode.INVALID_INPUT,
        mh.ErrorCode.INVALID_INPUT,
    )


def test_refused_connection_is_network_error_family():
    """端口关闭时两个引擎都应给出网络类错误（具体码取决于平台时序）。"""
    allowed = {mh.ErrorCode.NETWORK_ERROR, mh.ErrorCode.CONNECT_TIMEOUT}
    sync_result = mh.check_mtproto_proxy("127.0.0.1", 1, SECRET_HEX, connect_timeout=0.5)
    async_result = asyncio.run(
        ma.check_mtproto_proxy_async("127.0.0.1", 1, SECRET_HEX, total_budget=0.5)
    )
    assert sync_result.error_code in allowed
    assert async_result.error_code in allowed


# ---------------------------------------------------------------------------
# 批量编排


def test_check_many_sync_wrapper_uses_thread_mock():
    """同步壳子必须能在已有线程模型里直接用（这里配线程版 mock，避免跨 loop）。"""
    with MockMtproxyServer(SECRET) as srv:
        nodes = [ma.ProxyNode("127.0.0.1", srv.port, SECRET_HEX) for _ in range(3)]
        results = ma.check_many_sync(nodes, concurrency=2)

    assert len(results) == 3
    assert all(r.ok for r in results), [r.error_code for r in results]


def test_peak_concurrency_is_capped_by_semaphore():
    async def body():
        async with AsyncMockMtproxyServer(SECRET, delay_ms=20) as srv:
            nodes = [
                ma.ProxyNode("127.0.0.1", srv.port, SECRET_HEX) for _ in range(60)
            ]
            results = await ma.check_many(nodes, concurrency=10, total_budget=3.0)
            return results, srv.peak_active

    results, peak = asyncio.run(body())

    assert all(r.ok for r in results), [r.error_code for r in results if not r.ok][:5]
    assert peak <= 10, f"峰值并发 {peak} 超过信号量上限 10"
    assert peak >= 5, f"峰值并发只有 {peak}，看起来并没有真的并发"


def test_results_preserve_input_order():
    """故意让输入越靠前的节点越慢，完成顺序与输入顺序相反。"""

    async def body():
        servers = []
        try:
            for i in range(12):
                server = AsyncMockMtproxyServer(SECRET, delay_ms=(11 - i) * 10)
                await server.start()
                servers.append(server)
            nodes = [
                ma.ProxyNode("127.0.0.1", server.port, SECRET_HEX)
                for server in servers
            ]
            results = await ma.check_many(nodes, concurrency=12, total_budget=3.0)
            return results, [server.port for server in servers]
        finally:
            for server in servers:
                await server.stop()

    results, ports = asyncio.run(body())

    assert [r.port for r in results] == ports
    assert all(r.ok for r in results), [r.error_code for r in results if not r.ok]


def test_invalid_secret_does_not_consume_semaphore_slot():
    """非法 secret 应在拿信号量之前就被拒，不建连接、不占槽位。"""

    async def body():
        async with AsyncMockMtproxyServer(SECRET) as srv:
            nodes = [ma.ProxyNode("127.0.0.1", srv.port, "zz") for _ in range(20)]
            results = await ma.check_many(nodes, concurrency=1, total_budget=1.0)
            return results, srv.peak_active

    results, peak = asyncio.run(body())

    assert all(r.error_code == mh.ErrorCode.INVALID_SECRET for r in results)
    assert peak == 0, f"非法 secret 不该建立连接，服务端却看到 {peak} 个并发"


def test_cancellation_propagates():
    async def body():
        async with AsyncMockMtproxyServer(SECRET, silent=True, hold_s=5.0) as srv:
            nodes = [
                ma.ProxyNode("127.0.0.1", srv.port, SECRET_HEX) for _ in range(20)
            ]
            task = asyncio.create_task(
                ma.check_many(nodes, concurrency=5, total_budget=5.0)
            )
            await asyncio.sleep(0.1)
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                return True
            return False

    assert asyncio.run(body()) is True


def test_on_result_is_called_once_per_node():
    seen = []

    async def body():
        async with AsyncMockMtproxyServer(SECRET) as srv:
            nodes = [
                ma.ProxyNode("127.0.0.1", srv.port, SECRET_HEX) for _ in range(15)
            ]
            await ma.check_many(
                nodes,
                concurrency=5,
                total_budget=2.0,
                on_result=lambda index, result: seen.append(index),
            )

    asyncio.run(body())
    assert sorted(seen) == list(range(15))


def test_on_result_exception_does_not_break_the_run():
    async def body():
        async with AsyncMockMtproxyServer(SECRET) as srv:
            nodes = [
                ma.ProxyNode("127.0.0.1", srv.port, SECRET_HEX) for _ in range(6)
            ]

            def boom(index, result):
                raise RuntimeError("callback bug")

            results = await ma.check_many(
                nodes, concurrency=3, total_budget=2.0, on_result=boom
            )
            return results

    results = asyncio.run(body())
    assert len(results) == 6
    assert all(r.ok for r in results)


# ---------------------------------------------------------------------------
# 100 节点（用户验收口径）


def test_hundred_nodes_all_ok():
    async def body():
        farm = await AsyncMockFarm(100, SECRET, base_port=20000).start()
        try:
            nodes = [
                ma.ProxyNode("127.0.0.1", port, SECRET_HEX) for port in farm.ports
            ]
            results = await ma.check_many(nodes, concurrency=200, total_budget=3.0)
            return results, farm.errors, len(farm.ports)
        finally:
            await farm.stop()

    results, errors, count = asyncio.run(body())

    assert count == 100
    assert len(results) == 100
    failed = [r for r in results if not r.ok]
    assert not failed, f"{len(failed)} 个失败，示例: {failed[:3]}"
    assert errors == [], errors[:3]


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
