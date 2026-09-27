package com.sqlctx.completion.model;

public class Suggestion {
    private String key;
    private SuggestionType type;
    private String columnType;
    private int order;

    public Suggestion(String key, SuggestionType type, String columnType, int order) {
        this.key = key;
        this.type = type;
        this.columnType = columnType;
        this.order = order;
    }

    public Suggestion(String key, SuggestionType type, int order) {
        this(key, type, null, order);
    }

    public int getOrder() {
        return order;
    }

    public void setOrder(int order) {
        this.order = order;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public SuggestionType getType() {
        return type;
    }

    public void setType(SuggestionType type) {
        this.type = type;
    }

    public String getColumnType() {
        return columnType;
    }

    public void setColumnType(String columnType) {
        this.columnType = columnType;
    }

    public static Suggestion of(String value, SuggestionType type) {
        return new Suggestion(value, type, type.order());
    }

    public static Suggestion of(String value, SuggestionType type, String columnType) {
        return new Suggestion(value, type, columnType, type.order());
    }
}
