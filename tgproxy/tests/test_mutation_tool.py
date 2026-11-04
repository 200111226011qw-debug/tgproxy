# -*- coding: utf-8 -*-
"""变异自检工具自身的测试。

工具的结论只有四种，判错任何一种都会让人得出相反的行动：
把 `NOT_CAUGHT` 当成通过 → 覆盖不足被放过；把 `INCONCLUSIVE` 当成 `CAUGHT` → 假绿；
把 `INCONCLUSIVE` 当成 `WRONG_FAILURE` → 让人去查「为什么红错了地方」，白查一轮。

所以这里测的不是「工具能跑」，而是**它在这几种边界上判得对不对**。

两条主线：

1. **行尾**：`core.autocrlf=true` 的机器检出的是 CRLF，而清单里的多行锚点用 `\\n` 写。
   同一个工具在 LF 和 CRLF 工作区必须给出同样的结论。
2. **判定来源**：只认 junit xml 里的 `<failure>`，不认控制台文本。
   pytest 崩溃 / collection error / 测试名打错时，终端上没有 `FAILED ` 行 ——
   那种情况必须报 `INCONCLUSIVE`，不能猜成「红错了地方」。
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
# junit 解析
# --------------------------------------------------------------------------


_JUNIT = """<?xml version="1.0" encoding="utf-8"?>
<testsuites>
  <testsuite name="pytest" tests="4">
    <testcase classname="tests.a" name="test_ok" time="0.01"/>
    <testcase classname="tests.a" name="test_bad" time="0.01"><failure message="boom">tb</failure></testcase>
    <testcase classname="tests.a" name="test_err" time="0.01"><error message="fixture">tb</error></testcase>
    <testcase classname="tests.a" name="test_skip" time="0.01"><skipped message="no node"/></testcase>
  </testsuite>
