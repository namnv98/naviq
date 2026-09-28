#!/usr/bin/env python3
"""Trích mọi câu SQL test Postgres (pg("...|...") và @ValueSource) ra target/ij-compare/ij-cases.txt.

Mỗi dòng 1 câu, "|" là vị trí con trỏ; xuống dòng/gạch chéo ngược được escape thành \\n / \\\\.
Chạy từ thư mục gốc repo: python3 tools/intellij-compare/extract_cases.py
"""
import glob
import os
import re

OUT_DIR = "target/ij-compare"
STR = r'"((?:[^"\\]|\\.)*)"'


def unescape_java(s):
    return re.sub(r'\\(.)', lambda m: {'n': '\n', 't': '\t'}.get(m.group(1), m.group(1)), s)


def main():
    cases = []
    for path in sorted(glob.glob("src/test/java/com/sqlctx/completion/Postgres*Test.java")):
        src = open(path, encoding="utf-8").read()
        found = [''.join(unescape_java(x) for x in re.findall(STR, m.group(1)))
                 for m in re.finditer(r'\bpg\(\s*((?:' + STR + r'\s*\+?\s*)+)\)', src)]
        for m in re.finditer(r'@ValueSource\(strings\s*=\s*\{(.*?)\}\)', src, re.S):
            found += [unescape_java(x) for x in re.findall(STR, m.group(1))]
        for sql in found:
            if '|' in sql and sql not in cases:
                cases.append(sql)
    os.makedirs(OUT_DIR, exist_ok=True)
    with open(os.path.join(OUT_DIR, "ij-cases.txt"), "w", encoding="utf-8") as w:
        for sql in cases:
            w.write(sql.replace('\\', '\\\\').replace('\n', '\\n') + '\n')
    print(f"{len(cases)} câu -> {OUT_DIR}/ij-cases.txt")


if __name__ == "__main__":
    main()
