# -*- coding: utf-8 -*-
"""侧栏两个标签的文案选择：用 node 跑真断言。

两个纯函数的源码都只有一份（`static/index.html`），用 `// >>> 名字` / `// <<< 名字`
圈出来给这里提取——不复制到测试里，复制必然漂移。

为什么值得测：

- `mtprotoLabel`：`in_pool > 0 且 candidates == 0` 表示「全部死在 TCP 预筛」。
  只说「N 个候选」，用户看到的是一个**合理但错误**的理由。
- `fetchAgeLabel`：合并列表是各源节点的并集，一个新鲜源刷新不了别的源的节点，
  只报最新的一份会掩盖陈旧节点（实测有源 6.2 小时、有源 21 分钟）。

node 不存在时 skip：这是前端断言，不该让纯 Python 环境红。
"""

import json
import os
import re
import shutil
import subprocess
import tempfile

import pytest

_HERE = os.path.dirname(os.path.abspath(__file__))
_ROOT = os.path.dirname(_HERE)
_INDEX = os.path.join(_ROOT, "static", "index.html")

_MARKERS = {
    "mtprotoLabel": ("// >>> mtprotoLabel", "// <<< mtprotoLabel"),
    "fetchAgeLabel": ("// >>> fetchAgeLabel", "// <<< fetchAgeLabel"),
}

# (configured, in_pool, candidates) -> 期望文案
MT_CASES = [
    (False, 0, 0, "未配置"),
    (True, 0, 0, "已配置 · 无候选"),
    (True, 3, 0, "3 个 · 全被预筛淘汰"),
    (True, 5, 2, "5 个 · 进闸门 2 个"),
    (True, 4, 4, "4 个候选"),
]

# (updated, age_seconds, age_seconds_newest, stale, via_mirror_sources) -> 期望文案
FETCH_CASES = [
    (False, None, None, False, 0, "—"),
    (True, None, None, False, 0, "直连"),
    (True, None, None, False, 2, "镜像"),
    (True, 20, 20, False, 0, "刚刚"),
    (True, 20, 10, False, 0, "刚刚"),
    # stale_after_sec 可配到 30 秒以下，此时「陈旧」与「刚刚」会撞车
    (True, 20, 20, True, 0, "⚠ 不到 1 分钟"),
    (True, 40, 40, False, 0, "1 分钟前"),
    # 后端不返回 newest 时必须回落到 oldest（不显示成区间）
    (True, 40, None, False, 0, "1 分钟前"),
    (True, 40, 10, False, 0, "刚刚–1 分钟前"),
    (True, 3420, 3420, True, 0, "⚠ 57 分钟前"),
    # 两个源：最旧 57 分钟、最新 2 分钟 —— 区间才不撒谎
    (True, 3420, 120, True, 2, "⚠ 2–57 分钟前"),
]


def _extract(name):
    begin, end = _MARKERS[name]
    with open(_INDEX, encoding="utf-8") as fh:
        src = fh.read()
    assert begin in src, "index.html 里缺少 %s 标记" % begin
    assert end in src, "index.html 里缺少 %s 标记" % end
    body = src[src.index(begin) + len(begin):src.index(end)]
    assert ("function " + name) in body, "标记之间没有 %s" % name
    return body


def _node():
    node = shutil.which("node")
    if node is None:
        pytest.skip("node 不可用，跳过前端断言")
    return node


def _run_node(script_text, tmp_path, name):
    script = tmp_path / (name + ".mjs")
    script.write_text(script_text, encoding="utf-8")
    proc = subprocess.run(
        [_node(), str(script)], capture_output=True, text=True, timeout=60
    )
    assert proc.returncode == 0, "%s 断言失败：\n%s\n%s" % (
        name, proc.stdout, proc.stderr
    )
    return proc.stdout.strip()


