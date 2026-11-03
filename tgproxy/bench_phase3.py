# -*- coding: utf-8 -*-
"""Phase 3 全量 A/B：竞速合入前后 + TG 并发水位。

真实性声明（必须和数字一起看）：
- **fetch**：真实 HTTP 请求，本地「黑洞（accept 后不回）」+「健康镜像」两个 mock。
  直连黑洞等价于 GitHub 被墙；这是真实 socket。
- **TCP 预筛**：**真实 socket**。300 个本地 sink（accept 即 close），3000 个节点轮询。
- **TG 实测**：**合成闸门**。沙箱到 Telegram DC 不通，也无法为 desktop.telegram.org
  签出受信证书，所以把两关换成 `sleep(RTT/2)`。它只测**调度与并发水位**，
  不含 TLS 握手成本、不含丢包重传。
- 因此本基准的「总耗时」是**结构性对比**，不是 WAN 实测。

用法：
    python bench_phase3.py --nodes 3000 --rtt-ms 60
    python bench_phase3.py --nodes 3000 --workers 100,250,300 --json phase3.json
"""

from __future__ import annotations

import argparse
import asyncio
import importlib.machinery
import importlib.util
import json
import os
import statistics
import sys
import time

_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)
_TESTS = os.path.join(_HERE, "tests")
if _TESTS not in sys.path:
    sys.path.insert(0, _TESTS)

import proxy_manager as pm  # noqa: E402
from mock_http_mirror import AsyncHttpMock  # noqa: E402

BASELINE_PATH = os.path.join(_HERE, "proxy_manager.py.orig")


def load_baseline():
    """把改造前的 proxy_manager.py.orig 以另一模块名加载，做同进程 A/B。"""
    if not os.path.exists(BASELINE_PATH):
        raise SystemExit("缺少 proxy_manager.py.orig，无法做 A/B")
    loader = importlib.machinery.SourceFileLoader("pm_baseline", BASELINE_PATH)
    spec = importlib.util.spec_from_loader("pm_baseline", loader)
    module = importlib.util.module_from_spec(spec)
    sys.modules["pm_baseline"] = module
    loader.exec_module(module)
    return module


