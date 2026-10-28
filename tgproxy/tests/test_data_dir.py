# -*- coding: utf-8 -*-
"""data/ 子目录：状态文件统一收口 + 旧布局迁移。

为什么要测迁移：老用户升级后，配置和节点缓存还平铺在程序目录里。如果只是把
路径改到 data/ 而不搬，用户会**看起来什么都没了**（配置被重置、known_good 消失），
实际是文件还在旧位置。这里锁住「搬过去」和「不覆盖」两条。
"""
import os
import sys

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), os.pardir)))

import app  # noqa: E402


def _patch(monkeypatch, tmp_path):
    base = str(tmp_path)
    data = os.path.join(base, "data")
    monkeypatch.setattr(app, "BASE_DIR", base)
    monkeypatch.setattr(app, "DATA_DIR", data)
    return base, data


def test_creates_data_dir(monkeypatch, tmp_path):
    _, data = _patch(monkeypatch, tmp_path)
    app.ensure_data_dir()
    assert os.path.isdir(data)


def test_migrates_legacy_files(monkeypatch, tmp_path):
    base, data = _patch(monkeypatch, tmp_path)
    for name in ("config.json", "known_good.json", "rounds.jsonl"):
        with open(os.path.join(base, name), "w", encoding="utf-8") as fh:
            fh.write("{}")
    app.ensure_data_dir()
    for name in ("config.json", "known_good.json", "rounds.jsonl"):
        assert os.path.isfile(os.path.join(data, name)), name + " 没有搬进 data/"
        assert not os.path.isfile(os.path.join(base, name)), name + " 还留在程序目录"


def test_does_not_overwrite_existing(monkeypatch, tmp_path):
    """data/ 里已经有同名文件时，旧的平铺文件不能覆盖它。"""
    base, data = _patch(monkeypatch, tmp_path)
    os.makedirs(data, exist_ok=True)
    with open(os.path.join(data, "config.json"), "w", encoding="utf-8") as fh:
        fh.write('{"port": 9999}')
    with open(os.path.join(base, "config.json"), "w", encoding="utf-8") as fh:
        fh.write('{"port": 8765}')
    app.ensure_data_dir()
    with open(os.path.join(data, "config.json"), encoding="utf-8") as fh:
        assert "9999" in fh.read()


def test_idempotent(monkeypatch, tmp_path):
    base, data = _patch(monkeypatch, tmp_path)
    with open(os.path.join(base, "known_bad.json"), "w", encoding="utf-8") as fh:
        fh.write("{}")
    app.ensure_data_dir()
    app.ensure_data_dir()  # 第二次不该炸
    assert os.path.isfile(os.path.join(data, "known_bad.json"))
