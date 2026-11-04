# -*- coding: utf-8 -*-
"""镜像竞速：把「直连失败 → 逐个回退 jsDelivr」换成「多候选并发、首个完整响应胜出」。

为什么值得改：桌面版一轮 44.76s 里 fetch 占 37%，而其中很大一块是
GitHub 直连被墙后硬烧 15s 才回退镜像。竞速把最坏等待压到「stagger + 镜像 RTT」。

两条硬规则：
1. **必须由任务自己校验完再返回 ok**。不能「先到先得再校验」——否则会选中
   「快但坏」的截断响应，然后卡在解析阶段。
2. **胜出即取消其余**。否则黑洞候选的 socket 会挂满整个 connect_timeout。

「完整响应」判定顺序不可颠倒：状态码 → 字节数对 Content-Length → 非空 →
解压 → UTF-8 解码 → validator。Content-Length 是压缩前语义，所以必须先比字节数再解压。

残余风险（写在这里而不是藏着）：既无 Content-Length 又非 chunked 的响应，
传输层无法发现截断，只能靠 validator 兜第二道。
"""

from __future__ import annotations

import asyncio
import gzip
import http.client
import time
import urllib.error
import urllib.request
import zlib
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from typing import Callable, Optional, Sequence

DEFAULT_USER_AGENT = "tgproxy/1.0 (local Telegram proxy manager)"
DEFAULT_TIMEOUT = 8.0
DEFAULT_CONNECT_TIMEOUT = 3.0
DEFAULT_STAGGER_MS = 250
MIN_BYTES = 64


class FetchCode:
    OK = "OK"
    BAD_STATUS = "BAD_STATUS"
    TRUNCATED = "TRUNCATED"
    EMPTY = "EMPTY"
    UNDECODABLE = "UNDECODABLE"
    UNUSABLE = "UNUSABLE"
    TIMEOUT = "TIMEOUT"
    NETWORK = "NETWORK"
    ALL_FAILED = "ALL_MIRRORS_FAILED"


@dataclass(frozen=True)
class Candidate:
    url: str
    weight: int = 0
    label: str = ""


@dataclass(frozen=True)
class Attempt:
    label: str
    url: str
    ok: bool
    code: str = ""
    status: int = 0
    bytes: int = 0
    elapsed_ms: float = 0.0
    detail: str = ""
    body: str = ""
    # CDN 缓存年龄（秒）。取自响应头 Age，仅镜像/代理链路会有；
    # None 表示「没有这个头」（直连源通常如此），不是「年龄为 0」。
    age_seconds: Optional[int] = None


@dataclass(frozen=True)
class FetchResult:
    ok: bool
    code: str
    body: str = ""
    source: str = ""
    attempts: tuple[Attempt, ...] = ()
    age_seconds: Optional[int] = None


# ---------------------------------------------------------------------------
# 完整性判定


def default_validator(text: str) -> bool:
    """至少一条非空、非注释行。免费代理列表的最小可用性判据。"""
    for line in text.splitlines():
        stripped = line.strip()
        if stripped and not stripped.startswith("#"):
            return True
    return False


def age_seconds(headers: dict[str, str]) -> Optional[int]:
    """从响应头读 CDN 缓存年龄（秒）。

    jsDelivr 的 `Cache-Control: ... s-maxage=43200` 意味着列表最长可能陈旧
    12 小时，而 `?v=` 之类的查询串对缓存键无效（实测无效）。`Age` 是唯一
    能编程读到的新鲜度信号——读不到就返回 None，绝不臆测为 0。
    """
    if not headers:
        return None
    raw = headers.get("age")
    if raw is None:
        return None
    try:
        value = int(str(raw).strip())
    except (TypeError, ValueError):
        return None
    return value if value >= 0 else None


def _decompress(raw: bytes, encoding: str) -> bytes:
    encoding = (encoding or "").strip().lower()
    if not encoding or encoding == "identity":
        return raw
    if "gzip" in encoding:
        return gzip.decompress(raw)
    if "deflate" in encoding:
        try:
            return zlib.decompress(raw)
        except zlib.error:
            return zlib.decompress(raw, -zlib.MAX_WBITS)
    if "br" in encoding:
        import brotli  # 可选依赖，缺了就明确失败而不是猜

        return brotli.decompress(raw)
    raise ValueError(f"未知 Content-Encoding: {encoding}")


