# -*- coding: utf-8 -*-
"""变异自检：确认关键接线真的被测试守着。

做法：把正确的实现改成错的，然后期望**指定测试变红**。

四种结果：

    CAUGHT         目标测试确实以 failure 失败              → 通过
    NOT_CAUGHT     目标测试确实通过                          → 覆盖不足，必须修
    INCONCLUSIVE   没拿到可信结果（没跑/error/xml 读不到）    → 重试，仍不行就人工查
    INJECT_FAILED  锚点不匹配                                → 代码变了，变异列表要更新

## 为什么不解析控制台文本

早期版本靠 `ln.startswith("FAILED ")` 判断。实测会**偶发误判**：
pytest 崩溃（INTERNALERROR）、collection error、被外部钩子打断时都不会打印 FAILED 行，
于是「根本没跑起来」被读成「红了但不是目标测试」。

三种情况当时都报成 WRONG_FAILURE：

    INTERNALERROR> OSError: [safe-delete] ...   → 误判
    ERROR tests/... - ImportError                → 误判
    ERROR: not found: tests/a.py::nope           → 误判

现在改用 `--junitxml`：判定只看 XML 里 `<testcase name=...>` 有没有 `<failure>`，
与终端格式、输出污染、并发打印都无关。目标测试没出现在 XML 里 → INCONCLUSIVE。

**结论必须来自结构，不能来自字面。**

## 行尾（这个坑栽过三次）

同一份文件在不同机器上的**工作区行尾可能不同**：`core.autocrlf=true` 时
git 检出会把 LF 转成 CRLF，而清单里的多行锚点是用 `\\n` 写的。直接 count 会得到 0，
被报成 INJECT_FAILED —— 看起来像「代码变了」，实际只是行尾不同。

所以 `_locate()` 会同时尝试 LF 与 CRLF 两种形式的锚点，**选实际匹配的那一种**，
并把替换文本的行尾同步过去。不做全文件归一化：那会把混合行尾的文件
在变异期间统一掉，测试看到的内容和平时不一样。

仓库里任何做文本匹配的工具/脚本/测试，都该按这个方式处理行尾。

## 其它约束

不叫 test_ 前缀，所以 pytest 不会收集它：它会改源文件，不能进常规测试。
每项变异跑完立即用**字节**恢复并校验 SHA-256，任何一次恢复失败都立刻中止。

用法：

    python tools/mutation_check.py           # 跑全部
    python tools/mutation_check.py -v        # 附带 pytest 摘要
    python tools/mutation_check.py --list    # 只列清单，不动文件
"""

import hashlib
import os
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

MUTATIONS = [
    # ---------------- 前端：新鲜度标签 ----------------
    {
        "name": "新鲜度阈值 60 → 120（该换小时时不换）",
        "file": "static/index.html",
        "find": "var AGE_HOUR_MIN = 60;",
        "replace": "var AGE_HOUR_MIN = 120;",
        "tests": ["tests/test_ui_labels.py::test_fetch_age_label_matrix"],
    },
    {
        "name": "去掉 stale 的 ⚠ 前缀（陈旧看不出来）",
        "file": "static/index.html",
        "find": 'text: (fm.stale ? "⚠ " : "") + text,',
        "replace": "text: text,",
        "tests": ["tests/test_ui_labels.py::test_fetch_age_label_matrix"],
    },
    {
        "name": "newest 缺省时不回退 oldest（区间一端丢失）",
        "file": "static/index.html",
        "find": "? oldest : fm.age_seconds_newest;",
        "replace": "? fm.age_seconds_newest : fm.age_seconds_newest;",
        "tests": ["tests/test_ui_labels.py::test_fetch_age_label_matrix"],
    },
    # ---------------- 前端：MTProto 标签 ----------------
    {
        "name": "「全被预筛淘汰」退化成「N 个候选」",
        "file": "static/index.html",
        "find": 'text: pool + " 个 · 全被预筛淘汰",',
        "replace": 'text: pool + " 个候选",',
        "tests": ["tests/test_ui_labels.py::test_mtproto_label_matrix"],
    },
    # ---------------- 后端：MTProto 接线 ----------------
    {
        # 唯一一条多行锚点：CRLF 工作区下必须靠 _locate 的双形式匹配
        "name": "MTProto 源不再「改好即宽恕」",
        "file": "proxy_manager.py",
        "find": ("            self._mtproto_list_urls = new_urls\n"
                 "            self._forgive_added_urls(old_urls, new_urls)"),
        "replace": "            self._mtproto_list_urls = new_urls",
        "tests": ["tests/test_mtproto_source.py"],
    },
    {
        "name": "in_pool 忽略传入值（无法区分「没候选」与「全被刷掉」）",
        "file": "proxy_manager.py",
        "find": '"in_pool": int(len(mtproto_indices) if in_pool is None else in_pool),',
        "replace": '"in_pool": int(len(mtproto_indices)),',
        "tests": ["tests/test_mtproto_wiring.py"],
    },
    {
        "name": "普通源截断时连 MTProto 源一起切掉",
        "file": "proxy_manager.py",
        "find": "urls = list(self._proxy_list_urls)[:FETCH_MAX_SOURCES]",
        "replace": "urls = list(self._proxy_list_urls)",
        "tests": ["tests/test_mtproto_source.py"],
    },
    {
        "name": "PUT /api/settings 不传 mtproto_list_urls（写了不生效）",
        "file": "app.py",
        "find": "mtproto_list_urls=mt_urls_body,",
        "replace": "mtproto_list_urls=None,",
        "tests": ["tests/test_mtproto_source.py"],
    },
    # ---------------- 脚本自检 ----------------
    # 这条改的是注释，不该有任何测试为它变红。
    # 如果脚本把它报成 CAUGHT，说明判定逻辑在瞎报。
    {
        "name": "[自检] 改一句注释（应当无人察觉）",
        "file": "static/index.html",
        "find": "// tooltip 始终用分钟：精确值不该因为显示层换单位而丢掉",
        "replace": "// tooltip 始终用分钟",
        "tests": ["tests/test_ui_labels.py"],
        "expect": "NOT_CAUGHT",
    },
]


