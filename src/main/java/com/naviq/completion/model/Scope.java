package com.naviq.completion.model;


import java.util.*;

public class Scope {
    public int openIdx, closeIdx;
    public int lastConsumedIdx;          // token cuối đã đọc (để nhảy alias)
    public final Scope parent;
    public final List<Scope> children = new ArrayList<>();
    public final Map<String, String> realTables = new LinkedHashMap<>();     // alias -> schema.table
    public final Map<String, List<ColumnInfo>> virtualTables = new LinkedHashMap<>(); // alias -> columns
    public String pendingCteName;        // tên CTE nếu scope này là body CTE

    public Scope(int openIdx, int closeIdx, Scope parent) {
        this.openIdx = openIdx;
        this.closeIdx = closeIdx;
        this.parent = parent;
        this.lastConsumedIdx = openIdx;
    }

    public boolean contains(int tokenIdx) {
        return tokenIdx > openIdx && tokenIdx < closeIdx;
    }
}