package com.naviq.completion.suggests.oracle;

import com.naviq.antlr4.oracle.*;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;

import java.util.List;
import java.util.Map;

/**
 * Đặt tên alias tự động (kiểu "orders" -> "o") + tìm tên bảng đứng trước "AS" tại vị trí cursor,
 * hoặc tên bảng vừa gõ xong (chưa có AS/alias).
 * <p>
 * PORT TỪ BẢN POSTGRES: đổi PostgreSQLParser -> PlSqlParser, DOT -> PERIOD.
 * <p>
 * BUG THẬT ĐÃ GẶP VÀ SỬA: bản đầu chỉ coi 1 token là "định danh hợp lệ" nếu type là
 * {@code REGULAR_ID} hoặc {@code DELIMITED_ID} — sai, vì Oracle có ~2500 "soft keyword" (vd
 * {@code USERS}, {@code TABLE}, {@code COLUMN}...) được LEX thành token RIÊNG của chính nó
 * (không phải REGULAR_ID), nhưng vẫn được rule {@code regular_id} (tầng parser, xem
 * PlSqlParser.g4: {@code regular_id : non_reserved_keywords_pre12c | ... | REGULAR_ID | ...})
 * chấp nhận làm định danh hợp lệ. Test thực tế "select * from users |" thất bại đúng vì "users"
 * tự nó là 1 token keyword {@code USERS}, không phải {@code REGULAR_ID}.
 * <p>
 * SỬA: dùng thẳng {@link PlSqlLexerBase#isIdentifier(int)} (đã có sẵn, liệt kê đầy đủ và chính
 * xác toàn bộ tập token này) thay vì tự check 2 loại — lấy lexer qua
 * {@code (PlSqlLexer) tokenStream.getTokenSource()}.
 */
public class AliasNameSuggester {
    public static String suggestAlias(Map<String, String> aliasMap, String tableName) {
        if (tableName == null || tableName.isEmpty()) {
            return null;
        }
        var keySet = aliasMap.keySet();
        int dot = tableName.lastIndexOf('.');
        if (dot != -1) {
            tableName = tableName.substring(dot + 1);
        }
        String[] parts = tableName.split("_");
        StringBuilder base = new StringBuilder();
        for (String p : parts) {
            if (!p.isEmpty()) {
                base.append(Character.toLowerCase(p.charAt(0)));
            }
        }
        if (base.length() == 0 && !tableName.isEmpty()) {
            base.append(Character.toLowerCase(tableName.charAt(0)));
        }
        String alias = base.toString();
        if (!keySet.contains(alias)) {
            return alias;
        }
        int i = 1;
        while (keySet.contains(alias + i)) {
            i++;
        }
        return alias + i;
    }

    static int skipHidden(List<Token> tokens, int from, int size) {
        while (from < size && tokens.get(from).getChannel() != Token.DEFAULT_CHANNEL) {
            from++;
        }
        return from;
    }

    public static String extractTableBeforeAs(CommonTokenStream tokenStream, int caretTokenIndex) {
        List<Token> tokens = tokenStream.getTokens();
        PlSqlLexer lexer = (PlSqlLexer) tokenStream.getTokenSource();
        int fromIdx = -1;
        for (int i = caretTokenIndex - 1; i >= 0; i--) {
            Token t = tokens.get(i);
            if (t.getChannel() != Token.DEFAULT_CHANNEL) {
                continue;
            }
            if (t.getType() == PlSqlParser.FROM) {
                fromIdx = i;
                break;
            }
        }
        if (fromIdx < 0) {
            return null;
        }
        int i = fromIdx + 1;
        while (i < caretTokenIndex) {
            Token t = tokens.get(i);
            if (t.getChannel() != Token.DEFAULT_CHANNEL) {
                i++;
                continue;
            }
            if (t.getType() == PlSqlParser.AS) {
                int next = skipHidden(tokens, i + 1, caretTokenIndex);
                if (next >= caretTokenIndex) {
                    return readTableNameBackward(tokens, i - 1, lexer);
                }
            }
            i++;
        }
        return null;
    }

