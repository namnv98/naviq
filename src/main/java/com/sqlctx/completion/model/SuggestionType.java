package com.sqlctx.completion.model;

import java.util.Arrays;

public enum SuggestionType {
    ALIAS("alias", 1),
    COLUMN("column", 2),
    TABLE("table", 3),
    KEYWORD("keyword", 4),
    VIEW("view", 5),
    FUNCTION("function", 6),
    DATATYPE("datatype", 7),
    MATERIALIZED_VIEW("materialized view", 99),
    SCHEMA("schema", 99),
    OTHER("other", 99),
    COMMAND("command", 0),
    DATABASE("database", 1);

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