def _sha(data):
    return hashlib.sha256(data).hexdigest()


# ---------------------------------------------------------------------------
# 锚点定位：容忍 CRLF 工作区
# ---------------------------------------------------------------------------


def _locate(text, find):
    """在 text 里定位锚点，容忍 CRLF 工作区。

    返回 (anchor, err)：
      anchor 是**实际出现在 text 里**的那一种形式（可能是 CRLF 版），
      调用方必须用它替换，并把 replace 的行尾同步成它的行尾。
    """
    candidates = [find]
    if "\n" in find:
        crlf = find.replace("\n", "\r\n")
        if crlf != find:
            candidates.append(crlf)

    unique = [c for c in candidates if text.count(c) == 1]
    if len(unique) == 1:
        return unique[0], None
    if len(unique) > 1:
        return None, ("锚点同时以 LF 与 CRLF 两种形式各出现 1 次，无法确定目标"
                      "（文件可能是混合行尾）")

    counts = " / ".join(str(text.count(c)) for c in candidates)
    return None, "锚点出现 %s 次（应为 1）：%r" % (counts, find[:60])


def _align_eol(s, anchor):
    """把替换文本的行尾对齐到锚点实际使用的行尾。"""
    eol = "\r\n" if "\r\n" in anchor else "\n"
    if eol == "\n":
        return s
    return s.replace("\r\n", "\n").replace("\n", "\r\n")


# ---------------------------------------------------------------------------
# 结果判定：只看 junit xml，不看控制台文本
# ---------------------------------------------------------------------------


def _to_classname(path_part):
    """`tests/test_a.py` → `tests.test_a`（pytest 在 xml 里就是这么写的）。"""
    p = path_part.replace("\\", "/")
    if p.endswith(".py"):
        p = p[:-3]
    return p.replace("/", ".")


def _parse_junit(xml_path):
    """返回 [(classname, name, outcome)]，outcome ∈ failure/error/skipped/passed。

    xml 不存在或解析失败返回 None —— 那意味着 pytest 没走到写报告这一步。
    """
    try:
        tree = ET.parse(xml_path)
    except (OSError, ET.ParseError):
        return None

    cases = []
    for tc in tree.iter("testcase"):
        outcome = "passed"
        for child in tc:
            if child.tag in ("failure", "error"):
                outcome = child.tag
                break
            if child.tag == "skipped":
                outcome = "skipped"
        cases.append((tc.get("classname", ""), tc.get("name", ""), outcome))
    return cases


def _matches(classname, name, spec):
    """把 junit 里的 testcase 对到清单里的 node id。"""
    if "::" in spec:
        file_part, name_part = spec.split("::", 1)
        want_name = name_part.split("[")[0]
        want_cls = _to_classname(file_part)
        short_name = name.split("[")[0]
        return short_name == want_name and (
            classname == want_cls or classname.endswith("." + want_cls)
        )
    want_cls = _to_classname(spec)
    return classname == want_cls or classname.endswith("." + want_cls)


def _tail(text, n=3):
    lines = [ln for ln in (text or "").splitlines() if ln.strip()]
    return " / ".join(lines[-n:])[:200]


def _judge(cases, test_paths, output):
    """纯判定函数：输入 junit 结果与原始输出，输出 (status, detail)。

    CAUGHT 只认 `failure`（断言失败）。
    `error` 是 fixture/collect 层的问题，不算「测试捕获了变异」，报 INCONCLUSIVE。
    """
    if cases is None:
        return "INCONCLUSIVE", "junit xml 读不到（pytest 可能提前退出）：" + _tail(output)

    matched = [
        (cls, nm, oc) for cls, nm, oc in cases
        if any(_matches(cls, nm, spec) for spec in test_paths)
    ]
    if not matched:
        return "INCONCLUSIVE", (
            "目标测试没有出现在结果里（收集错误/被中断/名字打错）：" + _tail(output)
        )

    failures = [c for c in matched if c[2] == "failure"]
    if failures:
        cls, nm, _ = failures[0]
        return "CAUGHT", "%s::%s 失败" % (cls, nm)

    errors = [c for c in matched if c[2] == "error"]
    if errors:
        cls, nm, _ = errors[0]
        return "INCONCLUSIVE", "%s::%s 是 error 而非 failure（fixture/环境问题）" % (cls, nm)

    return "NOT_CAUGHT", ""