    static String readTableNameBackward(List<Token> tokens, int idx, PlSqlLexer lexer) {
        StringBuilder sb = new StringBuilder();
        int i = idx;
        while (i >= 0) {
            Token t = tokens.get(i);
            if (t.getChannel() != Token.DEFAULT_CHANNEL) {
                i--;
                continue;
            }
            // SỬA: dùng lexer.isIdentifier() (đã có sẵn trong PlSqlLexerBase) thay vì chỉ check
            // REGULAR_ID/DELIMITED_ID - vì Oracle có ~2500 "soft keyword" (vd USERS, TABLE,
            // COLUMN...) được LEX thành token RIÊNG của chính nó (không phải REGULAR_ID), nhưng
            // vẫn được rule `regular_id` (tầng parser) chấp nhận làm định danh hợp lệ. Chỉ check
            // 2 loại REGULAR_ID/DELIMITED_ID sẽ bỏ sót toàn bộ nhóm này (bug thật đã gặp: "users"
            // tự nó là 1 keyword USERS, không phải REGULAR_ID).
            if (lexer.isIdentifier(t.getType())) {
                if (sb.length() > 0) {
                    sb.insert(0, ".");
                }
                sb.insert(0, t.getText());
            } else if (t.getType() == PlSqlParser.PERIOD) {
                // skip
            } else {
                break;
            }
            i--;
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * Xử lý case KHÁC HẲN {@link #extractTableBeforeAs} — chưa gõ {@code AS}, thậm chí chưa gõ
     * alias nào cả, caret đứng NGAY SAU tên bảng vừa gõ xong. Vd {@code "select * from users |"}
     * (caret sau khoảng trắng, ngay sau {@code users}) -> trả về {@code "users"}.
     * <p>
     * Đọc TIẾN từ ngay sau {@code FROM}, gom {@code identifier (PERIOD identifier)*} (schema.table),
     * rồi kiểm tra: nếu đã đọc hết đúng tới {@code caretTokenIndex} (không còn token thật nào khác
     * xen giữa, kể cả 1 alias đã gõ dở) thì mới coi là hợp lệ - nếu KHÔNG (vd đã có thêm 1 token
     * khác như alias/COMMA/JOIN trước khi chạm caret), trả {@code null} vì lúc này không còn đúng
     * ngữ cảnh "vừa gõ xong tên bảng, chưa gõ gì thêm" nữa.
     */
    public static String extractTableNameForImplicitAlias(CommonTokenStream tokenStream, int caretTokenIndex) {
        List<Token> tokens = tokenStream.getTokens();
        PlSqlLexer lexer = (PlSqlLexer) tokenStream.getTokenSource();

        int fromIdx = -1;
        for (int i = caretTokenIndex - 1; i >= 0; i--) {
            Token t = tokens.get(i);
            if (t.getChannel() != Token.DEFAULT_CHANNEL) {
                continue;
            }
            if (t.getType() == PlSqlParser.FROM) {
                fromIdx = i;
                break;
            }
            // Gặp COMMA/JOIN trước khi gặp FROM -> đang ở bảng thứ 2+ hoặc sau join, không phải
            // ngay-sau-FROM nữa -> không thuộc phạm vi hàm này, dừng sớm.
            if (t.getType() == PlSqlParser.COMMA || t.getType() == PlSqlParser.JOIN) {
                return null;
            }
        }
        if (fromIdx < 0) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        int i = fromIdx + 1;
        boolean expectIdentifier = true; // xen kẽ: identifier, PERIOD, identifier, PERIOD, ...
        while (i < caretTokenIndex) {
            Token t = tokens.get(i);
            if (t.getChannel() != Token.DEFAULT_CHANNEL) {
                i++;
                continue;
            }
            if (expectIdentifier) {
                // SỬA: dùng lexer.isIdentifier() thay vì chỉ check REGULAR_ID/DELIMITED_ID -
                // xem giải thích chi tiết ở readTableNameBackward.
                if (!lexer.isIdentifier(t.getType())) {
                    return null; // token đầu tiên sau FROM không phải tên bảng -> không áp dụng
                }
                if (sb.length() > 0) {
                    sb.append(".");
                }
                sb.append(t.getText());
                expectIdentifier = false;
            } else {
                if (t.getType() != PlSqlParser.PERIOD) {
                    // đã có thêm token khác (alias đã gõ dở, COMMA, JOIN...) TRƯỚC khi chạm caret
                    // -> không còn đúng ngữ cảnh "vừa gõ xong tên bảng, chưa gõ gì thêm" nữa.
                    return null;
                }
                expectIdentifier = true;
            }
            i++;
        }
        // Nếu dừng đúng lúc đang chờ PERIOD tiếp theo (vừa đọc xong 1 identifier) -> hợp lệ.
        // Nếu dừng khi đang chờ identifier (vd caret rơi ngay sau dấu "." chưa gõ gì) -> không đủ
        // thông tin để gợi ý alias, coi như không áp dụng.
        return !expectIdentifier && sb.length() > 0 ? sb.toString() : null;
    }
}