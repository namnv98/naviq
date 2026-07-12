package com.naviq;

import com.naviq.completion.model.Suggest;
import com.naviq.oracle.suggests.CompletionEngine;

import java.util.List;

public class Main {
    public static void main(String[] args) {
        String sql = "select * from users where ";
        int cursor = sql.length();

        // GỌI ĐÚNG TẦNG orchestrator - đây là nơi bộ lọc isSoftKeywordNoise (đã thêm lượt trước)
        // thực sự chạy. OracleSQLSyntacticAnalyzer.analyze() (bản Main.java cũ) là tầng THẤP HƠN,
        // KHÔNG đi qua bộ lọc đó - nên thấy nguyên ~2283 token là đúng, không phải bug ở tầng đó.
        List<Suggest> suggests = CompletionEngine.suggests(sql, cursor);

        System.out.println();
        System.out.println("========== keyword ==========");
        suggests.stream()
                .filter(s -> s.getType().equals("keyword"))
                .forEach(s -> System.out.println(s.getKey()));
        System.out.println("(tổng số keyword: " + suggests.stream().filter(s -> s.getType().equals("keyword")).count() + ")");

        System.out.println();
        System.out.println("========== column ==========");
        suggests.stream()
                .filter(s -> s.getType().equals("column"))
                .forEach(s -> System.out.println(s.getKey()));

        System.out.println();
        System.out.println("========== function ==========");
        suggests.stream()
                .filter(s -> s.getType().equals("function"))
                .forEach(s -> System.out.println(s.getKey()));
    }
}