def _evaluate_once(test_paths, root):
    """跑一次 pytest，用 junit xml 判定。返回 (status, detail, output)。"""
    fd, xml_path = tempfile.mkstemp(prefix="mutjunit-", suffix=".xml")
    os.close(fd)
    try:
        cmd = [sys.executable, "-m", "pytest",
               "-p", "no:cacheprovider",
               "--junitxml", xml_path,
               "-q", "--tb=no", *test_paths]
        proc = subprocess.run(cmd, cwd=root, capture_output=True, text=True,
                              encoding="utf-8", errors="replace")
        output = (proc.stdout or "") + (proc.stderr or "")
        status, detail = _judge(_parse_junit(xml_path), test_paths, output)
        return status, detail, output
    finally:
        try:
            os.unlink(xml_path)
        except OSError:
            pass


def run_mutation(m, verbose=False, root=None, retries=1):
    """注入一项变异，跑指定测试，无论结果如何都恢复原文件。

    INCONCLUSIVE 会重试（默认 1 次）：这类结果来自执行环境的偶发干扰
    （外部钩子打断、临时目录清理失败），重试一次通常就正常。
    仍不明确就如实报出来，而不是猜一个结论。

    root 可覆盖项目根（测试用临时目录时可传），默认是本文件上一级。
    """
    root = root or ROOT
    path = os.path.join(root, m["file"])
    if not os.path.isfile(path):
        return "INCONCLUSIVE", "文件不存在：%s" % m["file"]

    original = open(path, "rb").read()
    digest = _sha(original)
    text = original.decode("utf-8")

    anchor, err = _locate(text, m["find"])
    if anchor is None:
        return "INJECT_FAILED", err

    replacement = _align_eol(m["replace"], anchor)

    try:
        mutated = text.replace(anchor, replacement, 1)
        if mutated == text:
            return "INJECT_FAILED", "替换后内容未变"
        with open(path, "w", encoding="utf-8", newline="") as fh:
            fh.write(mutated)

        status, detail, output = "INCONCLUSIVE", "", ""
        notes = ""
        for attempt in range(retries + 1):
            status, detail, output = _evaluate_once(m["tests"], root)
            if status != "INCONCLUSIVE":
                break
            if attempt < retries:
                # 不能改写 detail：下一轮会被 _evaluate_once 的返回值覆盖
                notes += "第 %d 次 INCONCLUSIVE（%s），已重试；" % (attempt + 1, detail[:80])
        if notes:
            detail = notes + detail

        if verbose:
            summary = " / ".join(
                ln for ln in output.splitlines() if "passed" in ln or "failed" in ln
            )
            detail = detail + " || " + summary
        return status, detail
    finally:
        with open(path, "wb") as fh:
            fh.write(original)
        if _sha(open(path, "rb").read()) != digest:
            raise SystemExit(
                "!! 恢复失败：%s 的 SHA-256 与原始不一致，已中止。"
                "请用 git checkout -- %s 恢复后再跑。" % (m["file"], m["file"])
            )


def main(argv):
    verbose = "-v" in argv
    if "--list" in argv:
        for i, m in enumerate(MUTATIONS, 1):
            multi = "多行锚点" if "\n" in m["find"] else ""
            print("%2d  %-56s %-20s %s" % (i, m["name"], m["file"], multi))
        return 0

    print("共 %d 项变异（默认期望 CAUGHT，自检项期望 NOT_CAUGHT）\n" % len(MUTATIONS))

    tally = {"CAUGHT": 0, "NOT_CAUGHT": 0, "INCONCLUSIVE": 0, "INJECT_FAILED": 0}
    bad = []

    for i, m in enumerate(MUTATIONS, 1):
        expect = m.get("expect", "CAUGHT")
        status, detail = run_mutation(m, verbose=verbose)
        ok = status == expect
        tally[status] = tally.get(status, 0) + 1
        print("%s [%2d/%d] %s" % ("OK  " if ok else "FAIL", i, len(MUTATIONS), m["name"]))
        print("         %s（期望 %s）%s" % (
            status, expect, ("— " + detail[:160]) if detail else ""))
        if not ok:
            bad.append((m["name"], expect, status, detail))

    print("\n汇总：" + "  ".join("%s=%d" % (k, v) for k, v in tally.items() if v))
    if bad:
        print("\n以下变异的结果与期望不符：")
        for name, expect, got, detail in bad:
            print("  - %s：期望 %s，实际 %s %s" % (name, expect, got, detail[:200]))
        print("\nNOT_CAUGHT 说明测试没守住这条接线；"
              "INJECT_FAILED 说明锚点已过期（或行尾不一致）；"
              "INCONCLUSIVE 说明这次运行没拿到可信结果，需人工看一眼那个测试。")
        return 1

    print("\n全部符合期望。")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
