# -*- coding: utf-8 -*-
"""变异自检：确认关键接线真的被测试守着。

做法：把正确的实现改成错的，然后期望**指定测试变红**。

四种结果：

    CAUGHT         注入成功 + 目标测试出现在 FAILED 行   → 通过
    NOT_CAUGHT     注入成功 + 测试仍然全绿               → 覆盖不足，必须修
    INJECT_FAILED  锚点不匹配                            → 代码变了，变异列表要更新
    WRONG_FAILURE  测试红了，但不是目标测试              → 多半是语法被打坏，误判成通过

后两种单独区分很重要：锚点失配时若当成「没抓到」，会误判成覆盖不足；
若当成「通过」，就是静默假绿 —— 而静默假绿正是这个脚本存在的理由。

## 行尾（这个坑栽过三次）

同一份文件在不同机器上的**工作区行尾可能不同**：`core.autocrlf=true` 时
git 检出会把 LF 转成 CRLF，而清单里的多行锚点是用 `\n` 写的。直接 count 会得到 0，
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
    python tools/mutation_check.py -v        # 附带每个变异的测试输出摘要
    python tools/mutation_check.py --list    # 只列清单，不动文件
"""

import hashlib
import os
import subprocess
import sys

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


def _run_tests(paths, root):
    cmd = [sys.executable, "-m", "pytest", "--no-header", "-q", "--tb=no", *paths]
    proc = subprocess.run(cmd, cwd=root, capture_output=True, text=True,
                          encoding="utf-8", errors="replace")
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


def _judge(returncode, output, test_paths):
    """把 pytest 的结果判成 CAUGHT / NOT_CAUGHT / WRONG_FAILURE。

    要求「目标测试」真的出现在 FAILED 行里 —— 只看 returncode 会把
    「变异把文件改成语法错误、全部收集失败」也当成捕获成功。
    """
    if returncode == 0:
        return "NOT_CAUGHT", ""

    failed_lines = [ln for ln in output.splitlines() if ln.startswith("FAILED ")]
    targets = []
    for path in test_paths:
        # 参数化的 node id 是 "文件::测试名[参数]"，取测试名首段比对
        name = path.split("::")[-1].split("[")[0]
        targets.append(name)

    hit = [ln for ln in failed_lines if any(t in ln for t in targets)]
    if hit:
        return "CAUGHT", hit[0]

    if failed_lines:
        return "WRONG_FAILURE", failed_lines[0]
    tail = output.strip().splitlines()[-1:] or [""]
    return "WRONG_FAILURE", tail[0]


def run_mutation(m, verbose=False, root=None):
    """注入一项变异，跑指定测试，无论结果如何都恢复原文件。

    root 可覆盖项目根（测试用临时目录时可传），默认是本文件上一级。
    """
    root = root or ROOT
    path = os.path.join(root, m["file"])
    if not os.path.isfile(path):
        return "INJECT_FAILED", "文件不存在：%s" % m["file"]

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

        rc, out = _run_tests(m["tests"], root)
        status, detail = _judge(rc, out, m["tests"])
        if verbose:
            summary = " / ".join(
                ln for ln in out.splitlines() if "passed" in ln or "failed" in ln
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

    tally = {"CAUGHT": 0, "NOT_CAUGHT": 0, "INJECT_FAILED": 0, "WRONG_FAILURE": 0}
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
        print("\nNOT_CAUGHT 说明测试没守住这条接线；INJECT_FAILED 说明锚点已过期"
              "（或行尾不一致），需按当前代码更新 MUTATIONS。")
        return 1

    print("\n全部符合期望。")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
