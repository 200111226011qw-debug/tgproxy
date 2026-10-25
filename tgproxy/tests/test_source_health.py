# -*- coding: utf-8 -*-
"""源自动体检回归测试（覆盖 2026-10-25 评审各轮发现的核心路径，共 10 个用例）。

既可用 pytest 运行（python -m pytest tgproxy/tests -q），也可直接执行本文件。
"""
import json
import os
import random
import sys
import tempfile

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), os.pardir)))
from proxy_manager import SOURCE_CANDIDATES, SOURCE_SAMPLE_FLOOR, ProxyManager  # noqa: E402


def make_manager(tmpdir, name):
    return ProxyManager(
        known_good_path=os.path.join(tmpdir, "kg_%s.json" % name),
        source_health_path=os.path.join(tmpdir, "sh_%s.json" % name),
        on_sources_changed=lambda urls: None,
    )


def rec(url, status, error=None):
    return {"url": url, "status": status, "kept": 0, "via_mirror": False, "error": error}


def load_health(tmpdir, name):
    with open(os.path.join(tmpdir, "sh_%s.json" % name), encoding="utf-8") as fh:
        return json.load(fh)


def test_park_after_three_consecutive_zero_rounds():
    d = tempfile.mkdtemp()
    m = make_manager(d, "zero3")
    m.set_proxy_list_urls(["https://A/x", "https://B/x"])
    for _ in range(3):
        m._update_source_health(
            [rec("https://A/x", "ok"), rec("https://B/x", "ok")],
            {"https://A/x": 0, "https://B/x": 5})
    health = load_health(d, "zero3")
    assert health["https://A/x"]["parked"] == "连续 3 轮 0 个 TG 可用节点"
    assert not health["https://B/x"]["parked"]
    # 停用后自动从候选池补位
    assert m._proxy_list_urls == ["https://B/x", SOURCE_CANDIDATES[0]]  # 移除 A，候选追加尾部


def test_park_after_three_consecutive_fetch_failures():
    d = tempfile.mkdtemp()
    m = make_manager(d, "fail3")
    m.set_proxy_list_urls(["https://A/x", "https://B/x"])
    for _ in range(3):
        m._update_source_health(
            [rec("https://A/x", "failed", "boom"), rec("https://B/x", "ok")],
            {"https://B/x": 3})
    health = load_health(d, "fail3")
    assert health["https://A/x"]["parked"] == "连续 3 轮抓取失败"


def test_global_zero_ratio_protects_sources():
    d = tempfile.mkdtemp()
    m = make_manager(d, "gz")
    urls = ["https://%s/x" % c for c in "ABCDE"]
    m.set_proxy_list_urls(urls)
    # 5 源中 4 源 0 产出、1 源 1 个节点（占比 80%）-> 环境异常，保护
    for _ in range(4):
        notice = m._update_source_health(
            [rec(u, "ok") for u in urls], {"https://E/x": 1})
    health = load_health(d, "gz")
    assert not any(health[u]["parked"] for u in urls)
    assert "暂停自动换源" in (notice or "")
    # 用户源不被替换
    assert m._proxy_list_urls == urls


def test_single_degraded_source_still_parked():
    d = tempfile.mkdtemp()
    m = make_manager(d, "single")
    m.set_proxy_list_urls(["https://A/x", "https://B/x"])
    for _ in range(3):
        m._update_source_health(
            [rec("https://A/x", "ok"), rec("https://B/x", "ok")],
            {"https://A/x": 5, "https://B/x": 0})
    health = load_health(d, "single")
    assert health["https://B/x"]["parked"]
    assert not health["https://A/x"]["parked"]


def test_known_good_without_src_does_not_crash():
    """P0 回归：known_good 兜底节点无 _src 时，归因跳过而非 KeyError。"""
    d = tempfile.mkdtemp()
    m = make_manager(d, "kg")
    m.set_proxy_list_urls(["https://A/x"])
    m._save_known_good([{"host": "3.3.3.3", "port": 1080, "tg_latency_ms": 400}])
    verified = [
        {"host": "1.1.1.1", "port": 1080, "type": "socks5", "tg_ok": True,
         "tg_latency_ms": 500, "status": "available", "link": "socks5://1.1.1.1:1080",
         "location": "", "isp": "", "added_at": ""},
        {"host": "3.3.3.3", "port": 1080, "type": "socks5", "tg_ok": True,
         "tg_latency_ms": 400, "status": "available", "link": "socks5://3.3.3.3:1080",
         "location": "", "isp": "", "added_at": ""},  # known_good 节点，无 _src
    ]
    per_src = {}
    for item in verified:
        src_url = item.get("_src")  # 修复后的归因保护
        if not src_url:
            continue
        per_src[src_url] = per_src.get(src_url, 0) + 1
    assert per_src == {}  # 两个节点都不属于任何源（A 源本轮未贡献）


def test_manual_readd_forgives_parked_source():
    d = tempfile.mkdtemp()
    m = make_manager(d, "forgive")
    m.set_proxy_list_urls(["https://A/x", "https://B/x"])
    for _ in range(3):
        m._update_source_health(
            [rec("https://A/x", "ok"), rec("https://B/x", "ok")],
            {"https://A/x": 0, "https://B/x": 5})
    health = load_health(d, "forgive")
    assert health["https://A/x"]["parked"]
    # 用户手动把 A 加回（重设列表包含 A）-> 宽恕
    m.set_proxy_list_urls(["https://B/x", "https://A/x"])
    health = load_health(d, "forgive")
    assert not health["https://A/x"]["parked"] and health["https://A/x"]["hist"] == []


