# -*- coding: utf-8 -*-
"""变异自检工具自身的测试。

工具的结论只有四种，判错任何一种都会让人得出相反的行动：
把 `NOT_CAUGHT` 当成通过 → 覆盖不足被放过；把 `INJECT_FAILED` 当成通过 → 锚点失效没人发现。
所以这里测的不是「工具能跑」，而是**它在这几种边界上判得对不对**。

重点是行尾：`core.autocrlf=true` 的机器检出的是 CRLF，而清单里的多行锚点用 `\\n` 写。
同一个工具在 LF 和 CRLF 工作区必须给出同样的结论。
"""

import importlib.util
import os

import pytest

_HERE = os.path.dirname(os.path.abspath(__file__))
_ROOT = os.path.dirname(_HERE)
_TOOL = os.path.join(_ROOT, "tools", "mutation_check.py")

_spec = importlib.util.spec_from_file_location("mutation_check", _TOOL)
mt = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(mt)


# --------------------------------------------------------------------------
# 锚点定位：行尾容忍
# --------------------------------------------------------------------------


def test_locate_single_line_in_lf():
    anchor, err = mt._locate("a\nb\nc", "b")
    assert anchor == "b" and err is None


def test_locate_multi_line_in_lf():
    anchor, err = mt._locate("x\na\nb\ny", "a\nb")
    assert anchor == "a\nb" and err is None


def test_locate_multi_line_in_crlf():
    """CRLF 工作区：LF 形式的锚点匹配不上，必须自动改用 CRLF 形式。"""
    text = "x\r\na\r\nb\r\ny"
    assert text.count("a\nb") == 0, "前提：LF 锚点在 CRLF 文本里匹配不到"

    anchor, err = mt._locate(text, "a\nb")
    assert err is None
    assert anchor == "a\r\nb"


def test_locate_reports_counts_when_missing():
    anchor, err = mt._locate("abc", "zzz")
    assert anchor is None
    assert "0 次" in err


def test_locate_refuses_ambiguous_mixed_eol():
    """同一锚点以 LF 与 CRLF 各出现一次：不该猜，应该报错。"""
    text = "a\nb" + "|" + "a\r\nb"
    anchor, err = mt._locate(text, "a\nb")
    assert anchor is None
    assert "混合" in err


def test_align_eol_follows_anchor():
    assert mt._align_eol("p\nq", "a\r\nb") == "p\r\nq"
    assert mt._align_eol("p\nq", "a\nb") == "p\nq"
    # 替换文本本身若已含 CRLF，也要先归一化再对齐，不能出现 \r\r\n
    assert mt._align_eol("p\r\nq", "a\r\nb") == "p\r\nq"


# --------------------------------------------------------------------------
# 真实清单：那条唯一的多行锚点在两种工作区下都要能定位
# --------------------------------------------------------------------------


def _multi_line_mutation():
    for m in mt.MUTATIONS:
        if "\n" in m["find"]:
            return m
    pytest.skip("清单里没有多行锚点")


def test_real_multi_line_anchor_matches_in_lf_checkout():
    m = _multi_line_mutation()
    text = open(os.path.join(_ROOT, m["file"]), encoding="utf-8").read()
    normalized = text.replace("\r\n", "\n")
    anchor, err = mt._locate(normalized, m["find"])
    assert anchor is not None, err


def test_real_multi_line_anchor_matches_in_crlf_checkout():
    """用户在 Windows 上遇到的正是这个：工作区被检出成 CRLF。

    修复前这里会返回 INJECT_FAILED，看起来像「代码变了」，
    实际只是行尾不同 —— 而这条恰好是「源改好即宽恕」，会静默少一条覆盖。
    """
    m = _multi_line_mutation()
    text = open(os.path.join(_ROOT, m["file"]), encoding="utf-8").read()
    crlf_text = text.replace("\r\n", "\n").replace("\n", "\r\n")
    assert crlf_text.count(m["find"]) == 0, "前提：LF 锚点在 CRLF 文本里匹配不到"

    anchor, err = mt._locate(crlf_text, m["find"])
    assert anchor is not None, err
    assert anchor == m["find"].replace("\n", "\r\n")


# --------------------------------------------------------------------------
# 端到端：CRLF 文件上真的走完「注入 → 测试红 → 恢复」
# --------------------------------------------------------------------------


def _make_crlf_project(tmp_path):
    original = "HEAD\r\nMUT_TARGET\r\nSECOND_LINE\r\nTAIL\r\n"
    src = tmp_path / "proxy_manager.py"
    src.write_bytes(original.encode("utf-8"))

    tdir = tmp_path / "tests"
    tdir.mkdir()
    (tdir / "test_dummy.py").write_text(
        "import os\n"
        "\n"
        "\n"
        "def test_marker():\n"
        "    here = os.path.dirname(os.path.abspath(__file__))\n"
        "    path = os.path.join(here, '..', 'proxy_manager.py')\n"
        "    with open(path, encoding='utf-8') as fh:\n"
        "        text = fh.read()\n"
        "    assert 'MUT_TARGET' in text\n",
        encoding="utf-8",
    )
    return src, original


def test_end_to_end_crlf_file_is_caught(tmp_path):
    src, original = _make_crlf_project(tmp_path)
    m = {
        "name": "假变异（多行锚点）",
        "file": "proxy_manager.py",
        "find": "MUT_TARGET\nSECOND_LINE",
        "replace": "MUT_CHANGED",
        "tests": ["tests/test_dummy.py::test_marker"],
    }

    status, detail = mt.run_mutation(m, root=str(tmp_path))
    assert status == "CAUGHT", detail
    # 恢复后必须逐字节一致，且仍是 CRLF（不能被顺手改成 LF）
    assert src.read_bytes() == original.encode("utf-8")


def test_end_to_end_reports_not_caught_when_test_does_not_read_the_file(tmp_path):
    """测试压根不读被改的文件时，应判 NOT_CAUGHT 而不是 CAUGHT。

    这正是我第一版探测脚本设计错的地方：以为「打坏 index.html」
    会让一个后端测试变红，其实那个测试根本不读 HTML。
    """
    _make_crlf_project(tmp_path)
    (tmp_path / "tests" / "test_other.py").write_text(
        "def test_unrelated():\n    assert True\n", encoding="utf-8"
    )
    m = {
        "name": "假变异（无人读）",
        "file": "proxy_manager.py",
        "find": "MUT_TARGET\nSECOND_LINE",
        "replace": "MUT_CHANGED",
        "tests": ["tests/test_other.py::test_unrelated"],
    }

    status, detail = mt.run_mutation(m, root=str(tmp_path))
    assert status == "NOT_CAUGHT", detail


def test_end_to_end_restores_file_even_when_tests_error(tmp_path):
    """测试进程报错也要恢复原文件 —— 恢复在 finally 里，不依赖测试结果。"""
    src, original = _make_crlf_project(tmp_path)
    m = {
        "name": "假变异（测试名不存在）",
        "file": "proxy_manager.py",
        "find": "MUT_TARGET\nSECOND_LINE",
        "replace": "MUT_CHANGED",
        "tests": ["tests/test_dummy.py::test_does_not_exist"],
    }

    status, detail = mt.run_mutation(m, root=str(tmp_path))
    assert status == "WRONG_FAILURE", detail
    assert src.read_bytes() == original.encode("utf-8")


if __name__ == "__main__":
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            try:
                fn()
                print("PASS", name)
            except TypeError:
                print("SKIP", name, "(需要参数，请用 pytest 跑)")
            except AssertionError as exc:
                print("FAIL", name, exc)
