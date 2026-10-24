package com.sqlctx.schema;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Chuyển đổi giữa tên trong {@link SchemaIndex} và tên gõ trong câu SQL.
 * <p>
 * Tên trong index: Postgres giữ đúng như catalog (thường là chữ thường); Oracle hạ tên chữ hoa (tên tạo không quote)
 * về chữ thường, còn tên tạo có quote với chữ thường/hoa lẫn lộn ("OrderItems") thì giữ nguyên - xem
 * {@link SchemaLoader#oracleName(String)}.
 */
public final class Identifiers {

    private Identifiers() {
    }

    /** Từ khoá reserved của Postgres (kể cả loại "chỉ dùng được làm tên hàm/kiểu") - làm tên bảng/cột phải quote. */
    private static final Set<String> PG_RESERVED = Set.of(
            "all", "analyse", "analyze", "and", "any", "array", "as", "asc", "asymmetric", "authorization", "binary",
            "both", "case", "cast", "check", "collate", "collation", "column", "concurrently", "constraint", "create",
            "cross", "current_catalog", "current_date", "current_role", "current_schema", "current_time",
            "current_timestamp", "current_user", "default", "deferrable", "desc", "distinct", "do", "else", "end",
            "except", "false", "fetch", "for", "foreign", "freeze", "from", "full", "grant", "group", "having", "ilike",
            "in", "initially", "inner", "intersect", "into", "is", "isnull", "join", "lateral", "leading", "left",
            "like", "limit", "localtime", "localtimestamp", "natural", "not", "notnull", "null", "offset", "on", "only",
            "or", "order", "outer", "overlaps", "placing", "primary", "references", "returning", "right", "select",
            "session_user", "similar", "some", "symmetric", "system_user", "table", "tablesample", "then", "to",
            "trailing", "true", "union", "unique", "user", "using", "variadic", "verbose", "when", "where", "window",
            "with");

    /** V$RESERVED_WORDS (RESERVED = 'Y') của Oracle, chữ thường. */
    private static final Set<String> ORACLE_RESERVED = Set.of(
            "access", "add", "all", "alter", "and", "any", "as", "asc", "audit", "between", "by", "char", "check",
            "cluster", "column", "comment", "compress", "connect", "create", "current", "date", "decimal", "default",
            "delete", "desc", "distinct", "drop", "else", "exclusive", "exists", "file", "float", "for", "from", "grant",
            "group", "having", "identified", "immediate", "in", "increment", "index", "initial", "insert", "integer",
            "intersect", "into", "is", "level", "like", "lock", "long", "maxextents", "minus", "mlslabel", "mode",
            "modify", "noaudit", "nocompress", "not", "nowait", "null", "number", "of", "offline", "on", "online",
            "option", "or", "order", "pctfree", "prior", "public", "raw", "rename", "resource", "revoke", "row",
            "rowid", "rownum", "rows", "select", "session", "set", "share", "size", "smallint", "start", "successful",
            "synonym", "sysdate", "table", "then", "to", "trigger", "uid", "union", "unique", "update", "user",
            "validate", "values", "varchar", "varchar2", "view", "whenever", "where", "with");

    private static final Pattern PG_PLAIN = Pattern.compile("[a-z_][a-z0-9_$]*");
    private static final Pattern ORACLE_PLAIN = Pattern.compile("[a-z][a-z0-9_$#]*");

    /** Tên trong index -> dạng chèn vào câu SQL: tên trần nếu gõ trần DB vẫn hiểu đúng, ngược lại đặt trong ngoặc kép. */
    public static String quote(String name, Dialect dialect) {
        if (dialect == Dialect.ORACLE) {
            if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
                return name; // tên gốc toàn chữ thường, đã lưu kèm ngoặc kép (SchemaLoader.oracleName)
            }
            boolean folded = name.equals(name.toLowerCase()); // tên gốc chữ hoa, đã hạ về chữ thường lúc nạp
            if (folded && ORACLE_PLAIN.matcher(name).matches() && !ORACLE_RESERVED.contains(name)) {
                return name;
            }
            return "\"" + (folded ? name.toUpperCase() : name).replace("\"", "\"\"") + "\"";
        }
        if (PG_PLAIN.matcher(name).matches() && !PG_RESERVED.contains(name)) {
            return name;
        }
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    /** Tên 1 phần như người dùng gõ (có/không ngoặc kép) -> tên trong index. */
    public static String normalize(String typed, Dialect dialect) {
        if (typed.length() >= 2 && typed.startsWith("\"") && typed.endsWith("\"")) {
            String exact = typed.substring(1, typed.length() - 1).replace("\"\"", "\"");
            if (dialect == Dialect.ORACLE) {
                // "USERS" có quote vẫn là bảng USERS thường (trong index "users"); "abc" toàn thường lưu kèm ngoặc kép
                if (exact.equals(exact.toUpperCase())) {
                    return exact.toLowerCase();
                }
                if (exact.equals(exact.toLowerCase())) {
                    return "\"" + exact + "\"";
                }
            }
            return exact;
        }
        return typed.toLowerCase(); // tên không quote: cả 2 DB đều gấp hoa/thường (Postgres về thường, Oracle về hoa)
    }

    /** Như {@link #normalize}, cho tên có thể có tiền tố "schema." - dấu chấm trong ngoặc kép không tính. */
    public static String normalizeQualified(String typed, Dialect dialect) {
        StringBuilder out = new StringBuilder();
        int start = 0;
        boolean inQuotes = false;
        for (int i = 0; i <= typed.length(); i++) {
            if (i == typed.length() || (typed.charAt(i) == '.' && !inQuotes)) {
                if (!out.isEmpty()) {
                    out.append('.');
                }
                out.append(normalize(typed.substring(start, i), dialect));
                start = i + 1;
            } else if (typed.charAt(i) == '"') {
                inQuotes = !inQuotes;
            }
        }
        return out.toString();
    }
}
