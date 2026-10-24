package com.sqlctx.schema;

import java.util.List;

/**
 * Khoá ngoại {@code fromTable(fromColumns) REFERENCES toTable(toColumns)}; tên bảng dạng "schema.bảng" như
 * {@link TableInfo#fullName()}, 2 danh sách cột cùng độ dài và cùng thứ tự (khoá ghép).
 */
public record ForeignKey(
        String fromTable,
        List<String> fromColumns,
        String toTable,
        List<String> toColumns
) {
}
