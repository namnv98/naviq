package com.sqlctx.kafka;

import org.jline.reader.Highlighter;
import org.jline.reader.LineReader;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * Tô màu dòng lệnh kafka-*.sh theo vai trò: tool, chế độ/tuỳ chọn, giá trị theo loại (topic = màu bảng, group/
 * partition = màu cột như SqlHighlighter); tuỳ chọn sai hoặc không hợp với chế độ gạch chân đỏ.
 */
final class KafkaHighlighter implements Highlighter {

    private static final int KEYWORD = 75;
    private static final int TOPIC = 214;
    private static final int FIELD = 152;
    private static final int NUMBER = 141;
    private static final int STRING = 114;
    private static final int ERROR = 203;

    /** Đang ở chế độ nhập message của producer: dòng là dữ liệu, không tô. */
    private final BooleanSupplier plain;

    KafkaHighlighter(BooleanSupplier plain) {
        this.plain = plain;
    }

    @Override
    public AttributedString highlight(LineReader reader, String buffer) {
        return highlight(buffer, plain.getAsBoolean());
    }

    static AttributedString highlight(String buffer, boolean plain) {
        AttributedStringBuilder sb = new AttributedStringBuilder();
        String stripped = buffer.stripLeading();
        if (plain) {
            return sb.append(buffer).toAttributedString();
        }
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
        KafkaArgs.Parsed p = KafkaArgs.parse(buffer);
        int errorToken = p.problems().isEmpty() ? -1 : p.problems().get(0).token();
        int pos = 0;
        for (int i = 0; i < p.tokens().size(); i++) {
            KafkaArgs.Token t = p.tokens().get(i);
            sb.style(AttributedStyle.DEFAULT).append(buffer.substring(pos, t.start()));
            AttributedStyle style = style(p.roles().get(i), p.kinds().get(i), t);
            // đang gõ dở tên tool/tuỳ chọn ở cuối dòng: chưa coi là sai
            boolean typing = i == p.tokens().size() - 1 && t.end() == buffer.length();
            if (i == errorToken && !typing) {
                style = style.underline().foreground(ERROR);
            }
            sb.style(style).append(buffer.substring(t.start(), t.end()));
            pos = t.end();
        }
        sb.style(AttributedStyle.DEFAULT).append(buffer.substring(pos));
        return sb.toAttributedString();
    }

    private static final Pattern NUMERIC = Pattern.compile("-?\\d+");

    private static AttributedStyle style(KafkaArgs.Role role, String kind, KafkaArgs.Token t) {
        return switch (role) {
            case TOOL -> AttributedStyle.BOLD.foreground(KEYWORD);
            case MODE -> AttributedStyle.BOLD.foreground(KEYWORD);
            case OPTION -> AttributedStyle.DEFAULT.foreground(KEYWORD);
            case UNKNOWN_TOOL, ERROR -> AttributedStyle.DEFAULT;
            case VALUE -> {
                if ("topic".equals(kind) || "topicPartitions".equals(kind) || "topicList".equals(kind)) {
                    yield AttributedStyle.DEFAULT.foreground(TOPIC);
                }
                if ("group".equals(kind) || "subject".equals(kind) || "partition".equals(kind)) {
                    yield AttributedStyle.DEFAULT.foreground(FIELD);
                }
                if (t.quoted()) {
                    yield AttributedStyle.DEFAULT.foreground(STRING);
                }
                yield NUMERIC.matcher(t.text()).matches() ? AttributedStyle.DEFAULT.foreground(NUMBER) : AttributedStyle.DEFAULT;
            }
        };
    }

    @Override
    public void setErrorPattern(Pattern errorPattern) {
    }

    @Override
    public void setErrorIndex(int errorIndex) {
    }
}
