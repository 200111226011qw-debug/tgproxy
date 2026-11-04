#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""安卓端变异自检：证明「MTProto 双通道」这组测试真的会红。

为什么需要它：测试全绿只说明「当前实现下测试通过」，不说明「实现错了测试会红」。
常见的假绿有三种，这个脚本各有一条防线：

1. 变异没注入进去（锚点早已改过）→ 断言 `count == 1`，否则 INJECT_FAILED；
2. 注入成功但用例没覆盖 → 判 NOT_CAUGHT（覆盖不足，不是通过）；
3. 变异把代码打成编译错误 → 编译失败不等于「测试捕获」，判 INCONCLUSIVE。

每条变异跑完都用**字节**恢复并校验 SHA-256，不一致立刻中止（在脏文件上继续跑，
后面的结论没有意义）。

用法（路径按需覆盖）：
    python tools/mutation_check_android.py
    GRADLE_BIN=C:/btg/gradle/gradle-8.9/bin/gradle.bat python tools/mutation_check_android.py
"""

from __future__ import annotations

import hashlib
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAVA_HOME = os.environ.get("JAVA_HOME", r"C:\btg\jdk\jdk-17.0.20.1+1")
ANDROID_HOME = os.environ.get("ANDROID_HOME", r"C:\btg\sdk")
GRADLE_USER_HOME = os.environ.get("GRADLE_USER_HOME", r"C:\btg\gradle-home")
GRADLE_BIN = os.environ.get("GRADLE_BIN", r"C:\btg\gradle\gradle-8.9\bin\gradle.bat")
INIT_SCRIPT = os.environ.get("GRADLE_INIT_SCRIPT", r"C:\btg\mirror.init.gradle")
# 工程路径必须是纯 ASCII：JDK 17 按 GBK 解 @argfile，中文路径会让 test worker 起不来
PROJECT_DIR = os.environ.get("PROJECT_DIR", r"C:\btg\tgproxy-live")
RESULTS_DIR = Path(PROJECT_DIR) / "app" / "build" / "test-results" / "testDebugUnitTest"


@dataclass(frozen=True)
class Mutation:
    name: str
    path: str
    old: str
    new: str
    test_class: str


MUTATIONS = [
    Mutation(
        "dec_iv 反向切片改成顺序切片",
        "app/src/main/java/com/tgproxy/data/MtProtoHandshake.kt",
        "val decIv = reversed(init, 23, 8)",
        "val decIv = init.copyOfRange(8, 24)",
        "com.tgproxy.MtProtoHandshakeTest",
    ),
    Mutation(
        "握手头末 8 字节改成明文发送",
        "app/src/main/java/com/tgproxy/data/MtProtoHandshake.kt",
        "for (i in 56 until INIT_LEN) wire[i] = stream[i]",
        "for (i in 56 until INIT_LEN) wire[i] = init[i]",
        "com.tgproxy.MtProtoHandshakeTest",
    ),
    Mutation(
        "resPQ 不再校验 nonce 回显",
        "app/src/main/java/com/tgproxy/data/MtProtoHandshake.kt",
        "if (!echoed.contentEquals(expectedNonce)) {",
        "if (false && !echoed.contentEquals(expectedNonce)) {",
        "com.tgproxy.MtProtoHandshakeTest",
    ),
    Mutation(
        "dd secret 少取一字节（错位）",
        "app/src/main/java/com/tgproxy/data/MtProtoHandshake.kt",
        "return hex.copyOfRange(1, 17)",
        "return hex.copyOfRange(0, 16)",
        "com.tgproxy.MtProtoHandshakeTest",
    ),
    Mutation(
        "握手成功也记失败",
        "app/src/main/java/com/tgproxy/data/ProxyRepository.kt",
        "result.ok -> TgVerdict.PASS",
        "result.ok -> TgVerdict.FAIL",
        "com.tgproxy.ProxyRepositoryMtProtoTest",
    ),
    Mutation(
        "无法判定被记成失败",
        "app/src/main/java/com/tgproxy/data/ProxyRepository.kt",
        "TgVerdict.UNVERIFIED\n            else -> TgVerdict.FAIL",
        "TgVerdict.FAIL\n            else -> TgVerdict.FAIL",
        "com.tgproxy.ProxyRepositoryMtProtoTest",
    ),
    Mutation(
        "解析器不把 secret 放进条目（退回双解析）",
        "app/src/main/java/com/tgproxy/data/ProxyParser.kt",
        "secret = parsed.secret,",
        "secret = null,",
        "com.tgproxy.ProxyParserTest",
    ),
    Mutation(
        "去 min_bytes（空体当成功）",
        "app/src/main/java/com/tgproxy/data/ProxyFetcher.kt",
        "if (bytes.size < minBytes) {",
        "if (false && bytes.size < minBytes) {",
        "com.tgproxy.ProxyFetcherTest",
    ),
    Mutation(
        "去 validator（纯注释当成功）",
        "app/src/main/java/com/tgproxy/data/ProxyFetcher.kt",
        "if (!hasUsableLine(text)) {",
        "if (false && !hasUsableLine(text)) {",
        "com.tgproxy.ProxyFetcherTest",
    ),
    Mutation(
        "改成顺序（只试直连，镜像不再兜底）",
        "app/src/main/java/com/tgproxy/data/MirrorRace.kt",
        "val jobs = candidates.mapIndexed { index, candidate ->",
        "val jobs = candidates.take(1).mapIndexed { index, candidate ->",
        "com.tgproxy.MirrorRaceTest",
    ),
    Mutation(
        "去 stagger（错峰失效）",
        "app/src/main/java/com/tgproxy/data/MirrorRace.kt",
        "if (index > 0 && staggerMs > 0) delay(index * staggerMs)",
        "if (false) delay(index * staggerMs)",
        "com.tgproxy.MirrorRaceTest",
    ),
]


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run_tests(test_class: str) -> tuple[bool, str]:
    """返回 (是否编译通过, 输出)。"""
    env = dict(os.environ)
    env.update(
        JAVA_HOME=JAVA_HOME,
        ANDROID_HOME=ANDROID_HOME,
        GRADLE_USER_HOME=GRADLE_USER_HOME,
    )
    cmd = [
        GRADLE_BIN,
        "-p", PROJECT_DIR,
        "--no-daemon", "--console=plain",
        "--init-script", INIT_SCRIPT,
        ":app:testDebugUnitTest",
        "--tests", test_class,
    ]
    proc = subprocess.run(cmd, env=env, capture_output=True, text=True, errors="replace")
    output = (proc.stdout or "") + (proc.stderr or "")
    compiled = "compileDebugUnitTestKotlin FAILED" not in output and "e: file" not in output
    return compiled, output


def targeted_failures(test_class: str) -> int:
    xml = RESULTS_DIR / f"TEST-{test_class}.xml"
    if not xml.exists():
        return -1
    root = ET.parse(xml).getroot()
    count = 0
    for case in root.iter("testcase"):
        if list(case.iter("failure")) or list(case.iter("error")):
            count += 1
    return count


def main() -> int:
    rows = []
    for item in MUTATIONS:
        path = ROOT / item.path
        original = path.read_bytes()
        before = hashlib.sha256(original).hexdigest()
        # 锚点要同时兼容 LF / CRLF：Windows 工作区（core.autocrlf=true）里是 CRLF，
        # 只写 LF 的锚点会「一条都匹配不到」，看起来像清单过期。
        pairs = [
            (item.old.encode("utf-8"), item.new.encode("utf-8")),
            (item.old.replace("\n", "\r\n").encode("utf-8"),
             item.new.replace("\n", "\r\n").encode("utf-8")),
        ]
        chosen = next(((o, n) for o, n in pairs if original.count(o) == 1), None)
        if chosen is None:
            hits = max(original.count(o) for o, _ in pairs)
            rows.append((item.name, f"INJECT_FAILED（锚点出现 {hits} 次）"))
            continue
        old, new = chosen

        try:
            path.write_bytes(original.replace(old, new, 1))
            compiled, output = run_tests(item.test_class)
            if not compiled:
                rows.append((item.name, "INCONCLUSIVE（变异导致编译失败）"))
            else:
                failed = targeted_failures(item.test_class)
                if failed < 0:
                    rows.append((item.name, "INCONCLUSIVE（没有测试结果文件）"))
                elif failed == 0:
                    rows.append((item.name, "NOT_CAUGHT（测试全绿 → 覆盖不足）"))
                else:
                    rows.append((item.name, f"CAUGHT（{failed} 条红）"))
        finally:
            path.write_bytes(original)
            if sha256(path) != before:
                print(f"!! 恢复失败，字节不一致：{item.path}", file=sys.stderr)
                return 2

    print("\n变异自检结果")
    print("-" * 62)
    for name, verdict in rows:
        print(f"{verdict:<28} {name}")
    print("-" * 62)
    bad = [v for _, v in rows if not v.startswith("CAUGHT")]
    print(f"合计 {len(rows)} 条，符合期望 {len(rows) - len(bad)} 条")
    return 0 if not bad else 1


if __name__ == "__main__":
    raise SystemExit(main())
