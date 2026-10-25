package com.sqlctx.redis;

import org.jline.reader.Highlighter;
import org.jline.reader.LineReader;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.util.regex.Pattern;

/**
 * Tô màu theo VAI TRÒ trong cây lệnh (lệnh, tuỳ chọn, key, field/member, chuỗi, số), không chỉ theo chữ:
 * "SET get value" thì "get" là key, không phải từ khoá GET. Token sai cú pháp đầu tiên gạch chân đỏ.
 * Bảng màu giống SqlHighlighter (key = màu bảng, field = màu cột).
 */
public final class RedisHighlighter implements Highlighter {

    private static final int KEYWORD = 75;
    private static final int KEY = 214;
    private static final int FIELD = 152;
    private static final int UNKNOWN_COMMAND = 117;
    private static final int STRING = 114;
    private static final int NUMBER = 141;
    private static final int ERROR = 203;

    @Override
    public AttributedString highlight(LineReader reader, String buffer) {
        return highlight(buffer);
    }

    static AttributedString highlight(String buffer) {
        AttributedStringBuilder sb = new AttributedStringBuilder();
        String stripped = buffer.stripLeading();
        if (stripped.startsWith("\\")) {
            int lead = buffer.length() - stripped.length();
            int end = lead;
            while (end < buffer.length() && !Character.isWhitespace(buffer.charAt(end))) {
                end++;
            }
            sb.append(buffer.substring(0, lead));
            sb.style(AttributedStyle.BOLD.foreground(KEYWORD)).append(buffer.substring(lead, end));
            sb.style(AttributedStyle.DEFAULT).append(buffer.substring(end));
            return sb.toAttributedString();
        }

        RedisSyntax.Inspection inspection = RedisSyntax.inspect(buffer);
        int pos = 0;
        for (int i = 0; i < inspection.tokens().size(); i++) {
            RedisSyntax.Tok t = inspection.tokens().get(i);
            int start = t.start();
            int stop = t.end();
            sb.style(AttributedStyle.DEFAULT).append(buffer.substring(pos, start));
            AttributedStyle style = style(i < inspection.roles().size() ? inspection.roles().get(i) : null);
            if (start == inspection.errorOffset()) {
                style = style.underline().foreground(ERROR);
            }
            sb.style(style).append(buffer.substring(start, stop));
            pos = stop;
        }
        sb.style(AttributedStyle.DEFAULT).append(buffer.substring(pos));
        return sb.toAttributedString();
    }

    private static AttributedStyle style(RedisSyntax.Role role) {
        if (role == null) {
            return AttributedStyle.DEFAULT;
        }
        return switch (role) {
            case COMMAND -> AttributedStyle.BOLD.foreground(KEYWORD);
            case KEYWORD -> AttributedStyle.DEFAULT.foreground(KEYWORD);
            case UNKNOWN_COMMAND -> AttributedStyle.DEFAULT.foreground(UNKNOWN_COMMAND);
            case KEY -> AttributedStyle.DEFAULT.foreground(KEY);
            case FIELD -> AttributedStyle.DEFAULT.foreground(FIELD);
            case STRING -> AttributedStyle.DEFAULT.foreground(STRING);
            case NUMBER -> AttributedStyle.DEFAULT.foreground(NUMBER);
            case VALUE -> AttributedStyle.DEFAULT;
        };
    }

    @Override
    public void setErrorPattern(Pattern errorPattern) {
    }

    @Override
    public void setErrorIndex(int errorIndex) {
    }
}