def evaluate_response(
    status: int,
    headers: dict[str, str],
    raw: bytes,
    *,
    min_bytes: int = MIN_BYTES,
    validator: Callable[[str], bool] = default_validator,
    utf8_errors: str = "strict",
) -> tuple[str, str, str]:
    """返回 (code, body_text, detail)。code == FetchCode.OK 才算可用。"""
    if status != 200:
        return FetchCode.BAD_STATUS, "", f"状态码 {status}"

    declared = headers.get("content-length")
    if declared is not None:
        try:
            expected = int(declared)
        except ValueError:
            expected = -1
        if expected >= 0 and expected != len(raw):
            return (
                FetchCode.TRUNCATED,
                "",
                f"Content-Length {expected} 与实际 {len(raw)} 不符",
            )

    if len(raw) < min_bytes:
        return FetchCode.EMPTY, "", f"body 只有 {len(raw)} 字节（<{min_bytes}）"

    try:
        decoded_bytes = _decompress(raw, headers.get("content-encoding", ""))
    except Exception as exc:
        return FetchCode.UNDECODABLE, "", f"解压失败: {exc}"

    try:
        text = decoded_bytes.decode("utf-8", errors=utf8_errors)
    except UnicodeDecodeError as exc:
        return FetchCode.UNDECODABLE, "", f"UTF-8 解码失败: {exc}"

    try:
        usable = validator(text)
    except Exception as exc:
        return FetchCode.UNUSABLE, "", f"validator 异常: {exc}"
    if not usable:
        return FetchCode.UNUSABLE, "", "validator 判定不可用"

    return FetchCode.OK, text, ""


# ---------------------------------------------------------------------------
# 传输：urllib 跑在专用线程池


_EXECUTOR: Optional[ThreadPoolExecutor] = None


def _executor() -> ThreadPoolExecutor:
    global _EXECUTOR
    if _EXECUTOR is None:
        # 专用池：不复用 MTProto 的默认 DNS 线程池，避免互相排队。
        # 64 = 上游 FETCH_MAX_SOURCES(20) × 每源最多 4 个候选 的极端上界；
        # 若只有 8 个 worker，黑洞候选会把队列堵满一整个 connect_timeout。
        _EXECUTOR = ThreadPoolExecutor(max_workers=64, thread_name_prefix="mirror-race")
    return _EXECUTOR


def _blocking_fetch(url: str, timeout: float) -> tuple[int, dict[str, str], bytes]:
    request = urllib.request.Request(url, headers={"User-Agent": DEFAULT_USER_AGENT})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        status = getattr(response, "status", None) or response.getcode()
        headers = {key.lower(): value for key, value in response.headers.items()}
        raw = response.read()
    return int(status), headers, raw


async def _attempt(
    candidate: Candidate,
    *,
    connect_timeout: float,
    min_bytes: int,
    validator: Callable[[str], bool],
    loop: asyncio.AbstractEventLoop,
    utf8_errors: str = "strict",
) -> Attempt:
    label = candidate.label or candidate.url
    started = time.monotonic()

    try:
        status, headers, raw = await loop.run_in_executor(
            _executor(), _blocking_fetch, candidate.url, connect_timeout
        )
    except TimeoutError as exc:
        return Attempt(
            label, candidate.url, False, FetchCode.TIMEOUT,
            elapsed_ms=(time.monotonic() - started) * 1000.0,
            detail=f"超时: {exc}",
        )
    except urllib.error.HTTPError as exc:
        # 非 2xx 不是网络故障，是一个「带状态码的响应」。
        # HTTPError 是 URLError 的子类，必须排在它前面，否则 404/500 会被
        # 误判成 NETWORK —— 排查时会以为是网络问题。
        try:
            raw = exc.read()
        except Exception:
            raw = b""
        headers = {key.lower(): value for key, value in (exc.headers or {}).items()}
        code, body, detail = evaluate_response(
            int(exc.code), headers, raw, min_bytes=min_bytes, validator=validator,
            utf8_errors=utf8_errors,
        )
        return Attempt(
            label, candidate.url, code == FetchCode.OK, code, int(exc.code),
            len(raw), (time.monotonic() - started) * 1000.0, detail,
            body if code == FetchCode.OK else "",
        )
    except urllib.error.URLError as exc:
        code = FetchCode.TIMEOUT if isinstance(exc.reason, TimeoutError) else FetchCode.NETWORK
        return Attempt(
            label, candidate.url, False, code,
            elapsed_ms=(time.monotonic() - started) * 1000.0,
            detail=f"{type(exc.reason).__name__}: {exc.reason}",
        )
    except http.client.IncompleteRead as exc:
        return Attempt(
            label, candidate.url, False, FetchCode.TRUNCATED,
            elapsed_ms=(time.monotonic() - started) * 1000.0,
            detail=f"传输中断: {exc}",
        )
    except Exception as exc:
        return Attempt(
            label, candidate.url, False, FetchCode.NETWORK,
            elapsed_ms=(time.monotonic() - started) * 1000.0,
            detail=f"{type(exc).__name__}: {exc}",
        )

    code, body, detail = evaluate_response(
        status, headers, raw, min_bytes=min_bytes, validator=validator,
        utf8_errors=utf8_errors,
    )
    return Attempt(
        label,
        candidate.url,
        code == FetchCode.OK,
        code,
        status,
        len(raw),
        (time.monotonic() - started) * 1000.0,
        detail,
        body if code == FetchCode.OK else "",
        age_seconds(headers),
    )


