# -*- coding: utf-8 -*-
"""Phase 2 离线基准：异步引擎 vs 线程模型；镜像竞速 vs 顺序回退。

用法：
    python bench_phase2.py nodes                 # 100 节点三引擎对比
    python bench_phase2.py nodes --rounds 3
    python bench_phase2.py mirrors               # 顺序回退 vs 竞速
    python bench_phase2.py micro                 # AES / urandom / sha256 微基准
    python bench_phase2.py nodes --json out.json

口径声明（必须和数字一起看）：
- 全部是 127.0.0.1 loopback，RTT ≈0。它度量的是**引擎开销与可扩展性**，
  不能预测 WAN 的 RTT 主导行为、丢包重传、NAT 超时、半开连接。
- 0/50/200ms 是人为注入的延迟、无丢包。只用来把「开销主导」和「延迟主导」分开看。
- **mock 服务端跑在独立子进程**。这一点很关键：如果服务端和异步客户端共用一个
  event loop，客户端会和服务端抢同一个线程，async 会被系统性低估（实测 0ms 档
  async 反而最慢，就是共 loop 造成的假象）。
- 计时只包住客户端批量调用；服务端起停不计入。
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import statistics
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor

_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)
_TESTS = os.path.join(_HERE, "tests")
if _TESTS not in sys.path:
    sys.path.insert(0, _TESTS)

import mirror_race as mr  # noqa: E402
import mtproto_async as ma  # noqa: E402
import mtproto_handshake as mh  # noqa: E402
from mock_http_mirror import AsyncHttpMock  # noqa: E402
from mock_mtproxy_async import AsyncMockFarm  # noqa: E402

SECRET_HEX = "4fc30d1222972a04067e805a5134cb57"
SECRET = bytes.fromhex(SECRET_HEX)
ENGINES = ("async", "to_thread", "threadpool")


# ---------------------------------------------------------------------------
# 内存（Windows）


def peak_working_set() -> int | None:
    if os.name != "nt":
        return None
    import ctypes
    from ctypes import wintypes

    class PROCESS_MEMORY_COUNTERS(ctypes.Structure):
        _fields_ = [
            ("cb", wintypes.DWORD),
            ("PageFaultCount", wintypes.DWORD),
            ("PeakWorkingSetSize", ctypes.c_size_t),
            ("WorkingSetSize", ctypes.c_size_t),
            ("QuotaPeakPagedPoolUsage", ctypes.c_size_t),
            ("QuotaPagedPoolUsage", ctypes.c_size_t),
            ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t),
            ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
            ("PagefileUsage", ctypes.c_size_t),
            ("PeakPagefileUsage", ctypes.c_size_t),
        ]

    counters = PROCESS_MEMORY_COUNTERS()
    counters.cb = ctypes.sizeof(counters)

    # GetCurrentProcess 返回伪句柄 (HANDLE)-1。不显式声明 restype，ctypes 默认按
    # c_int 处理，64 位下会被截断成 0xFFFFFFFF，GetProcessMemoryInfo 直接失败。
    kernel32 = ctypes.WinDLL("kernel32")
    kernel32.GetCurrentProcess.restype = wintypes.HANDLE
    handle = kernel32.GetCurrentProcess()

    psapi = ctypes.WinDLL("psapi")
    get_info = psapi.GetProcessMemoryInfo
    get_info.argtypes = [
        wintypes.HANDLE,
        ctypes.POINTER(PROCESS_MEMORY_COUNTERS),
        wintypes.DWORD,
    ]
    get_info.restype = wintypes.BOOL

    ok = get_info(handle, ctypes.byref(counters), counters.cb)
    return counters.PeakWorkingSetSize if ok else None


# ---------------------------------------------------------------------------
# 服务端子进程


class ServerProcess:
    """把 mock 集群放进独立进程，客户端只测自己那一侧的开销。"""

    def __init__(self, count: int, delay_ms: int, base_port: int) -> None:
        self._count = count
        self._delay_ms = delay_ms
        self._base_port = base_port
        self._process: subprocess.Popen | None = None
        self.ports: list[int] = []

    def __enter__(self) -> "ServerProcess":
        command = [
            sys.executable,
            os.path.abspath(__file__),
            "serve",
            "--nodes",
            str(self._count),
            "--delay",
            str(self._delay_ms),
            "--base-port",
            str(self._base_port),
        ]
        self._process = subprocess.Popen(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        assert self._process.stdout is not None
        line = self._process.stdout.readline()
        if not line:
            error = ""
            if self._process.stderr is not None:
                error = self._process.stderr.read()[-800:]
            raise RuntimeError(f"服务端子进程没有就绪: {error}")
        payload = json.loads(line)
        if "error" in payload:
            raise RuntimeError(payload["error"])
        self.ports = payload["ports"]
        return self

    def __exit__(self, *exc_info) -> bool:
        if self._process is not None:
            self._process.terminate()
            try:
                self._process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self._process.kill()
        return False


async def _serve(args) -> int:
    farm = await AsyncMockFarm(
        args.nodes, SECRET, base_port=args.base_port, delay_ms=args.delay
    ).start()
    try:
        print(json.dumps({"ports": farm.ports}), flush=True)
        while True:
            await asyncio.sleep(3600)
    finally:
        await farm.stop()


# ---------------------------------------------------------------------------
# 单轮执行


def _sync_check(node: ma.ProxyNode, budget: float) -> mh.CheckResult:
    return mh.check_mtproto_proxy(
        node.host,
        node.port,
        node.secret,
        connect_timeout=min(budget / 2, 1.0),
        response_timeout=budget,
    )


def _threadpool_blocking(
    nodes: list[ma.ProxyNode], concurrency: int, budget: float
) -> list[mh.CheckResult]:
    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = [executor.submit(_sync_check, node, budget) for node in nodes]
        return [future.result() for future in futures]


async def run_round(engine: str, nodes: list[ma.ProxyNode], concurrency: int, budget: float):
    started = time.perf_counter()
    if engine == "async":
        results = await ma.check_many(nodes, concurrency=concurrency, total_budget=budget)
    elif engine == "to_thread":
        semaphore = asyncio.Semaphore(concurrency)

        async def one(node: ma.ProxyNode):
            async with semaphore:
                return await asyncio.to_thread(_sync_check, node, budget)

        results = list(await asyncio.gather(*(one(node) for node in nodes)))
    elif engine == "threadpool":
        results = await asyncio.get_running_loop().run_in_executor(
            None, _threadpool_blocking, nodes, concurrency, budget
        )
    else:
        raise ValueError(engine)
    return time.perf_counter() - started, results


def _error_histogram(results: list[mh.CheckResult]) -> dict[str, int]:
    histogram: dict[str, int] = {}
    for result in results:
        key = "OK" if result.ok else (result.error_code or "UNKNOWN")
        histogram[key] = histogram.get(key, 0) + 1
    return histogram


def _p(values: list[float], q: float) -> float:
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, int(round(q * (len(ordered) - 1)))))
    return ordered[index]


# ---------------------------------------------------------------------------
# nodes 基准


def bench_nodes(args) -> dict:
    return asyncio.run(_bench_nodes_async(args))


async def _bench_nodes_async(args) -> dict:
    delays = [int(value) for value in args.delays.split(",")]
    concurrencies = [int(value) for value in args.concurrencies.split(",")]
    report: dict = {
        "kind": "nodes",
        "node_count": args.nodes,
        "rounds": args.rounds,
        "budget_s": args.budget,
        "cells": [],
        "micro": bench_micro(args),
        "environment": {
            "python": sys.version.split()[0],
            "platform": sys.platform,
            "aes_backend": mh.aes_backend_name(),
            "server_isolated": True,
            "note": "loopback only; measures overhead/scalability, not WAN latency",
        },
    }

    for delay in delays:
        with ServerProcess(args.nodes, delay, args.base_port) as server:
            nodes = [ma.ProxyNode("127.0.0.1", port, SECRET_HEX) for port in server.ports]
            for engine in ENGINES:
                for concurrency in concurrencies:
                    await run_round(engine, nodes, concurrency, args.budget)  # warmup

                    times: list[float] = []
                    last_results: list[mh.CheckResult] = []
                    for _ in range(args.rounds):
                        elapsed, results = await run_round(
                            engine, nodes, concurrency, args.budget
                        )
                        times.append(elapsed)
                        last_results = results
                        if args.cooldown > 0:
                            await asyncio.sleep(args.cooldown)

                    errors = _error_histogram(last_results)
                    failures = sum(count for key, count in errors.items() if key != "OK")
                    if failures > len(nodes) * 0.05:
                        print(
                            f"  [warn] {failures}/{len(nodes)} 失败，疑似 Windows 临时端口耗尽"
                            f"（TIME_WAIT）。减少 --rounds / 降并发 / 加大 --cooldown。"
                        )
                    cell = {
                        "engine": engine,
                        "delay_ms": delay,
                        "concurrency": concurrency,
                        "rounds": args.rounds,
                        "total_p50_ms": round(statistics.median(times) * 1000, 1),
                        "total_p95_ms": round(_p(times, 0.95) * 1000, 1),
                        "per_node_ms": round(statistics.median(times) * 1000 / len(nodes), 3),
                        "throughput_per_s": round(len(nodes) / statistics.median(times), 1),
                        "errors": errors,
                        "rtt_p50_ms": round(
                            _p([r.rtt_ms for r in last_results if r.rtt_ms is not None], 0.5), 2
                        ) if any(r.rtt_ms is not None for r in last_results) else None,
                    }
                    report["cells"].append(cell)
                    print(
                        f"delay={delay:>3}ms engine={engine:<10} conc={concurrency:<3} "
                        f"p50={cell['total_p50_ms']:>7.1f}ms p95={cell['total_p95_ms']:>7.1f}ms "
                        f"{cell['throughput_per_s']:>6.1f} nodes/s errors={cell['errors']}"
                    )

    return report


def _engine_only(args) -> dict:
    with ServerProcess(args.nodes, 0, args.base_port) as server:
        nodes = [ma.ProxyNode("127.0.0.1", port, SECRET_HEX) for port in server.ports]
        asyncio.run(run_round(args.engine_only, nodes, args.nodes, args.budget))
    return {
        "engine": args.engine_only,
        "peak_working_set": peak_working_set(),
        "nodes": args.nodes,
    }


def bench_nodes_memory(args) -> dict:
    out = {}
    for engine in ENGINES:
        command = [
            sys.executable,
            os.path.abspath(__file__),
            "nodes",
            "--engine-only",
            engine,
            "--nodes",
            str(args.nodes),
            "--rounds",
            "1",
            "--budget",
            str(args.budget),
            "--base-port",
            str(args.base_port + 200),
        ]
        completed = subprocess.run(command, capture_output=True, text=True, timeout=300)
        try:
            out[engine] = json.loads(completed.stdout.strip().splitlines()[-1])
        except Exception:
            out[engine] = {"error": completed.stderr[-400:] or "no output"}
    return out


# ---------------------------------------------------------------------------
# mirrors 基准


def bench_mirrors(args) -> dict:
    async def body():
        blackhole = await AsyncHttpMock(mode="blackhole", hold_s=30.0).start()
        fast = await AsyncHttpMock(mode="ok").start()
        truncated = await AsyncHttpMock(mode="truncated").start()
        empty = await AsyncHttpMock(mode="empty").start()
        try:
            candidates = [
                mr.Candidate(f"http://127.0.0.1:{blackhole.port}/list", 0, "direct"),
                mr.Candidate(f"http://127.0.0.1:{fast.port}/list", 1, "mirror-fast"),
            ]
            race_times = []
            race_result = None
            for _ in range(args.rounds):
                started = time.perf_counter()
                race_result = await mr.race_mirrors_async(
                    candidates,
                    timeout=args.budget,
                    connect_timeout=args.connect_timeout,
                    stagger_ms=250,
                )
                race_times.append(time.perf_counter() - started)

            sequential_started = time.perf_counter()
            sequential_ok = False
            for candidate in candidates:
                attempt = await mr._attempt(
                    candidate,
                    connect_timeout=args.connect_timeout,
                    min_bytes=mr.MIN_BYTES,
                    validator=mr.default_validator,
                    loop=asyncio.get_running_loop(),
                )
                if attempt.ok:
                    sequential_ok = True
                    break
            sequential_elapsed = time.perf_counter() - sequential_started

            bad_candidates = [
                mr.Candidate(f"http://127.0.0.1:{truncated.port}/a", 0, "truncated"),
                mr.Candidate(f"http://127.0.0.1:{empty.port}/b", 1, "empty"),
            ]
            bad_result = await mr.race_mirrors_async(
                bad_candidates, timeout=args.budget, connect_timeout=args.connect_timeout
            )

            return {
                "race_p50_ms": round(statistics.median(race_times) * 1000, 1),
                "race_p95_ms": round(_p(race_times, 0.95) * 1000, 1),
                "race_source": race_result.source if race_result else None,
                "race_ok": race_result.ok if race_result else False,
                "sequential_ms": round(sequential_elapsed * 1000, 1),
                "sequential_ok": sequential_ok,
                "bad_rejected": (not bad_result.ok),
                "bad_codes": [a.code for a in bad_result.attempts],
                "connect_timeout_s": args.connect_timeout,
            }
        finally:
            await blackhole.stop()
            await fast.stop()
            await truncated.stop()
            await empty.stop()

    return {"kind": "mirrors", **asyncio.run(body())}


# ---------------------------------------------------------------------------
# 微基准


def bench_micro(_args) -> dict:
    loops = 10000

    def timeit(fn) -> float:
        started = time.perf_counter()
        for _ in range(loops):
            fn()
        return (time.perf_counter() - started) * 1000.0 / loops * 1000.0  # µs/op

    key, iv = os.urandom(32), os.urandom(16)
    results = {
        "urandom_64_us": round(timeit(lambda: os.urandom(64)), 3),
        "aes_ctr_64_us": round(timeit(lambda: mh.aes_ctr(key, iv).update(b"\x00" * 64)), 3),
        "sha256_48_us": round(
            timeit(lambda: __import__("hashlib").sha256(b"\x00" * 48).digest()), 3
        ),
        "parse_secret_us": round(timeit(lambda: mh.parse_secret(SECRET_HEX)), 3),
    }
    per_node = (
        results["urandom_64_us"] * 2
        + results["aes_ctr_64_us"] * 4
        + results["sha256_48_us"] * 2
        + results["parse_secret_us"]
    )
    results["sync_work_per_node_us"] = round(per_node, 3)
    results["share_of_2s_budget_pct"] = round(per_node / 2_000_000 * 100, 6)
    results["verdict"] = "无需 to_thread" if per_node < 100 else "建议 offload"
    return results


# ---------------------------------------------------------------------------
# CLI


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="tgproxy Phase 2 离线基准")
    sub = parser.add_subparsers(dest="command", required=True)

    serve = sub.add_parser("serve", help=argparse.SUPPRESS)
    serve.add_argument("--nodes", type=int, default=100)
    serve.add_argument("--delay", type=int, default=0)
    serve.add_argument("--base-port", type=int, default=20000)

    nodes = sub.add_parser("nodes", help="异步引擎 vs 线程模型")
    nodes.add_argument("--nodes", type=int, default=100)
    nodes.add_argument("--rounds", type=int, default=3)
    nodes.add_argument("--budget", type=float, default=2.0)
    nodes.add_argument("--delays", default="0,200")
    nodes.add_argument("--concurrencies", default="50,100")
    nodes.add_argument("--cooldown", type=float, default=0.5)
    nodes.add_argument("--base-port", type=int, default=20000)
    nodes.add_argument("--engine-only", default=None, help=argparse.SUPPRESS)
    nodes.add_argument("--json", default=None)

    mirrors = sub.add_parser("mirrors", help="顺序回退 vs 竞速")
    mirrors.add_argument("--rounds", type=int, default=3)
    mirrors.add_argument("--budget", type=float, default=8.0)
    mirrors.add_argument("--connect-timeout", type=float, default=3.0)
    mirrors.add_argument("--json", default=None)

    micro = sub.add_parser("micro", help="同步阻塞占比微基准")
    micro.add_argument("--json", default=None)

    args = parser.parse_args(argv)

    if args.command == "serve":
        try:
            asyncio.run(_serve(args))
        except KeyboardInterrupt:
            pass
        return 0

    if args.command == "nodes":
        if args.engine_only:
            print(json.dumps(_engine_only(args)))
            return 0

        report = bench_nodes(args)
        report["memory"] = bench_nodes_memory(args)
        for engine, payload in report["memory"].items():
            peak = payload.get("peak_working_set")
            if peak:
                print(
                    f"memory engine={engine:<10} peak_ws={peak/1024/1024:>7.1f} MiB "
                    f"({peak/args.nodes/1024:>7.1f} KiB/node)"
                )
        if args.json:
            with open(args.json, "w", encoding="utf-8") as handle:
                json.dump(report, handle, ensure_ascii=False, indent=2)
        return 0

    if args.command == "mirrors":
        report = bench_mirrors(args)
        print(
            f"sequential={report['sequential_ms']:>7.1f}ms  race_p50={report['race_p50_ms']:>7.1f}ms  "
            f"race_p95={report['race_p95_ms']:>7.1f}ms  source={report['race_source']}"
        )
        print(
            f"speedup≈{report['sequential_ms']/max(report['race_p50_ms'],0.001):.1f}x  "
            f"bad_rejected={report['bad_rejected']} codes={report['bad_codes']}"
        )
        if args.json:
            with open(args.json, "w", encoding="utf-8") as handle:
                json.dump(report, handle, ensure_ascii=False, indent=2)
        return 0

    report = bench_micro(args)
    for key, value in report.items():
        print(f"{key}: {value}")
    if args.json:
        with open(args.json, "w", encoding="utf-8") as handle:
            json.dump(report, handle, ensure_ascii=False, indent=2)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
