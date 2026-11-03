# -*- coding: utf-8 -*-
"""镜像竞速：完整性判定 + 竞速策略。

重点不是「快」，而是「不会选中快但坏的响应」。
"""

import asyncio
import gzip
import os
import sys
import time

_HERE = os.path.dirname(os.path.abspath(__file__))
_ROOT = os.path.dirname(_HERE)
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

import mirror_race as mr  # noqa: E402
from mock_http_mirror import AsyncHttpMock  # noqa: E402

GOOD_BODY = b"".join(
    b"%d.%d.%d.%d:1080\n" % (i, i, i, i) for i in range(1, 11)
)


# ---------------------------------------------------------------------------
# 纯函数：完整性判定


def test_evaluate_rejects_content_length_mismatch():
    code, body, detail = mr.evaluate_response(
        200, {"content-length": "1000"}, b"x" * 10
    )
    assert code == mr.FetchCode.TRUNCATED, detail
    assert body == ""


def test_evaluate_accepts_gzip_body():
    text = GOOD_BODY.decode()
    compressed = gzip.compress(GOOD_BODY)
    code, body, detail = mr.evaluate_response(
        200,
        {"content-length": str(len(compressed)), "content-encoding": "gzip"},
        compressed,
    )
    assert code == mr.FetchCode.OK, detail
    assert body == text


def test_evaluate_rejects_non_200_and_short_and_empty_encoding():
    assert mr.evaluate_response(500, {}, GOOD_BODY)[0] == mr.FetchCode.BAD_STATUS
    assert mr.evaluate_response(200, {}, b"tiny")[0] == mr.FetchCode.EMPTY
    assert (
        mr.evaluate_response(
            200, {"content-encoding": "br"}, b"x" * 100
        )[0]
        == mr.FetchCode.UNDECODABLE
    )


def test_evaluate_rejects_invalid_utf8():
    code, _body, detail = mr.evaluate_response(200, {}, b"\xff\xfe" * 100)
    assert code == mr.FetchCode.UNDECODABLE, detail


def test_validator_rejects_comment_only_body():
    body = b"# only comments here\n" * 10
    code, _body, detail = mr.evaluate_response(200, {}, body)
    assert code == mr.FetchCode.UNUSABLE, detail


def test_build_candidates_maps_raw_github_to_jsdelivr():
    url = "https://raw.githubusercontent.com/user/repo/main/dir/list.txt"
    candidates = mr.build_candidates(url)

    assert [c.label for c in candidates] == [
        "direct",
        "jsdelivr-cdn",
        "jsdelivr-gcore",
        "jsdelivr-fastly",
    ]
    assert candidates[0].url == url
    assert candidates[1].url == "https://cdn.jsdelivr.net/gh/user/repo@main/dir/list.txt"
    assert candidates[2].url == "https://gcore.jsdelivr.net/gh/user/repo@main/dir/list.txt"


def test_build_candidates_passes_through_non_github_url():
    candidates = mr.build_candidates("https://example.com/list.txt")
    assert len(candidates) == 1
    assert candidates[0].label == "direct"


# ---------------------------------------------------------------------------
# 竞速


def test_race_prefers_healthy_mirror_over_blackhole():
    async def body():
        blackhole = await AsyncHttpMock(mode="blackhole", hold_s=2.0).start()
        healthy = await AsyncHttpMock(body=GOOD_BODY, mode="ok").start()
        try:
            candidates = [
                mr.Candidate(f"http://127.0.0.1:{blackhole.port}/list", 0, "direct"),
                mr.Candidate(f"http://127.0.0.1:{healthy.port}/list", 1, "mirror"),
            ]
            started = time.monotonic()
            result = await mr.race_mirrors_async(
                candidates, timeout=3.0, connect_timeout=1.0, stagger_ms=50
            )
            return result, time.monotonic() - started
        finally:
            await blackhole.stop()
            await healthy.stop()

    result, elapsed = asyncio.run(body())

    assert result.ok, result.attempts
    assert result.source == "mirror"
    assert "1.1.1.1:1080" in result.body
    # 黑洞直连应被 stagger + 镜像耗时甩开，而不是等满 connect_timeout
    assert elapsed < 1.5, f"竞速耗时 {elapsed:.2f}s，疑似在等黑洞"


def test_truncated_response_is_rejected():
    async def body():
        server = await AsyncHttpMock(mode="truncated").start()
        try:
            return await mr.race_mirrors_async(
                [mr.Candidate(f"http://127.0.0.1:{server.port}/list", 0, "truncated")],
                timeout=3.0,
                connect_timeout=1.0,
            )
        finally:
            await server.stop()

    result = asyncio.run(body())
    assert not result.ok
    assert any(a.code == mr.FetchCode.TRUNCATED for a in result.attempts), result.attempts


def test_empty_response_is_rejected():
    async def body():
        server = await AsyncHttpMock(mode="empty").start()
        try:
            return await mr.race_mirrors_async(
                [mr.Candidate(f"http://127.0.0.1:{server.port}/list", 0, "empty")],
                timeout=3.0,
                connect_timeout=1.0,
            )
        finally:
            await server.stop()

    result = asyncio.run(body())
    assert not result.ok
    assert any(a.code == mr.FetchCode.EMPTY for a in result.attempts), result.attempts


def test_bad_status_is_rejected():
    async def body():
        server = await AsyncHttpMock(mode="status", status=500).start()
        try:
            return await mr.race_mirrors_async(
                [mr.Candidate(f"http://127.0.0.1:{server.port}/list", 0, "500")],
                timeout=3.0,
                connect_timeout=1.0,
            )
        finally:
            await server.stop()

    result = asyncio.run(body())
    assert not result.ok
    assert any(a.code == mr.FetchCode.BAD_STATUS for a in result.attempts), result.attempts


def test_all_candidates_bad_returns_all_failed():
    async def body():
        truncated = await AsyncHttpMock(mode="truncated").start()
        empty = await AsyncHttpMock(mode="empty").start()
        bad = await AsyncHttpMock(mode="status", status=404).start()
        try:
            candidates = [
                mr.Candidate(f"http://127.0.0.1:{truncated.port}/a", 0, "a"),
                mr.Candidate(f"http://127.0.0.1:{empty.port}/b", 1, "b"),
                mr.Candidate(f"http://127.0.0.1:{bad.port}/c", 2, "c"),
            ]
            return await mr.race_mirrors_async(
                candidates, timeout=3.0, connect_timeout=1.0, stagger_ms=10
            )
        finally:
            await truncated.stop()
            await empty.stop()
            await bad.stop()

    result = asyncio.run(body())
    assert not result.ok
    assert result.code == mr.FetchCode.ALL_FAILED
    assert len(result.attempts) == 3
    assert all(not a.ok for a in result.attempts)


def test_direct_success_wins():
    async def body():
        server = await AsyncHttpMock(body=GOOD_BODY, mode="ok").start()
        try:
            return await mr.race_mirrors_async(
                [mr.Candidate(f"http://127.0.0.1:{server.port}/list", 0, "direct")],
                timeout=3.0,
                connect_timeout=1.0,
            )
        finally:
            await server.stop()

    result = asyncio.run(body())
    assert result.ok
    assert result.source == "direct"
    assert "4.4.4.4:1080" in result.body


def test_empty_candidate_list_is_not_an_exception():
    result = asyncio.run(mr.race_mirrors_async([]))
    assert not result.ok
    assert result.code == mr.FetchCode.ALL_FAILED


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