</testsuites>
"""


def test_parse_junit_reads_every_outcome(tmp_path):
    path = tmp_path / "r.xml"
    path.write_text(_JUNIT, encoding="utf-8")

    cases = mt._parse_junit(str(path))
    assert ("tests.a", "test_ok", "passed") in cases
    assert ("tests.a", "test_bad", "failure") in cases
    assert ("tests.a", "test_err", "error") in cases
    assert ("tests.a", "test_skip", "skipped") in cases


def test_parse_junit_missing_or_empty_returns_none(tmp_path):
    """pytest 没写完就被打断时，文件要么不存在要么是空文件。"""
    assert mt._parse_junit(str(tmp_path / "nope.xml")) is None

    empty = tmp_path / "empty.xml"
    empty.write_bytes(b"")
    assert mt._parse_junit(str(empty)) is None


def test_classname_conversion_matches_pytest_layout():
    assert mt._to_classname("tests/test_ui_labels.py") == "tests.test_ui_labels"
    assert mt._to_classname("tests\\test_ui_labels.py") == "tests.test_ui_labels"


# --------------------------------------------------------------------------
# 判定：只认目标测试的 failure
# --------------------------------------------------------------------------


def test_judge_failure_on_target_is_caught():
    cases = [("tests.a", "test_target", "failure")]
    status, detail = mt._judge(cases, ["tests/a.py::test_target"], "")
    assert status == "CAUGHT"
    assert "test_target" in detail


def test_judge_target_passes_while_sibling_fails():
    """目标通过、同批别的测试失败 → 仍是 NOT_CAUGHT。

    这是改用 junit 的核心收益：判定只看目标本身，
    不再受「这次运行里还有别的红」影响。
    """
    cases = [
        ("tests.a", "test_target", "passed"),
        ("tests.a", "test_sibling", "failure"),
    ]
    status, _ = mt._judge(cases, ["tests/a.py::test_target"], "")
    assert status == "NOT_CAUGHT"


def test_judge_error_is_inconclusive_not_caught():
    """fixture/collect 层的 error 不算「测试捕获了变异」。"""
    cases = [("tests.a", "test_target", "error")]
    status, detail = mt._judge(cases, ["tests/a.py::test_target"], "")
    assert status == "INCONCLUSIVE"
    assert "error" in detail


def test_judge_whole_file_spec_matches_any_case():
    cases = [("tests.a", "test_one", "passed"), ("tests.a", "test_two", "failure")]
    status, _ = mt._judge(cases, ["tests/a.py"], "")
    assert status == "CAUGHT"


def test_judge_parameterized_name_is_matched_by_prefix():
    cases = [("tests.a", "test_target[case1]", "failure")]
    status, _ = mt._judge(cases, ["tests/a.py::test_target"], "")
    assert status == "CAUGHT"


def test_judge_missing_xml_is_inconclusive():
    """pytest 崩溃、junit 没写出来 —— 这时不能猜结论。"""
    status, detail = mt._judge(None, ["tests/a.py::t"], "INTERNALERROR> boom")
    assert status == "INCONCLUSIVE"
    assert "INTERNALERROR" in detail


def test_judge_target_absent_is_inconclusive():
    cases = [("tests.other", "test_x", "passed")]
    status, detail = mt._judge(cases, ["tests/a.py::test_target"], "")
    assert status == "INCONCLUSIVE"
    assert "没有出现" in detail


@pytest.mark.parametrize("output", [
    # 用户机器上那次误判的实际形状
    'INTERNALERROR> OSError: [safe-delete] SAFE_DELETE_BULK_CONFIRM_REQUIRED {"count":61}',
    # collection error：有 ERROR 行，没有 FAILED 行
    "ERROR tests/test_ui_labels.py - ImportError: boom\n"
    "!!! Interrupted: 1 error during collection !!!",
    # 测试名打错
    "ERROR: not found: tests/a.py::nope\nno tests ran in 0.01s",
])
def test_text_without_failed_lines_never_becomes_wrong_failure(output):
    """这三种终端输出的共同点：都没有 FAILED 行。

    旧实现把它们全报成 WRONG_FAILURE（=「红了但不是目标测试」），
    会让人去查「为什么红错了地方」，而真实情况是「根本没得到结果」。
    """
    status, _ = mt._judge(None, ["tests/a.py::test_target"], output)
    assert status == "INCONCLUSIVE"


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


def _crlf_mutation(**over):
    m = {
        "name": "假变异（多行锚点）",
        "file": "proxy_manager.py",
        "find": "MUT_TARGET\nSECOND_LINE",
        "replace": "MUT_CHANGED",
        "tests": ["tests/test_dummy.py::test_marker"],
    }
    m.update(over)
    return m


def test_end_to_end_crlf_file_is_caught(tmp_path):
    src, original = _make_crlf_project(tmp_path)

    status, detail = mt.run_mutation(_crlf_mutation(), root=str(tmp_path))
    assert status == "CAUGHT", detail
    # 恢复后必须逐字节一致，且仍是 CRLF（不能被顺手改成 LF）
    assert src.read_bytes() == original.encode("utf-8")


def test_end_to_end_reports_not_caught_when_test_does_not_read_the_file(tmp_path):
    """测试压根不读被改的文件时，应判 NOT_CAUGHT 而不是 CAUGHT。"""
    _make_crlf_project(tmp_path)
    (tmp_path / "tests" / "test_other.py").write_text(
        "def test_unrelated():\n    assert True\n", encoding="utf-8"
    )
    m = _crlf_mutation(tests=["tests/test_other.py::test_unrelated"])

    status, detail = mt.run_mutation(m, root=str(tmp_path))
    assert status == "NOT_CAUGHT", detail


def test_end_to_end_unknown_test_name_is_inconclusive(tmp_path):
    """测试名打错 → 目标没跑 → INCONCLUSIVE，而不是「红错了地方」。

    旧版本报 WRONG_FAILURE，会让人去查测试为什么红错，而真实原因是名字写错了。
    """
    src, original = _make_crlf_project(tmp_path)
    m = _crlf_mutation(tests=["tests/test_dummy.py::test_does_not_exist"])

    status, detail = mt.run_mutation(m, root=str(tmp_path))
    assert status == "INCONCLUSIVE", detail
    assert src.read_bytes() == original.encode("utf-8")


# --------------------------------------------------------------------------
# 重试：INCONCLUSIVE 不该直接下结论
# --------------------------------------------------------------------------


def test_inconclusive_is_retried_once(tmp_path):
    """第一次没拿到结果、第二次正常 → 给出结论，并说明重试过。"""
    _make_crlf_project(tmp_path)
    real = mt._evaluate_once
    calls = []

    def flaky(test_paths, root):
        calls.append(1)
        if len(calls) == 1:
            return "INCONCLUSIVE", "模拟崩溃", ""
        return real(test_paths, root)

    mt._evaluate_once = flaky
    try:
        status, detail = mt.run_mutation(_crlf_mutation(), root=str(tmp_path))
    finally:
        mt._evaluate_once = real

    assert status == "CAUGHT", detail
    assert len(calls) == 2
    assert "重试" in detail


def test_persistent_inconclusive_is_reported_not_guessed(tmp_path):
    """重试用尽仍不明确：如实报 INCONCLUSIVE，并且照样恢复原文件。"""
    src, original = _make_crlf_project(tmp_path)
    real = mt._evaluate_once
    mt._evaluate_once = lambda test_paths, root: ("INCONCLUSIVE", "一直崩", "")
    try:
        status, detail = mt.run_mutation(_crlf_mutation(), root=str(tmp_path))
    finally:
        mt._evaluate_once = real

    assert status == "INCONCLUSIVE", detail
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
