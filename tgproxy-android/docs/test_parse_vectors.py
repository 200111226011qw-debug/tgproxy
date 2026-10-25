# -*- coding: utf-8 -*-
"""pytest 收集入口：复用 qa_parser_vectors.py 的 golden 断言。"""
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))

from qa_parser_vectors import test_parse_vectors  # noqa: E402,F401
