package com.sqlctx.completion.model;

import java.util.Arrays;

public enum SuggestionType {
    /** Điều kiện nối theo khoá ngoại ở JOIN ... ON |, vd "o.user_id = u.id". */
    JOIN("join", 0),
    /** Cả danh sách cột thay cho 1 chỗ: "select *|" -> các cột của FROM, "group by |" -> các cột không gộp của SELECT. */
    COLUMNS("columns", 0),
    ALIAS("alias", 1),
    /** Giá trị hằng gợi ý được, vd giá trị enum ở "status = |". */
    VALUE("value", 1),
    COLUMN("column", 2),
    TABLE("table", 3),
    KEYWORD("keyword", 4),
    VIEW("view", 5),
    FUNCTION("function", 6),
    DATATYPE("datatype", 7),
    MATERIALIZED_VIEW("materialized view", 99),
    INDEX("index", 3),
    SEQUENCE("sequence", 3),
    TRIGGER("trigger", 3),
    SCHEMA("schema", 99),
    ROLE("role", 99),
    OTHER("other", 99),
    COMMAND("command", 0),
    DATABASE("database", 1),
    TABLESPACE("tablespace", 99);

    private final String label;
    private final int order;

    SuggestionType(String label, int order) {
        this.label = label;
        this.order = order;
    }

    public String label() {
        return label;
    }

    public int order() {
        return order;
    }

    public static SuggestionType fromLabel(String label) {
        return Arrays.stream(values())
                .filter(t -> t.label.equals(label))
                .findFirst()
                .orElse(OTHER);
    }
}
