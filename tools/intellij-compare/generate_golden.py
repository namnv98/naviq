#!/usr/bin/env python3
"""Sinh golden file cho IntellijGoldenRegressionTest từ target/ij-compare/ij-out.tsv (xem README.md).

Lấy TOÀN BỘ câu mà IntelliJ trả về ít nhất 1 gợi ý (không chỉ câu khớp hoàn toàn). Giá trị golden LUÔN
LÀ GỢI Ý CỦA INTELLIJ (không phải của tool) - kể cả ở các câu đã biết IntelliJ sai theo Postgres thật
(xem tools/intellij-compare/README.md mục "Đọc kết quả"): IntelliJ là mẫu gốc tuyệt đối, test sẽ đỏ ở
những câu đó cho tới khi tool được sửa để khớp, đúng/sai của IntelliJ tính sau. Mỗi câu KHÁC gợi ý hiện
tại của tool được đánh dấu "diverges_from_tool" trên dòng CASE để dễ tra cứu.

Ghi src/test/resources/completion/intellij-golden.tsv, test IntellijGoldenRegressionTest đọc file này
để phát hiện hồi quy: gợi ý thật của tool trên từng câu phải tiếp tục khớp giá trị đã ghi ở đây.

Chạy sau khi đã có target/ij-compare/ij-out.tsv + tool-out.tsv (xem README.md bước 1-5), từ thư mục
gốc repo: python3 tools/intellij-compare/generate_golden.py
"""
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from compare import DIR, load, ij_sets, tool_sets, real_roles  # noqa: E402

OUT = "src/test/resources/completion/intellij-golden.tsv"


def main():
    ij = load(os.path.join(DIR, "ij-out.tsv"))
    tool = load(os.path.join(DIR, "tool-out.tsv"))
    roles_ref = real_roles()
    lines = []
    agree = 0
    diverge = 0
    for key in tool:
        if key not in ij or not ij[key]:
            continue
        ir, ic, irole, ikw, ifunc, _iother = ij_sets(ij[key], roles_ref)
        tr, tc, trole, tkw, tfunc = tool_sets(tool[key])
        tq = {(q, c) for q, c in tc if q}
        matches = (ir == tr
                   and {c for _, c in tc} == {c for _, c in ic} and (not tq or tq == ic)
                   and trole == irole and tfunc == ifunc and tkw == ikw)
        if matches:
            agree += 1
            note = ""
        else:
            diverge += 1
            note = "diverges_from_tool"
        lines.append(f"{key}\tCASE\t{note}\t")
        for r in sorted(ir):
            lines.append(f"{key}\tREL\t{r}\t")
        for q, c in sorted(ic, key=lambda x: (x[0] or "", x[1])):
            lines.append(f"{key}\tCOL\t{q or ''}\t{c}")
        for r in sorted(irole):
            lines.append(f"{key}\tROLE\t{r}\t")
        for f in sorted(ifunc):
            lines.append(f"{key}\tFUNC\t{f}\t")
        for k in sorted(ikw):
            lines.append(f"{key}\tKW\t{k}\t")
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as w:
        w.write("\n".join(lines) + "\n")
    print(f"{agree + diverge} câu -> {OUT} ({agree} khớp tool hiện tại, {diverge} lệch - test sẽ đỏ ở "
          f"những câu này cho tới khi tool được sửa để khớp IntelliJ, xem README)")


if __name__ == "__main__":
    main()