class TcpSinkFarm:
    """accept 即 close 的 TCP sink 集群，用于真实地跑 TCP 预筛。"""

    def __init__(self, count: int, base_port: int = 22000) -> None:
        self._count = count
        self._base_port = base_port
        self._servers: list[asyncio.AbstractServer] = []
        self.ports: list[int] = []

    async def start(self) -> "TcpSinkFarm":
        port = self._base_port
        for _ in range(self._count):
            server = None
            for _attempt in range(64):
                try:
                    candidate = await asyncio.start_server(
                        self._handle, "127.0.0.1", port
                    )
                except OSError:
                    port += 1
                    continue
                server = candidate
                break
            if server is None:
                await self.stop()
                raise OSError(f"从 {self._base_port} 起找不到可用端口")
            self._servers.append(server)
            self.ports.append(server.sockets[0].getsockname()[1])
            port += 1
        return self

    @staticmethod
    async def _handle(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        try:
            writer.close()
            await asyncio.wait_for(writer.wait_closed(), 0.5)
        except BaseException:
            try:
                writer.transport.abort()
            except Exception:
                pass

    async def stop(self) -> None:
        for server in self._servers:
            server.close()
            try:
                await server.wait_closed()
            except Exception:
                pass
        self._servers.clear()


def build_entries(total: int, ports: list[int], socks5_ratio: float = 0.9):
    entries = []
    socks5_total = int(total * socks5_ratio)
    for index in range(total):
        entries.append(
            {
                "host": "127.0.0.1",
                "port": ports[index % len(ports)],
                "type": "socks5" if index < socks5_total else "mtproto",
                "status": "available",
            }
        )
    return entries


def make_fake_gates(rtt_ms: float):
    """把两关换成各 sleep(RTT/2) 的合成闸门（沙箱无法做真实 TLS）。"""
    half = rtt_ms / 2.0

    def fake_dc(host, port, timeout=None, **kwargs):
        time.sleep(half / 1000.0)
        return half

    def fake_web(host, port, timeout=None, **kwargs):
        time.sleep(half / 1000.0)
        return half

    return fake_dc, fake_web


async def run(args) -> dict:
    baseline = load_baseline()
    report: dict = {
        "kind": "phase3",
        "nodes": args.nodes,
        "rtt_ms": args.rtt_ms,
        "fetch_timeout_s": args.fetch_timeout,
        "workers_tested": args.workers,
        "reality": {
            "fetch": "real socket (blackhole + healthy mirror)",
            "tcp": "real socket (300 sinks, 3000 clients)",
            "tg": "synthetic gate (sleep RTT/2), no TLS, no packet loss",
        },
    }

    blackhole = await AsyncHttpMock(mode="blackhole", hold_s=60.0).start()
    healthy = await AsyncHttpMock(mode="ok").start()
    sinks = await TcpSinkFarm(min(300, max(32, args.nodes // 10))).start()
    blackhole_url = f"http://127.0.0.1:{blackhole.port}/list"
    healthy_url = f"http://127.0.0.1:{healthy.port}/list"

    try:
        # 两个模块都指向同一个健康镜像；直连仍是黑洞
        pm.mirror_urls = lambda url: [healthy_url]
        baseline.mirror_urls = lambda url: [healthy_url]

        started = time.perf_counter()
        base_text, base_via = await asyncio.to_thread(
            baseline.fetch_proxy_text, blackhole_url, args.fetch_timeout
        )
        fetch_baseline_s = time.perf_counter() - started

        started = time.perf_counter()
        race_text, race_via = await asyncio.to_thread(
            pm.fetch_proxy_text, blackhole_url, args.fetch_timeout
        )
        fetch_race_s = time.perf_counter() - started

        assert base_text == race_text, "两条路径抓到的内容不一致"
        assert base_via and race_via, "镜像路径应标记 via_mirror=True"

        report["fetch"] = {
            "baseline_ms": round(fetch_baseline_s * 1000, 1),
            "race_ms": round(fetch_race_s * 1000, 1),
            "speedup": round(fetch_baseline_s / max(fetch_race_s, 0.001), 1),
            "same_content": True,
            "via_mirror": True,
        }
        print(
            f"fetch   baseline={fetch_baseline_s*1000:>8.1f}ms  "
            f"race={fetch_race_s*1000:>7.1f}ms  "
            f"speedup={report['fetch']['speedup']}x"
        )

        # ---- TCP 预筛（真实 socket，两版逻辑一致，只跑一次）----
        manager = pm.ProxyManager(max_tg_latency_ms=pm.TG_LATENCY_LIMIT_MS)
        entries = build_entries(args.nodes, sinks.ports)
        started = time.perf_counter()
        tcp_result = await asyncio.to_thread(manager._test_entries, entries)
        tcp_s = time.perf_counter() - started
        tcp_ok = [item for item in tcp_result if item["status"] == "available"]
        report["tcp"] = {
            "ms": round(tcp_s * 1000, 1),
            "tested": len(tcp_result),
            "ok": len(tcp_ok),
        }
        print(
            f"tcp     {tcp_s*1000:>8.1f}ms  tested={len(tcp_result)} ok={len(tcp_ok)}"
        )

        # ---- TG 实测（合成闸门）----
        fake_dc, fake_web = make_fake_gates(args.rtt_ms)
        baseline.TG_TEST_MAX_WORKERS = 100
        baseline.test_socks5_telegram_dc = fake_dc
        baseline.test_telegram_web_via_socks5 = fake_web
        baseline_manager = baseline.ProxyManager(max_tg_latency_ms=baseline.TG_LATENCY_LIMIT_MS)

        started = time.perf_counter()
        await asyncio.to_thread(baseline_manager._test_telegram_entries, tcp_ok)
        tg_baseline_s = time.perf_counter() - started
        report["tg_baseline"] = {"workers": 100, "ms": round(tg_baseline_s * 1000, 1)}
        print(f"tg      workers=100   {tg_baseline_s*1000:>8.1f}ms  (baseline)")

        pm.test_socks5_telegram_dc = fake_dc
        pm.test_telegram_web_via_socks5 = fake_web
        tg_cells = []
        for workers in args.workers:
            pm.TG_TEST_MAX_WORKERS = workers
            started = time.perf_counter()
            await asyncio.to_thread(manager._test_telegram_entries, tcp_ok)
            elapsed = time.perf_counter() - started
            tg_cells.append({"workers": workers, "ms": round(elapsed * 1000, 1)})
            print(f"tg      workers={workers:<4}{elapsed*1000:>8.1f}ms")
        report["tg"] = tg_cells

        best = min(tg_cells, key=lambda cell: cell["ms"])
        report["tg_best"] = best

        total_before = fetch_baseline_s + tcp_s + tg_baseline_s
        total_after = fetch_race_s + tcp_s + best["ms"] / 1000.0
        report["total"] = {
            "before_s": round(total_before, 2),
            "after_s": round(total_after, 2),
            "saved_s": round(total_before - total_after, 2),
            "speedup": round(total_before / max(total_after, 0.001), 2),
        }
        print(
            f"total   before={total_before:>6.2f}s  after={total_after:>6.2f}s  "
            f"saved={total_before-total_after:>5.2f}s  "
            f"speedup={report['total']['speedup']}x"
        )
        return report
    finally:
        await blackhole.stop()
        await healthy.stop()
        await sinks.stop()


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="tgproxy Phase 3 全量 A/B")
    parser.add_argument("--nodes", type=int, default=3000)
    parser.add_argument("--rtt-ms", type=float, default=60.0)
    parser.add_argument("--fetch-timeout", type=float, default=15.0)
    parser.add_argument("--workers", default="100,250,300")
    parser.add_argument("--json", default=None)
    args = parser.parse_args(argv)
    args.workers = [int(value) for value in args.workers.split(",")]

    report = asyncio.run(run(args))
    if args.json:
        with open(args.json, "w", encoding="utf-8") as handle:
            json.dump(report, handle, ensure_ascii=False, indent=2)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