# ---------------------------------------------------------------------------
# 竞速


async def race_mirrors_async(
    candidates: Sequence[Candidate],
    *,
    timeout: float = DEFAULT_TIMEOUT,
    connect_timeout: float = DEFAULT_CONNECT_TIMEOUT,
    stagger_ms: int = DEFAULT_STAGGER_MS,
    min_bytes: int = MIN_BYTES,
    validator: Callable[[str], bool] = default_validator,
    utf8_errors: str = "strict",
) -> FetchResult:
    items = list(candidates)
    if not items:
        return FetchResult(False, FetchCode.ALL_FAILED)

    ordered = sorted(items, key=lambda item: item.weight)
    loop = asyncio.get_running_loop()

    async def launch(index: int, candidate: Candidate) -> Attempt:
        if index and stagger_ms > 0:
            await asyncio.sleep(index * stagger_ms / 1000.0)
        return await _attempt(
            candidate,
            connect_timeout=connect_timeout,
            min_bytes=min_bytes,
            validator=validator,
            loop=loop,
            utf8_errors=utf8_errors,
        )

    tasks = [
        asyncio.create_task(launch(index, candidate))
        for index, candidate in enumerate(ordered)
    ]
    attempts: list[Attempt] = []
    winner: Optional[Attempt] = None

    try:
        async with asyncio.timeout(timeout):
            pending = set(tasks)
            while pending:
                done, pending = await asyncio.wait(
                    pending, return_when=asyncio.FIRST_COMPLETED
                )
                for task in done:
                    try:
                        attempt = task.result()
                    except Exception as exc:  # 理论上 _attempt 不会抛
                        attempt = Attempt(
                            "?", "?", False, FetchCode.NETWORK, detail=str(exc)
                        )
                    attempts.append(attempt)
                    if attempt.ok:
                        winner = attempt
                        break
                if winner is not None:
                    break
    except TimeoutError:
        pass
    finally:
        for task in tasks:
            if not task.done():
                task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)

    if winner is not None:
        return FetchResult(
            True, FetchCode.OK, winner.body, winner.label, tuple(attempts),
            winner.age_seconds,
        )
    return FetchResult(False, FetchCode.ALL_FAILED, attempts=tuple(attempts))


def race_mirrors(candidates: Sequence[Candidate], **kwargs) -> FetchResult:
    return asyncio.run(race_mirrors_async(candidates, **kwargs))


# ---------------------------------------------------------------------------
# 候选构造


def build_candidates(url: str) -> list[Candidate]:
    """raw.githubusercontent.com → 直连 + 三个 jsDelivr 节点。

    非 GitHub raw 地址只给直连（没有已知镜像，不要瞎猜）。
    """
    marker = "raw.githubusercontent.com/"
    if marker not in url:
        return [Candidate(url=url, weight=0, label="direct")]

    path = url.split(marker, 1)[1]
    parts = path.split("/", 3)
    if len(parts) != 4:
        return [Candidate(url=url, weight=0, label="direct")]

    user, repo, branch, rest = parts
    gh = f"{user}/{repo}@{branch}/{rest}"

    return [
        Candidate(url=url, weight=0, label="direct"),
        Candidate(url=f"https://cdn.jsdelivr.net/gh/{gh}", weight=1, label="jsdelivr-cdn"),
        Candidate(url=f"https://gcore.jsdelivr.net/gh/{gh}", weight=2, label="jsdelivr-gcore"),
        Candidate(url=f"https://fastly.jsdelivr.net/gh/{gh}", weight=3, label="jsdelivr-fastly"),
    ]


__all__ = [
    "Attempt",
    "Candidate",
    "DEFAULT_STAGGER_MS",
    "FetchCode",
    "FetchResult",
    "build_candidates",
    "default_validator",
    "evaluate_response",
    "race_mirrors",
    "race_mirrors_async",
]