def test_set_urls_preserves_history_of_untouched_sources():
    """复核指出的副作用回归：新增源 b 不得清掉在列源 a 的历史。"""
    d = tempfile.mkdtemp()
    m = make_manager(d, "sideeffect")
    m.set_proxy_list_urls(["https://a/x", "https://b/x"])
    for _ in range(2):
        m._update_source_health([rec("https://a/x", "ok"), rec("https://b/x", "ok")],
                                {"https://a/x": 7, "https://b/x": 7})
    m.set_proxy_list_urls(["https://a/x", "https://b/x", "https://c/x"])
    health = load_health(d, "sideeffect")
    assert health["https://a/x"]["hist"] == [7, 7]


def test_sampling_floor_keeps_small_sources():
    random.seed(1)
    merged, seen = [], set()
    for i in range(20):
        for j in range(20):
            e = {"host": "10.0.%d.%d" % (i, j), "port": 1080 + j, "type": "socks5",
                 "latency_ms": None, "status": "unavailable", "link": "", "location": "",
                 "isp": "", "added_at": "", "_src": "https://s%d/x" % i}
            k = (e["host"], e["port"])
            if k not in seen:
                seen.add(k)
                merged.append(e)
    for j in range(30000):
        e = {"host": "99.0.0.%d" % j, "port": 1080, "type": "socks5", "latency_ms": None,
             "status": "unavailable", "link": "", "location": "", "isp": "",
             "added_at": "", "_src": "https://big/x"}
        k = (e["host"], e["port"])
        if k not in seen:
            seen.add(k)
            merged.append(e)
    preferred_keys = set()
    preferred = [e for e in merged if (e["host"], e["port"]) in preferred_keys]
    rest = [e for e in merged if (e["host"], e["port"]) not in preferred_keys]
    by_src = {}
    for entry in rest:
        by_src.setdefault(entry.get("_src"), []).append(entry)
    floor_entries, floor_keys = [], set()
    for _src_url, lst in by_src.items():
        take = lst if len(lst) <= SOURCE_SAMPLE_FLOOR else random.sample(lst, SOURCE_SAMPLE_FLOOR)
        floor_entries.extend(take)
        floor_keys.update((e["host"], e["port"]) for e in take)
    pool = [e for e in rest if (e["host"], e["port"]) not in floor_keys]
    keep_n = max(0, 3000 - len(preferred) - len(floor_entries))
    sampled = preferred + floor_entries + random.sample(pool, min(len(pool), keep_n))
    counts = {}
    for e in sampled:
        counts[e["_src"]] = counts.get(e["_src"], 0) + 1
    for i in range(20):
        assert counts["https://s%d/x" % i] >= SOURCE_SAMPLE_FLOOR


def test_known_good_switch_disabled_skips_read_and_write():
    """开关关闭：known_good.json 不读取、不写入；重新开启后旧文件恢复使用。"""
    d = tempfile.mkdtemp()
    kg = os.path.join(d, "known_good.json")
    m = ProxyManager(known_good_path=kg)  # 默认开启
    m._save_known_good([{"host": "1.1.1.1", "port": 1080, "tg_ok": True,
                         "tg_latency_ms": 500}])
    assert os.path.isfile(kg)
    merged, seen = [], set()
    assert m._merge_known_good(merged, seen)

    m.set_known_good_enabled(False)
    mt_before = os.path.getmtime(kg)
    m._save_known_good([{"host": "2.2.2.2", "port": 1080, "tg_ok": True,
                         "tg_latency_ms": 400}])
    assert os.path.getmtime(kg) == mt_before  # 关闭态不写盘
    merged2, seen2 = [], set()
    assert not m._merge_known_good(merged2, seen2)  # 关闭态不补入

    m.set_known_good_enabled(True)
    merged3, seen3 = [], set()
    m._merge_known_good(merged3, seen3)
    assert len(merged3) == 1 and merged3[0]["host"] == "1.1.1.1"  # 旧文件恢复使用


def test_four_sources_three_zero_protected():
    """P2 回归：4 源 3 零（75%）判环境异常，不换源（v1.7.2 修复场景）。"""
    d = tempfile.mkdtemp()
    m = make_manager(d, "n4")
    urls = ["https://%s/x" % c for c in "ABCD"]
    m.set_proxy_list_urls(urls)  # 必须显式设置，否则 active 是 5 个默认源
    notice = None
    for _ in range(4):
        notice = m._update_source_health(
            [rec(u, "ok") for u in urls], {"https://D/x": 5})
    health = load_health(d, "n4")
    assert not any(health[u]["parked"] for u in urls)
    assert "3/4" in (notice or "")
    assert m._proxy_list_urls == urls


if __name__ == "__main__":
    import inspect
    fns = [(n, f) for n, f in sorted(globals().items())
           if n.startswith("test_") and inspect.isfunction(f)]
    for name, fn in fns:
        fn()
        print("PASS", name)
    print("ALL %d TESTS PASSED" % len(fns))
