#!/usr/bin/env python3
"""So gợi ý của tool (tool-out.tsv) với IntelliJ (ij-out.tsv) trong target/ij-compare - xem README.md.

IntelliJ là mẫu gốc: so TOÀN BỘ relation/cột/role thật trả về, KHÔNG lọc bớt theo danh sách cố định nào
(DB thật có gì so cái đó - kể cả role nội bộ pg_*, relation ở schema pg_catalog...). Khác biệt không có
nghĩa tool sai - review bằng Postgres thật rồi mới quyết định sửa. Hàm built-in và keyword vẫn không so
(không có cách phân biệt tin cậy hàm nào là "built-in" trên cả 2 phía) nhưng vẫn đếm số lượng để biết.
Kết quả: target/ij-compare/compare.txt (+ compare.json). Chạy từ thư mục gốc repo:
python3 tools/intellij-compare/compare.py
"""
import collections
import json
import os
import re
import subprocess

DIR = "target/ij-compare"


def _psql_list(sql):
    """Chạy 1 câu trả về 1 cột trên chính DB fixture thật (docker exec) - dùng để tách role/database
    thật thay vì đoán qua whitelist: raw dump IntelliJ không phân biệt được role/database (cả 2 đều
    hiện dạng "<db>@localhost", không tail), phải tra DB thật mới biết đúng loại."""
    out = subprocess.run(
        ["docker", "exec", "sqlctx-postgres", "psql", "-U", "tester", "-d", "sqlctx_fixture", "-tA", "-c", sql],
        capture_output=True, text=True, check=True)
    return {line.strip() for line in out.stdout.splitlines() if line.strip()}


def real_roles():
    return _psql_list("SELECT rolname FROM pg_roles")


def real_databases():
    return _psql_list("SELECT datname FROM pg_database WHERE NOT datistemplate")


def load(path):
    d = collections.OrderedDict()
    for line in open(path, encoding="utf-8"):
        parts = line.rstrip("\n").split("\t")
        key, idx = parts[0], parts[1]
        d.setdefault(key, [])
        if idx != "-1":
            d[key].append(parts[2:])
    return d


def ij_sets(items, roles_ref):
    """Phân loại item IntelliJ THUẦN theo cấu trúc typeText/tailText (script dump không lấy được kind
    thật của DB object). Không loại bỏ gì - mọi item đều rơi vào đúng 1 trong các nhóm sau:
    - relation: typeText kết thúc "@localhost", tail dạng " (<db>.<schema>)" (đúng 1 dấu chấm) -> relation
      ở BẤT KỲ schema nào (public, pg_catalog...), không chỉ public.
    - role: typeText kết thúc "@localhost", KHÔNG có tail, VÀ tên nằm trong pg_roles thật (tra DB sống -
      role/database đều hiện dạng "<db>@localhost" không tail như nhau trong dump, không thể phân biệt
      chỉ bằng cấu trúc chuỗi; roles_ref lấy từ chính DB fixture, không phải whitelist tự chọn).
    - cột: tail dạng " (<gì đó>)" mà typeText KHÔNG kết thúc "@localhost" -> tiền tố lấy đoạn cuối cùng
      sau dấu chấm (alias hoặc tên bảng/schema.table).
    - keyword: kind = String.
    - func: còn lại - không có tail, typeText không kết thúc "@localhost" (hàm built-in trả kiểu X, kiểu
      dữ liệu cơ bản như "int4" cũng rơi vào đây vì Postgres có hàm cast cùng tên - gộp chung 1 nhóm).
    - other: phần rất nhỏ không khớp pattern nào ở trên (item lạ, không phải database object).
    """
    rels, cols, roles, kws, funcs, other = set(), set(), set(), set(), set(), []
    for it in items:
        s, kind, typ, tail = (it + ["", "", "", ""])[:4]
        m = re.fullmatch(r" \((.+)\)", tail)
        is_localhost = typ.endswith("@localhost")
        if kind == "String":
            kws.add(s.lower())
        elif is_localhost and m and m.group(1).count(".") == 1:
            rels.add(s)
        elif is_localhost and not tail and s in roles_ref:
            roles.add(s)
        elif m and not is_localhost:
            cols.add((m.group(1).split(".")[-1], s))
        elif not tail and not is_localhost and typ:
            funcs.add(s.lower())
        else:
            other.append(s)
    return rels, cols, roles, kws, funcs, other


def tool_sets(items):
    rels, cols, roles, kws, funcs = set(), set(), set(), set(), set()
    for key, typ in items:
        if typ in ("table", "view", "materialized view"):
            rels.add(key.split(".")[-1])
        elif typ == "datatype" and "." in key:
            # Kiểu composite (RETURNS/CAST/CREATE DOMAIN...) = tên 1 relation thật, tool gợi ý dưới
            # SuggestionType.DATATYPE (đúng: vị trí đó cú pháp là 1 typename) chứ không phải table/view -
            # kiểu cơ bản (int4, text...) không có dấu chấm nên không lẫn vào đây.
            rels.add(key.split(".")[-1])
        elif typ == "datatype":
            funcs.add(key.lower())  # kiểu cơ bản (int4, text...) - cùng nhóm với func phía IntelliJ
        elif typ == "column":
            q, _, c = key.rpartition(".")
            cols.add((q or None, c))
        elif typ == "role":
            roles.add(key)
        elif typ == "keyword":
            kws.add(key.lower())
        elif typ == "function":
            funcs.add(key.lower())
    return rels, cols, roles, kws, funcs


def main():
    ij = load(os.path.join(DIR, "ij-out.tsv"))
    tool = load(os.path.join(DIR, "tool-out.tsv"))
    roles_ref = real_roles()
    report, empty_ij = [], []
    for key in tool:
        if key not in ij:
            continue
        if not ij[key]:
            empty_ij.append(key)
            continue
        ir, ic, irole, ikw, ifunc, iother = ij_sets(ij[key], roles_ref)
        tr, tc, trole, tkw, tfunc = tool_sets(tool[key])
        tq = {(q, c) for q, c in tc if q}
        diff = {}
        if ir != tr:
            diff["relation"] = {"chỉ tool": sorted(tr - ir), "chỉ IntelliJ": sorted(ir - tr)}
        if {c for _, c in tc} != {c for _, c in ic} or (tq and tq != ic):
            diff["cột"] = {"tool": sorted(f"{q}.{c}" if q else c for q, c in tc),
                           "IntelliJ": sorted(f"{q}.{c}" for q, c in ic)}
        if trole != irole:
            diff["role"] = {"chỉ tool": sorted(trole - irole), "chỉ IntelliJ": sorted(irole - trole)}
        if tfunc != ifunc:
            diff["hàm/kiểu"] = {"chỉ tool": len(tfunc - ifunc), "chỉ IntelliJ": len(ifunc - tfunc)}
        if tkw != ikw:
            diff["keyword"] = {"chỉ tool": len(tkw - ikw), "chỉ IntelliJ": len(ikw - tkw)}
        report.append({"sql": key, "diff": diff})

    same = sum(1 for r in report if not r["diff"])
    lines = [f"so sánh {len(report)} câu (relation/cột/role/hàm/kiểu/keyword): khớp hoàn toàn {same}, khác {len(report) - same}",
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
