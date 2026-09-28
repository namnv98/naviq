#!/usr/bin/env python3
"""So gợi ý của tool (tool-out.tsv) với IntelliJ (ij-out.tsv) trong target/ij-compare - xem README.md.

Chỉ so trong phạm vi schema fixture (relation, cột kèm tiền tố, role): hàm built-in/bảng pg_catalog
mà IntelliJ biết thêm và keyword KHÔNG được so. Kết quả: target/ij-compare/compare.txt (+ compare.json).
Chạy từ thư mục gốc repo: python3 tools/intellij-compare/compare.py
"""
import collections
import json
import os
import re

DIR = "target/ij-compare"
RELS = {"users", "orders", "contracts", "products", "active_users", "daily_totals"}
COLS = {"id", "name", "email", "customer_id", "total", "status", "user_id", "amount", "price", "quantity",
        "description", "x", "y"}
ROLES = {"app_reader", "app_writer", "postgres"}


def load(path):
    d = collections.OrderedDict()
    for line in open(path, encoding="utf-8"):
        parts = line.rstrip("\n").split("\t")
        key, idx = parts[0], parts[1]
        d.setdefault(key, [])
        if idx != "-1":
            d[key].append(parts[2:])
    return d


def ij_sets(items):
    """Phân loại item IntelliJ theo typeText/tailText (script dump không lấy được kind của DB object):
    relation: typeText = <data source>, tail = " (sqlctx_fixture.public)"; cột: tail = " (<alias|bảng>)";
    role: typeText = <data source>, không tail; keyword: lớp String."""
    rels, cols, roles, kws, other = set(), set(), set(), set(), []
    for it in items:
        s, kind, typ, tail = (it + ["", "", "", ""])[:4]
        m = re.fullmatch(r" \((.+)\)", tail)
        if kind == "String":
            kws.add(s.lower())
        elif typ.endswith("@localhost") and tail == " (sqlctx_fixture.public)":
            rels.add(s)
        elif typ.endswith("@localhost") and not tail and s in ROLES:
            roles.add(s)
        elif m and s in COLS and not typ.endswith("@localhost"):
            cols.add((m.group(1).split(".")[-1], s))
        else:
            other.append(s)
    return rels, cols, roles, kws, other


def tool_sets(items):
    rels, cols, roles, kws = set(), set(), set(), set()
    for key, typ in items:
        if typ in ("table", "view", "materialized view"):
            rels.add(key.split(".")[-1])
        elif typ == "column":
            q, _, c = key.rpartition(".")
            cols.add((q or None, c))
        elif typ == "role":
            roles.add(key)
        elif typ == "keyword":
            kws.add(key.lower())
    return rels, cols, roles, kws


def main():
    ij = load(os.path.join(DIR, "ij-out.tsv"))
    tool = load(os.path.join(DIR, "tool-out.tsv"))
    report, empty_ij = [], []
    for key in tool:
        if key not in ij:
            continue
        if not ij[key]:
            empty_ij.append(key)
            continue
        ir, ic, irole, ikw, iother = ij_sets(ij[key])
        tr, tc, trole, tkw = tool_sets(tool[key])
        # CTE IntelliJ gợi ý không theo dạng relation của schema - coi là khớp nếu cùng tên
        ir |= {r for r in tr if r not in RELS and r in set(iother)}
        tq = {(q, c) for q, c in tc if q}
        diff = {}
        if ir != tr:
            diff["relation"] = {"chỉ tool": sorted(tr - ir), "chỉ IntelliJ": sorted(ir - tr)}
        if {c for _, c in tc} != {c for _, c in ic} or (tq and tq != ic):
            diff["cột"] = {"tool": sorted(f"{q}.{c}" if q else c for q, c in tc),
                           "IntelliJ": sorted(f"{q}.{c}" for q, c in ic)}
        if trole != irole:
            diff["role"] = {"chỉ tool": sorted(trole - irole), "chỉ IntelliJ": sorted(irole - trole)}
        report.append({"sql": key, "diff": diff, "keyword_tool": len(tkw), "keyword_ij": len(ikw),
                       "keyword_chung": len(tkw & ikw)})

    same = sum(1 for r in report if not r["diff"])
    lines = [f"so sánh {len(report)} câu: khớp hoàn toàn (relation/cột/role) {same}, khác {len(report) - same}",
             f"bỏ qua {len(empty_ij)} câu IntelliJ trả 0 gợi ý (có thể đúng là rỗng, hoặc popup chưa kịp hiện)"]
    for r in report:
        if r["diff"]:
            lines.append("\n" + r["sql"])
            lines += [f"   {k}: {v}" for k, v in r["diff"].items()]
    lines.append("\n=== IntelliJ trả 0 gợi ý ===")
    lines += [f"   {k}" for k in empty_ij]
    with open(os.path.join(DIR, "compare.txt"), "w", encoding="utf-8") as w:
        w.write("\n".join(lines) + "\n")
    with open(os.path.join(DIR, "compare.json"), "w", encoding="utf-8") as w:
        json.dump(report, w, ensure_ascii=False, indent=1)
    print("\n".join(lines[:2]) + f"\n-> {DIR}/compare.txt")


if __name__ == "__main__":
    main()
