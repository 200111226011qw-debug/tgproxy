# -*- coding: utf-8 -*-
"""Phase 5：列表新鲜度（Age）与轮次记录里的 MTProto 计数。"""

import os
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
_ROOT = os.path.dirname(_HERE)
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

import mirror_race as mr  # noqa: E402
import proxy_manager as pm  # noqa: E402
import round_log  # noqa: E402


# ---------------------------------------------------------------------------
# Age 头解析


def test_age_parsed_from_header():
    assert mr.age_seconds({"age": "3405"}) == 3405


def test_age_missing_returns_none_not_zero():
    """没有 Age 头 ≠ 年龄为 0。把未知当 0 会伪造「刚刚更新」。"""
    assert mr.age_seconds({}) is None
    assert mr.age_seconds({"cache-control": "s-maxage=43200"}) is None


def test_age_garbage_returns_none():
    assert mr.age_seconds({"age": "not-a-number"}) is None
    assert mr.age_seconds({"age": "-5"}) is None
    assert mr.age_seconds({"age": ""}) is None


def test_age_strips_whitespace():
    assert mr.age_seconds({"age": "  120 "}) == 120


# ---------------------------------------------------------------------------
# 竞速结果传递


def test_race_propagates_winner_age(monkeypatch):
    """胜出候选的 Age 必须出现在 FetchResult 上。"""
    async def fake_attempt(candidate, **kwargs):
        return mr.Attempt(candidate.label, candidate.url, True, mr.FetchCode.OK,
                          body="1.1.1.1:1080\n", age_seconds=777)

    monkeypatch.setattr(mr, "_attempt", fake_attempt)

    result = mr.race_mirrors([mr.Candidate("http://x/list.txt", 0, "direct")])
    assert result.ok
    assert result.age_seconds == 777


# ---------------------------------------------------------------------------
# fetch_proxy_text 的 meta 出参


def test_fetch_proxy_text_records_age(monkeypatch):
    """成功竞速后，年龄从 thread-local 里可取到（不改函数签名）。"""
    monkeypatch.setattr(pm, "FETCH_RACE_ENABLED", True)
    monkeypatch.setattr(
        pm.mirror_race, "race_mirrors",
        lambda candidates, **kw: mr.FetchResult(
            True, mr.FetchCode.OK, "1.1.1.1:1080\n", "gcore.jsdelivr.net",
            (), age_seconds=1234,
        ),
    )
    text, via_mirror = pm.fetch_proxy_text("https://raw.githubusercontent.com/a/b.txt")
    assert text == "1.1.1.1:1080\n"
    assert via_mirror is True
    assert pm.consume_fetch_meta() == 1234
    # 取过一次就清空，不会被下一个源误读
    assert pm.consume_fetch_meta() is None


def test_fetch_proxy_text_resets_age_when_unknown(monkeypatch):
    """直连源没有 Age 头：不能把上一轮的年龄残留下来。"""
    pm._set_fetch_meta(9999)
    monkeypatch.setattr(pm, "FETCH_RACE_ENABLED", True)
    monkeypatch.setattr(
        pm.mirror_race, "race_mirrors",
        lambda candidates, **kw: mr.FetchResult(
            True, mr.FetchCode.OK, "x\n", "direct", (), age_seconds=None),
    )
    text, via_mirror = pm.fetch_proxy_text("https://raw.githubusercontent.com/a/b.txt")
    assert (text, via_mirror) == ("x\n", False)
    assert pm.consume_fetch_meta() is None


def test_fetch_one_carries_age(monkeypatch):
    """_fetch_one 把线程局部的年龄写进源状态记录。"""
    monkeypatch.setattr(pm, "fetch_proxy_text",
                        lambda url: (pm._set_fetch_meta(42), ("x\n", True))[1])
    record = pm.ProxyManager()._fetch_one("http://example.com/list.txt")
    assert record["status"] == "ok"
    assert record["via_mirror"] is True
    assert record["age_seconds"] == 42


# ---------------------------------------------------------------------------
# 新鲜度汇总


def test_summarize_takes_freshest_source():
    fetched = [
        {"status": "ok", "age_seconds": 3405, "via_mirror": True},
        {"status": "ok", "age_seconds": 120, "via_mirror": True},
        {"status": "failed", "age_seconds": None, "via_mirror": False},
    ]
    meta = pm.summarize_fetch_meta(fetched, stale_after=900)
    assert meta["age_seconds"] == 120
    assert meta["stale"] is False
    assert meta["via_mirror_sources"] == 2


def test_summarize_marks_stale_beyond_threshold():
    meta = pm.summarize_fetch_meta(
        [{"status": "ok", "age_seconds": 3600, "via_mirror": True}], stale_after=900)
    assert meta["stale"] is True
    assert meta["age_seconds"] == 3600


def test_summarize_unknown_age_is_not_stale():
    """全部源都读不到 Age：年龄未知，不能判定陈旧，也不能假装新鲜。"""
    meta = pm.summarize_fetch_meta(
        [{"status": "ok", "age_seconds": None, "via_mirror": False}], stale_after=900)
    assert meta["age_seconds"] is None
    assert meta["stale"] is False


def test_summarize_empty_is_safe():
    meta = pm.summarize_fetch_meta([])
    assert meta["age_seconds"] is None
    assert meta["stale"] is False
    assert meta["via_mirror_sources"] == 0


# ---------------------------------------------------------------------------
# rounds.jsonl 的 mtproto 段


def _record(**kwargs):
    base = dict(ok=True, error=None, elapsed_ms=1000, stats={}, tg_stats={},
                stage_durations={})
    base.update(kwargs)
    return round_log.build_record(**base)


def test_record_has_mtproto_section_with_defaults():
    rec = _record()
    assert rec["mtproto"] == {"tested": 0, "ok": 0, "fail": 0, "unavailable": 0}


def test_record_carries_mtproto_counts():
    rec = _record(mtproto_stats={"tested": 18, "ok": 15, "fail": 2, "unavailable": 1})
    assert rec["mtproto"]["tested"] == 18
    assert rec["mtproto"]["unavailable"] == 1


def test_record_backward_compatible_without_mtproto():
    """老调用方（含安卓端写下的记录）不传这一段也能正常生成。"""
    rec = _record(stats={"available": 3})
    assert "mtproto" in rec
    assert rec["stats"]["available"] == 3


if __name__ == "__main__":
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            try:
                fn()
                print("PASS", name)
            except AssertionError as exc:
                print("FAIL", name, exc)
