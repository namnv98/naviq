package com.naviq.model;


public class Suggest {
    private String key;
    private String type;
    private String columnType;
    private int order;

    public Suggest(String key, String type, String columnType, int order) {
        this.key = key;
        this.type = type;
        this.columnType = columnType;
        this.order = order;
    }

    public Suggest(String key, String type, int order) {
        this.key = key;
        this.type = type;
        this.order = order;
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

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getColumnType() {
        return columnType;
    }

    public void setColumnType(String columnType) {
        this.columnType = columnType;
    }

    public static Suggest of(String value, String type) {
        return new Suggest(value, type, orderOf(type));
    }

    public static Suggest of(String value, String type, String columnType) {
        return new Suggest(value, type, columnType, orderOf(type));
    }

    private static int orderOf(String type) {
        return switch (type) {
            case "alias" -> 1;
            case "column" -> 2;
            case "table" -> 3;
            case "keyword" -> 4;
            case "view" -> 5;
            case "function" -> 6;
            case "datatype" -> 7;
            default -> 99;
        };
    }

}
