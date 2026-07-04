package com.naviq.completion.suggests;

import com.naviq.antlr4.PostgreSQLParser;
import com.naviq.completion.syntactic.SyntacticAnalyzer;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lọc "rác" trong danh sách keyword mà {@code AntlrCompletionEngine} trả về - 2 loại nhiễu
 * KHÁC NHAU, tách riêng khỏi CompletionEngine để orchestrator gọn hơn:
 * <p>
 * 1. {@link #STATEMENT_START_TOKENS}/{@link #isFreshStatementPosition} - chặn keyword
 * bắt-đầu-câu (SELECT/INSERT/...) lặp lại GIỮA 1 câu đang gõ dở (vd "select |" tự gợi ý
 * lại "select") - do grammar Postgres thật cho phép "SELECT" (không cột nào, xem
 * opt_target_list?) được coi là 1 statement ĐÃ HOÀN CHỈNH, nên ATN engine đúng đắn (theo
 * grammar) liệt kê CẢ khả năng "bắt đầu statement mới" tại đó - ambiguity CỐ Ý của
 * Postgres, không sửa được ở tầng grammar, phải lọc bằng heuristic văn bản ở đây.
 * <p>
 * 2. {@link #IDENTIFIER_USABLE_KEYWORDS}/{@link #hasColumnrefCandidate} - chặn các
 * keyword-cũng-dùng-được-làm-tên-cột/bảng/alias (vd "insert", "at", "do" - xem
 * unreserved_keyword/col_name_keyword/plsql_unreserved_keyword trong grammar) khi ĐÃ có
 * gợi ý cột/alias/tên bảng THẬT tại cùng vị trí - tránh nhiễu, vì không ai thực sự gõ hẳn
 * "insert" làm tên cột trong thực tế.
 */
final class KeywordNoiseFilter {

    private KeywordNoiseFilter() {
    }

    // =====================================================================
    // Nhóm 1: chặn keyword bắt-đầu-câu lặp lại giữa câu
    // =====================================================================

    static final Set<Integer> STATEMENT_START_TOKENS = Set.of(
            PostgreSQLParser.SELECT, PostgreSQLParser.INSERT, PostgreSQLParser.UPDATE,
            PostgreSQLParser.DELETE_P, PostgreSQLParser.WITH, PostgreSQLParser.CREATE,
            PostgreSQLParser.DROP, PostgreSQLParser.ALTER, PostgreSQLParser.TRUNCATE);

    /**
     * true nếu đoạn text TỪ dấu ";" gần nhất HOẶC từ ranh giới BEGIN (mở block PL/pgSQL)
     * gần nhất - TỚI cursor là RỖNG/toàn khoảng trắng. Trước đây CHỈ nhận ";" làm ranh
     * giới, nên "CREATE PROCEDURE ... AS $$ BEGIN |" bị coi là KHÔNG ở ranh giới câu mới
     * (vì chưa có ";" nào trong toàn bộ chuỗi) - mất hết SELECT/INSERT/UPDATE ngay sau
     * BEGIN dù đó chính là vị trí hợp lệ để bắt đầu 1 statement mới trong function body.
     * Heuristic đơn giản: tìm ranh giới GẦN CURSOR NHẤT trong 2 loại (";" hoặc từ "begin"
     * đứng riêng 1 mình, không phải 1 phần của định danh khác) - không parse đầy đủ cấu
     * trúc PL/pgSQL (BEGIN...END lồng nhau, EXCEPTION block...), chỉ là cải thiện thực
     * dụng cho case phổ biến nhất.
     */
    static boolean isFreshStatementPosition(String sql, int cursorOffset) {
        String upToCursor = sql.substring(0, Math.min(cursorOffset, sql.length()));
        int lastSemi = upToCursor.lastIndexOf(';');

        int lastBegin = -1;
        var m = Pattern.compile("(?i)\\bbegin\\b").matcher(upToCursor);
        while (m.find()) {
            lastBegin = m.end();
        }

        int boundary = Math.max(lastSemi + 1, lastBegin);
        String sinceLastStatement = upToCursor.substring(Math.max(0, boundary));
        return sinceLastStatement.isBlank();
    }

    // =====================================================================
    // Nhóm 2: chặn keyword-cũng-dùng-được-làm-identifier khi đã có gợi ý thật
    // =====================================================================

    static final Set<Integer> IDENTIFIER_USABLE_KEYWORDS = Set.of(
            PostgreSQLParser.ABORT_P, PostgreSQLParser.ABSENT, PostgreSQLParser.ABSOLUTE_P, PostgreSQLParser.ACCESS, PostgreSQLParser.ACTION,
            PostgreSQLParser.ADD_P, PostgreSQLParser.ADMIN, PostgreSQLParser.AFTER, PostgreSQLParser.AGGREGATE, PostgreSQLParser.ALIAS,
            PostgreSQLParser.ALSO, PostgreSQLParser.ALTER, PostgreSQLParser.ALWAYS, PostgreSQLParser.AND, PostgreSQLParser.ARRAY,
            PostgreSQLParser.ASENSITIVE, PostgreSQLParser.ASSERT, PostgreSQLParser.ASSERTION, PostgreSQLParser.ASSIGNMENT, PostgreSQLParser.AT,
            PostgreSQLParser.ATOMIC, PostgreSQLParser.ATTACH, PostgreSQLParser.ATTRIBUTE, PostgreSQLParser.BACKWARD, PostgreSQLParser.BEFORE,
            PostgreSQLParser.BEGIN_P, PostgreSQLParser.BETWEEN, PostgreSQLParser.BIGINT, PostgreSQLParser.BIT, PostgreSQLParser.BOOLEAN_P,
            PostgreSQLParser.BREADTH, PostgreSQLParser.BY, PostgreSQLParser.CACHE, PostgreSQLParser.CALL, PostgreSQLParser.CALLED,
            PostgreSQLParser.CASCADE, PostgreSQLParser.CASCADED, PostgreSQLParser.CAST, PostgreSQLParser.CATALOG_P, PostgreSQLParser.CHAIN,
            PostgreSQLParser.CHARACTER, PostgreSQLParser.CHARACTERISTICS, PostgreSQLParser.CHAR_P, PostgreSQLParser.CHECKPOINT, PostgreSQLParser.CLASS,
            PostgreSQLParser.CLOSE, PostgreSQLParser.CLUSTER, PostgreSQLParser.COLLATE, PostgreSQLParser.COLLATION, PostgreSQLParser.COLUMN,
            PostgreSQLParser.COLUMNS, PostgreSQLParser.COMMENT, PostgreSQLParser.COMMENTS, PostgreSQLParser.COMMIT, PostgreSQLParser.COMMITTED,
            PostgreSQLParser.COMPRESSION, PostgreSQLParser.CONCURRENTLY, PostgreSQLParser.CONDITIONAL, PostgreSQLParser.CONFIGURATION, PostgreSQLParser.CONFLICT,
            PostgreSQLParser.CONNECTION, PostgreSQLParser.CONSTANT, PostgreSQLParser.CONSTRAINT, PostgreSQLParser.CONSTRAINTS, PostgreSQLParser.CONTENT_P,
            PostgreSQLParser.CONTINUE_P, PostgreSQLParser.CONVERSION_P, PostgreSQLParser.COPY, PostgreSQLParser.COST, PostgreSQLParser.CROSS,
            PostgreSQLParser.CSV, PostgreSQLParser.CUBE, PostgreSQLParser.CURRENT_CATALOG, PostgreSQLParser.CURRENT_P, PostgreSQLParser.CURRENT_SCHEMA,
            PostgreSQLParser.CURSOR, PostgreSQLParser.CYCLE, PostgreSQLParser.DATABASE, PostgreSQLParser.DATA_P, PostgreSQLParser.DAY_P,
            PostgreSQLParser.DEALLOCATE, PostgreSQLParser.DEBUG, PostgreSQLParser.DEC, PostgreSQLParser.DECIMAL_P, PostgreSQLParser.DECLARE,
            PostgreSQLParser.DEFAULT, PostgreSQLParser.DEFAULTS, PostgreSQLParser.DEFERRED, PostgreSQLParser.DEFINER, PostgreSQLParser.DELETE_P,
            PostgreSQLParser.DELIMITER, PostgreSQLParser.DELIMITERS, PostgreSQLParser.DEPENDS, PostgreSQLParser.DEPTH, PostgreSQLParser.DETACH,
            PostgreSQLParser.DIAGNOSTICS, PostgreSQLParser.DICTIONARY, PostgreSQLParser.DISABLE_P, PostgreSQLParser.DISCARD, PostgreSQLParser.DO,
            PostgreSQLParser.DOCUMENT_P, PostgreSQLParser.DOMAIN_P, PostgreSQLParser.DOUBLE_P, PostgreSQLParser.DROP, PostgreSQLParser.DUMP,
            PostgreSQLParser.EACH, PostgreSQLParser.ELSIF, PostgreSQLParser.EMPTY_P, PostgreSQLParser.ENABLE_P, PostgreSQLParser.ENCODING,
            PostgreSQLParser.ENCRYPTED, PostgreSQLParser.ENFORCED, PostgreSQLParser.ENUM_P, PostgreSQLParser.ERROR_P, PostgreSQLParser.ESCAPE,
            PostgreSQLParser.EVENT, PostgreSQLParser.EXCEPTION, PostgreSQLParser.EXCLUDE, PostgreSQLParser.EXCLUDING, PostgreSQLParser.EXCLUSIVE,
            PostgreSQLParser.EXECUTE, PostgreSQLParser.EXISTS, PostgreSQLParser.EXIT, PostgreSQLParser.EXPLAIN, PostgreSQLParser.EXPRESSION,
            PostgreSQLParser.EXTENSION, PostgreSQLParser.EXTERNAL, PostgreSQLParser.EXTRACT, PostgreSQLParser.FAMILY, PostgreSQLParser.FETCH,
            PostgreSQLParser.FILTER, PostgreSQLParser.FINALIZE, PostgreSQLParser.FIRST_P, PostgreSQLParser.FLOAT_P, PostgreSQLParser.FOLLOWING,
            PostgreSQLParser.FORCE, PostgreSQLParser.FORMAT, PostgreSQLParser.FORWARD, PostgreSQLParser.FUNCTION, PostgreSQLParser.FUNCTIONS,
            PostgreSQLParser.GENERATED, PostgreSQLParser.GET, PostgreSQLParser.GLOBAL, PostgreSQLParser.GRANTED, PostgreSQLParser.GREATEST,
            PostgreSQLParser.GROUPING, PostgreSQLParser.GROUPS, PostgreSQLParser.HANDLER, PostgreSQLParser.HEADER_P, PostgreSQLParser.HOLD,
            PostgreSQLParser.HOUR_P, PostgreSQLParser.IDENTITY_P, PostgreSQLParser.IF_P, PostgreSQLParser.IMMEDIATE, PostgreSQLParser.IMMUTABLE,
            PostgreSQLParser.IMPLICIT_P, PostgreSQLParser.IMPORT_P, PostgreSQLParser.INCLUDE, PostgreSQLParser.INCLUDING, PostgreSQLParser.INCREMENT,
            PostgreSQLParser.INDENT, PostgreSQLParser.INDEX, PostgreSQLParser.INDEXES, PostgreSQLParser.INFO, PostgreSQLParser.INHERIT,
            PostgreSQLParser.INHERITS, PostgreSQLParser.INLINE_P, PostgreSQLParser.INOUT, PostgreSQLParser.INPUT_P, PostgreSQLParser.INSENSITIVE,
            PostgreSQLParser.INSERT, PostgreSQLParser.INSTEAD, PostgreSQLParser.INTEGER, PostgreSQLParser.INTERVAL, PostgreSQLParser.INT_P,
            PostgreSQLParser.INVOKER, PostgreSQLParser.IS, PostgreSQLParser.ISOLATION, PostgreSQLParser.JSON, PostgreSQLParser.JSON_ARRAY,
            PostgreSQLParser.JSON_ARRAYAGG, PostgreSQLParser.JSON_EXISTS, PostgreSQLParser.JSON_OBJECT, PostgreSQLParser.JSON_OBJECTAGG, PostgreSQLParser.JSON_QUERY,
            PostgreSQLParser.JSON_SCALAR, PostgreSQLParser.JSON_SERIALIZE, PostgreSQLParser.JSON_TABLE, PostgreSQLParser.JSON_VALUE, PostgreSQLParser.KEEP,
            PostgreSQLParser.KEY, PostgreSQLParser.KEYS, PostgreSQLParser.LABEL, PostgreSQLParser.LANGUAGE, PostgreSQLParser.LARGE_P,
            PostgreSQLParser.LAST_P, PostgreSQLParser.LEAKPROOF, PostgreSQLParser.LEAST, PostgreSQLParser.LEVEL, PostgreSQLParser.LISTEN,
            PostgreSQLParser.LOAD, PostgreSQLParser.LOCAL, PostgreSQLParser.LOCATION, PostgreSQLParser.LOCKED, PostgreSQLParser.LOCK_P,
            PostgreSQLParser.LOG, PostgreSQLParser.LOGGED, PostgreSQLParser.MAPPING, PostgreSQLParser.MATCH, PostgreSQLParser.MATCHED,
            PostgreSQLParser.MATERIALIZED, PostgreSQLParser.MAXVALUE, PostgreSQLParser.MERGE, PostgreSQLParser.MERGE_ACTION, PostgreSQLParser.METHOD,
            PostgreSQLParser.MINUTE_P, PostgreSQLParser.MINVALUE, PostgreSQLParser.MODE, PostgreSQLParser.MONTH_P, PostgreSQLParser.MOVE,
            PostgreSQLParser.NAMES, PostgreSQLParser.NAME_P, PostgreSQLParser.NATIONAL, PostgreSQLParser.NCHAR, PostgreSQLParser.NESTED,
            PostgreSQLParser.NEW, PostgreSQLParser.NEXT, PostgreSQLParser.NFC, PostgreSQLParser.NFD, PostgreSQLParser.NFKC,
            PostgreSQLParser.NFKD, PostgreSQLParser.NO, PostgreSQLParser.NONE, PostgreSQLParser.NORMALIZE, PostgreSQLParser.NORMALIZED,
            PostgreSQLParser.NOTHING, PostgreSQLParser.NOTICE, PostgreSQLParser.NOTIFY, PostgreSQLParser.NOWAIT, PostgreSQLParser.NULLIF,
            PostgreSQLParser.NULLS_P, PostgreSQLParser.NUMERIC, PostgreSQLParser.OBJECTS_P, PostgreSQLParser.OBJECT_P, PostgreSQLParser.OF,
            PostgreSQLParser.OFF, PostgreSQLParser.OIDS, PostgreSQLParser.OLD, PostgreSQLParser.OMIT, PostgreSQLParser.OPEN,
            PostgreSQLParser.OPERATOR, PostgreSQLParser.OPTION, PostgreSQLParser.OPTIONS, PostgreSQLParser.ORDINALITY, PostgreSQLParser.OTHERS,
            PostgreSQLParser.OUTER_P, PostgreSQLParser.OUT_P, PostgreSQLParser.OVER, PostgreSQLParser.OVERLAY, PostgreSQLParser.OVERRIDING,
            PostgreSQLParser.OWNED, PostgreSQLParser.OWNER, PostgreSQLParser.PARALLEL, PostgreSQLParser.PARAMETER, PostgreSQLParser.PARSER,
            PostgreSQLParser.PARTIAL, PostgreSQLParser.PARTITION, PostgreSQLParser.PASSING, PostgreSQLParser.PASSWORD, PostgreSQLParser.PATH,
            PostgreSQLParser.PERFORM, PostgreSQLParser.PERIOD, PostgreSQLParser.PLAN, PostgreSQLParser.PLANS, PostgreSQLParser.POLICY,
            PostgreSQLParser.POSITION, PostgreSQLParser.PRECEDING, PostgreSQLParser.PRECISION, PostgreSQLParser.PREPARE, PostgreSQLParser.PREPARED,
            PostgreSQLParser.PRESERVE, PostgreSQLParser.PRINT_STRICT_PARAMS, PostgreSQLParser.PRIOR, PostgreSQLParser.PRIVILEGES, PostgreSQLParser.PROCEDURAL,
            PostgreSQLParser.PROCEDURE, PostgreSQLParser.PROCEDURES, PostgreSQLParser.PROGRAM, PostgreSQLParser.PUBLICATION, PostgreSQLParser.QUERY,
            PostgreSQLParser.QUOTE, PostgreSQLParser.QUOTES, PostgreSQLParser.RAISE, PostgreSQLParser.RANGE, PostgreSQLParser.READ,
            PostgreSQLParser.REAL, PostgreSQLParser.REASSIGN, PostgreSQLParser.RECURSIVE, PostgreSQLParser.REFERENCING, PostgreSQLParser.REFRESH,
            PostgreSQLParser.REF_P, PostgreSQLParser.REINDEX, PostgreSQLParser.RELATIVE_P, PostgreSQLParser.RELEASE, PostgreSQLParser.RENAME,
            PostgreSQLParser.REPEATABLE, PostgreSQLParser.REPLACE, PostgreSQLParser.REPLICA, PostgreSQLParser.RESET, PostgreSQLParser.RESTART,
            PostgreSQLParser.RESTRICT, PostgreSQLParser.RETURN, PostgreSQLParser.RETURNS, PostgreSQLParser.REVERSE, PostgreSQLParser.REVOKE,
            PostgreSQLParser.ROLE, PostgreSQLParser.ROLLBACK, PostgreSQLParser.ROLLUP, PostgreSQLParser.ROUTINE, PostgreSQLParser.ROUTINES,
            PostgreSQLParser.ROW, PostgreSQLParser.ROWS, PostgreSQLParser.ROWTYPE, PostgreSQLParser.RULE, PostgreSQLParser.SAVEPOINT,
            PostgreSQLParser.SCALAR, PostgreSQLParser.SCHEMA, PostgreSQLParser.SCHEMAS, PostgreSQLParser.SCROLL, PostgreSQLParser.SEARCH,
            PostgreSQLParser.SECOND_P, PostgreSQLParser.SECURITY, PostgreSQLParser.SEQUENCE, PostgreSQLParser.SEQUENCES, PostgreSQLParser.SERIALIZABLE,
            PostgreSQLParser.SERVER, PostgreSQLParser.SESSION, PostgreSQLParser.SET, PostgreSQLParser.SETOF, PostgreSQLParser.SETS,
            PostgreSQLParser.SHARE, PostgreSQLParser.SHOW, PostgreSQLParser.SIMPLE, PostgreSQLParser.SKIP_P, PostgreSQLParser.SLICE,
            PostgreSQLParser.SMALLINT, PostgreSQLParser.SNAPSHOT, PostgreSQLParser.SOURCE, PostgreSQLParser.SQLSTATE, PostgreSQLParser.SQL_P,
            PostgreSQLParser.STABLE, PostgreSQLParser.STACKED, PostgreSQLParser.STANDALONE_P, PostgreSQLParser.START, PostgreSQLParser.STATEMENT,
            PostgreSQLParser.STATISTICS, PostgreSQLParser.STDIN, PostgreSQLParser.STDOUT, PostgreSQLParser.STORAGE, PostgreSQLParser.STORED,
            PostgreSQLParser.STRICT_P, PostgreSQLParser.STRING_P, PostgreSQLParser.STRIP_P, PostgreSQLParser.SUBSCRIPTION, PostgreSQLParser.SUBSTRING,
            PostgreSQLParser.SUPPORT, PostgreSQLParser.SYSID, PostgreSQLParser.SYSTEM_P, PostgreSQLParser.TABLE, PostgreSQLParser.TABLES,
            PostgreSQLParser.TABLESPACE, PostgreSQLParser.TARGET, PostgreSQLParser.TEMP, PostgreSQLParser.TEMPLATE, PostgreSQLParser.TEMPORARY,
            PostgreSQLParser.TEXT_P, PostgreSQLParser.TIES, PostgreSQLParser.TIME, PostgreSQLParser.TIMESTAMP, PostgreSQLParser.TRANSACTION,
            PostgreSQLParser.TRANSFORM, PostgreSQLParser.TREAT, PostgreSQLParser.TRIGGER, PostgreSQLParser.TRIM, PostgreSQLParser.TRUNCATE,
            PostgreSQLParser.TRUSTED, PostgreSQLParser.TYPES_P, PostgreSQLParser.TYPE_P, PostgreSQLParser.UESCAPE, PostgreSQLParser.UNBOUNDED,
            PostgreSQLParser.UNCOMMITTED, PostgreSQLParser.UNCONDITIONAL, PostgreSQLParser.UNENCRYPTED, PostgreSQLParser.UNKNOWN, PostgreSQLParser.UNLISTEN,
            PostgreSQLParser.UNLOGGED, PostgreSQLParser.UNTIL, PostgreSQLParser.UPDATE, PostgreSQLParser.USE_COLUMN, PostgreSQLParser.USE_VARIABLE,
            PostgreSQLParser.USER, PostgreSQLParser.VACUUM, PostgreSQLParser.VALID, PostgreSQLParser.VALIDATE, PostgreSQLParser.VALIDATOR,
            PostgreSQLParser.VALUE_P, PostgreSQLParser.VARCHAR, PostgreSQLParser.VARIABLE_CONFLICT, PostgreSQLParser.VARYING, PostgreSQLParser.VERSION_P,
            PostgreSQLParser.VIEW, PostgreSQLParser.VIEWS, PostgreSQLParser.VIRTUAL, PostgreSQLParser.VOLATILE, PostgreSQLParser.WARNING,
            PostgreSQLParser.WHITESPACE_P, PostgreSQLParser.WITHIN, PostgreSQLParser.WITHOUT, PostgreSQLParser.WORK, PostgreSQLParser.WRAPPER,
            PostgreSQLParser.WRITE, PostgreSQLParser.XMLATTRIBUTES, PostgreSQLParser.XMLCONCAT, PostgreSQLParser.XMLELEMENT, PostgreSQLParser.XMLEXISTS,
            PostgreSQLParser.XMLFOREST, PostgreSQLParser.XMLNAMESPACES, PostgreSQLParser.XMLPARSE, PostgreSQLParser.XMLPI, PostgreSQLParser.XMLROOT,
            PostgreSQLParser.XMLSERIALIZE, PostgreSQLParser.XMLTABLE, PostgreSQLParser.XML_P, PostgreSQLParser.YEAR_P, PostgreSQLParser.YES_P,
            PostgreSQLParser.ZONE
    );

    /**
     * true nếu vị trí caret CŨNG hợp lệ cho columnref, table_alias, HOẶC qualified_name/
     * any_name/colid (tức đã/sẽ có gợi ý cột/alias/tên bảng THẬT) - dùng để quyết định có
     * nên ẩn nhóm IDENTIFIER_USABLE_KEYWORDS khỏi "keyword" hay không. Bao gồm cả
     * qualified_name/any_name vì CÙNG 1 loại nhiễu xảy ra ở "FROM |" (tên bảng CŨNG có
     * thể literally là "at"/"by"/"do" theo đúng rule identifier), và colid cho
     * UPDATE...SET/ALTER TABLE ADD-DROP-ALTER COLUMN/JOIN USING.
     */
    static boolean hasColumnrefCandidate(SyntacticAnalyzer.Result syn) {
        var rules = syn.candidates().rules;
        return rules.containsKey(PostgreSQLParser.RULE_columnref)
                || rules.containsKey(PostgreSQLParser.RULE_table_alias)
                || rules.containsKey(PostgreSQLParser.RULE_qualified_name)
                || rules.containsKey(PostgreSQLParser.RULE_any_name)
                || rules.containsKey(PostgreSQLParser.RULE_colid);
    }
}