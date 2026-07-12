package com.naviq.postgresql.suggests;

public class CompletionInputPreparer {   // ───────────────────────────────────────────────────
    public record PrepareCompletionInput(String sql, int cursor, String prefix, boolean dotMode, String sqlSearch,
                                         int csrSearch) {

    }

    public static PrepareCompletionInput buildInput(String sql, int cursor) {
        String prefix = extractPrefix(sql, cursor);
        boolean dot = prefix.contains(".");

        String sqlSearch;
        int csrSearch;

        if (dot) {
            // cắt sql tại vị trí trước dấu chấm đầu tiên của prefix
            int dotPos = cursor - prefix.length() + prefix.indexOf('.');
            sqlSearch = sql.substring(0, dotPos + 1); // giữ lại "public."
            csrSearch = dotPos + 1;
        } else {
            sqlSearch = sql.substring(0, Math.max(0, cursor - prefix.length()));
            csrSearch = sqlSearch.length();
        }

        return new PrepareCompletionInput(extractCompletionPrefix(sql), cursor, prefix, dot, sqlSearch, csrSearch);
    }

    static String extractCompletionPrefix(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }

        // Chỉ 1 từ → không cần prefix
        if (input.trim().split("\\s+").length == 1 && !input.endsWith(" ")) {
            return "";
        }

        return input;
    }


    private static String extractPrefix(String s, int cursor) {
        int i = cursor - 1;
        while (i >= 0 && isIdentifier(s.charAt(i))) {
            i--;
        }
        return s.substring(i + 1, cursor);
    }

    private static boolean isIdentifier(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.';
    }
}