@pytest.mark.parametrize("name", sorted(_MARKERS))
def test_label_function_does_not_touch_dom(name):
    """纯函数才能被 node 直接跑；引入 DOM 查询就失去可测性。"""
    body = _extract(name)
    for forbidden in ("$(", "document", "window."):
        assert forbidden not in body, "%s 里出现 %r，不再是纯函数" % (name, forbidden)


def test_mtproto_label_matrix(tmp_path):
    cases = json.dumps(
        [{"configured": c, "inPool": p, "candidates": k, "text": t}
         for c, p, k, t in MT_CASES],
        ensure_ascii=False,
    )
    harness = _extract("mtprotoLabel") + """
const cases = %s;
const bad = [];
for (const c of cases) {
  const got = mtprotoLabel(c.configured, c.inPool, c.candidates);
  if (!got || got.text !== c.text) bad.push(c.text + " => " + (got && got.text));
  else if (!got.title) bad.push(c.text + " (title 为空)");
}
if (bad.length) { console.log("FAIL " + bad.join(" | ")); process.exit(1); }
console.log("OK " + cases.length);
""" % cases

    out = _run_node(harness, tmp_path, "mtproto_label")
    assert out == "OK %d" % len(MT_CASES)


def test_fetch_age_label_matrix(tmp_path):
    cases = json.dumps(
        [{"updated": u, "age": a, "newest": n, "stale": s, "mirror": m, "text": t}
         for u, a, n, s, m, t in FETCH_CASES],
        ensure_ascii=False,
    )
    harness = _extract("fetchAgeLabel") + """
const cases = %s;
const bad = [];
for (const c of cases) {
  const got = fetchAgeLabel(c.updated, {
    age_seconds: c.age,
    age_seconds_newest: c.newest,
    stale: c.stale,
    via_mirror_sources: c.mirror
  });
  if (!got || got.text !== c.text) { bad.push(c.text + " => " + (got && got.text)); continue; }
  if (c.text === "—" ? got.title !== "" : !got.title) bad.push(c.text + " (title)");
  // ⚠ 必须严格跟随 stale：多一个或少一个都是撒谎。
  // 年龄未知时无从展示，跳过（后端在这种情况下不会置 stale）。
  if (c.age !== null && c.stale !== got.text.startsWith("\\u26a0 ")) {
    bad.push(c.text + " (\\u26a0 与 stale=" + c.stale + " 不一致)");
  }
}
if (bad.length) { console.log("FAIL " + bad.join(" | ")); process.exit(1); }
console.log("OK " + cases.length);
""" % cases

    out = _run_node(harness, tmp_path, "fetch_age_label")
    assert out == "OK %d" % len(FETCH_CASES)


def test_inline_scripts_parse():
    """整块内联脚本能被 node 解析。

    UI 没有任何浏览器级测试，一个拼写错误就能让整个页面白屏；这条至少保证
    「语法没坏」。它的收益不在覆盖率，在把「发布后才发现」降级成「提交前就红」。
    """
    node = _node()
    with open(_INDEX, encoding="utf-8") as fh:
        src = fh.read()
    blocks = re.findall(r"<script(?![^>]*\bsrc=)[^>]*>(.*?)</script>", src, re.S)
    assert blocks, "index.html 里没有内联脚本"

    script = os.path.join(tempfile.mkdtemp(), "page.mjs")
    with open(script, "w", encoding="utf-8") as fh:
        fh.write("\n".join(blocks))

    proc = subprocess.run([node, "--check", script], capture_output=True, text=True, timeout=60)
    assert proc.returncode == 0, "index.html 内联脚本语法错误：\n%s" % proc.stderr[:800]


if __name__ == "__main__":
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            try:
                fn()
                print("PASS", name)
            except TypeError:
                # 参数化 / 需要 tmp_path 的用例，直接跑会缺参；用 pytest
                print("SKIP", name, "(需要参数，请用 pytest 跑)")
            except AssertionError as exc:
                print("FAIL", name, exc)
