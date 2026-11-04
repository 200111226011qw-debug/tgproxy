# -*- coding: utf-8 -*-
"""侧栏「MTProto 源」标签：用 node 跑真断言。

文案选择逻辑抽成了纯函数 `mtprotoLabel(configured, inPool, candidates)`，
源码只有一份（`static/index.html`），用 `// >>> mtprotoLabel` 与
`// <<< mtprotoLabel` 圈出来给这里提取——不复制一份到测试里，避免漂移。

为什么值得单独测：`in_pool > 0 且 candidates == 0` 的意思是「全部死在 TCP 预筛」，
如果标签只说「N 个候选」，用户看到的是一个**合理但错误**的理由。这一整轮改动
就是在消灭这种形状，标签是最后一块没被覆盖的地方。

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

_BEGIN = "// >>> mtprotoLabel"
_END = "// <<< mtprotoLabel"

# (configured, in_pool, candidates) -> 期望文案
CASES = [
    (False, 0, 0, "未配置"),
    (True, 0, 0, "已配置 · 无候选"),
    (True, 3, 0, "3 个 · 全被预筛淘汰"),
    (True, 5, 2, "5 个 · 进闸门 2 个"),
    (True, 4, 4, "4 个候选"),
]


def _extract_function():
    with open(_INDEX, encoding="utf-8") as fh:
        src = fh.read()
    assert _BEGIN in src, "index.html 里缺少 %s 标记" % _BEGIN
    assert _END in src, "index.html 里缺少 %s 标记" % _END
    body = src[src.index(_BEGIN) + len(_BEGIN):src.index(_END)]
    assert "function mtprotoLabel" in body, "标记之间没有 mtprotoLabel"
    return body


def test_label_function_does_not_touch_dom():
    """纯函数才能被 node 直接跑；引入 $() 或 document 就失去可测性。"""
    body = _extract_function()
    for forbidden in ("$(", "document", "window."):
        assert forbidden not in body, "mtprotoLabel 里出现 %r，不再是纯函数" % forbidden


def test_label_matrix(tmp_path):
    node = shutil.which("node")
    if node is None:
        pytest.skip("node 不可用，跳过前端断言")

    cases_json = json.dumps(
        [{"configured": c, "inPool": p, "candidates": k, "text": t}
         for c, p, k, t in CASES],
        ensure_ascii=False,
    )
    harness = _extract_function() + """
const cases = %s;
const bad = [];
for (const c of cases) {
  const got = mtprotoLabel(c.configured, c.inPool, c.candidates);
  if (!got || got.text !== c.text) bad.push(c.text);
  else if (!got.title) bad.push(c.text + " (title 为空)");
}
if (bad.length) { console.log("FAIL " + bad.length); process.exit(1); }
console.log("OK " + cases.length);
""" % cases_json

    script = tmp_path / "label_check.mjs"
    script.write_text(harness, encoding="utf-8")

    proc = subprocess.run([node, str(script)], capture_output=True, text=True, timeout=60)
    assert proc.returncode == 0, "标签矩阵不匹配：\n%s\n%s" % (proc.stdout, proc.stderr)
    assert proc.stdout.strip() == "OK %d" % len(CASES)


def test_inline_scripts_parse():
    """整块内联脚本能被 node 解析。

    UI 没有任何浏览器级测试，一个拼写错误就能让整个页面白屏；这条至少保证
    「语法没坏」，比什么都不守住强（它拦不住逻辑错，但能拦住打不开）。
    """
    node = shutil.which("node")
    if node is None:
        pytest.skip("node 不可用，跳过前端断言")

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
            except AssertionError as exc:
                print("FAIL", name, exc)
