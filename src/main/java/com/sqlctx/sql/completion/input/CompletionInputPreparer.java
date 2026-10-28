package com.sqlctx.sql.completion.input;

public class CompletionInputPreparer {
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
        // "select *|" / ", *|": "*" là thứ sắp được thay bằng danh sách cột (SuggestionSupport.addStarExpansion)
        if (isSelectListStar(s, cursor)) {
            return "*";
        }
        int i = cursor - 1;
        while (i >= 0 && isIdentifier(s.charAt(i))) {
            i--;
        }
        return s.substring(i + 1, cursor);
    }

    /**
     * Caret ngay sau "*" (hoặc "alias.*") đứng sau SELECT/DISTINCT hoặc dấu phẩy - không phải phép nhân. Tiền tố
     * luôn chỉ là "*": với "u.*" phần "u." giữ nguyên trong câu, gợi ý tự bắt đầu bằng tên cột trần.
     */
    public static boolean isSelectListStar(String s, int cursor) {
        if (cursor < 1 || cursor > s.length() || s.charAt(cursor - 1) != '*') {
            return false;
        }
        String before = s.substring(0, cursor - 1);
        String qualifier = starQualifier(s, cursor);
        if (qualifier != null) {
            before = before.substring(0, before.length() - qualifier.length() - 1);
        }
        before = before.stripTrailing();
        if (before.endsWith(",")) {
            return true;
        }
        int wordStart = before.length();
        while (wordStart > 0 && Character.isLetter(before.charAt(wordStart - 1))) {
            wordStart--;
        }
        String word = before.substring(wordStart);
        return word.equalsIgnoreCase("select") || word.equalsIgnoreCase("distinct");
    }

    /** "u.*|" -> "u"; null nếu "*" không đứng ngay sau "định_danh.". */
    public static String starQualifier(String s, int cursor) {
        if (cursor < 3 || s.charAt(cursor - 1) != '*' || s.charAt(cursor - 2) != '.') {
            return null;
        }
        int start = cursor - 2;
        while (start > 0 && (Character.isLetterOrDigit(s.charAt(start - 1)) || s.charAt(start - 1) == '_')) {
            start--;
        }
        return start == cursor - 2 ? null : s.substring(start, cursor - 2);
    }

    private static boolean isIdentifier(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.';
    }
}
