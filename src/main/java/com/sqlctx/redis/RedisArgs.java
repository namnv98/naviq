package com.sqlctx.redis;

import com.sqlctx.util.I18n;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Tách 1 dòng thành tham số đúng như redis-cli (sdssplitargs): cách nhau bởi khoảng trắng; "..." hiểu
 * \n \r \t \b \a \\ \" \xHH; '...' chỉ hiểu \'; sau dấu nháy đóng phải là khoảng trắng hoặc hết dòng.
 * Trả byte[] vì \xHH tạo được byte không phải UTF-8.
 */
public final class RedisArgs {

    private RedisArgs() {
    }

    public static List<byte[]> split(String line) {
        List<byte[]> args = new ArrayList<>();
        int i = 0;
        int n = line.length();
        while (true) {
            while (i < n && Character.isWhitespace(line.charAt(i))) {
                i++;
            }
            if (i >= n) {
                return args;
            }
            ByteArrayOutputStream cur = new ByteArrayOutputStream();
            boolean inDouble = false;
            boolean inSingle = false;
            boolean done = false;
            while (!done) {
                if (i >= n) {
                    if (inDouble || inSingle) {
                        throw new IllegalArgumentException(I18n.t("args.unclosedQuote"));
                    }
                    break;
                }
                char c = line.charAt(i);
                if (inDouble) {
                    if (c == '\\' && i + 3 < n && line.charAt(i + 1) == 'x'
                            && isHex(line.charAt(i + 2)) && isHex(line.charAt(i + 3))) {
                        cur.write(Integer.parseInt(line.substring(i + 2, i + 4), 16));
                        i += 3;
                    } else if (c == '\\' && i + 1 < n) {
                        i++;
                        char e = line.charAt(i);
                        switch (e) {
                            case 'n' -> cur.write('\n');
                            case 'r' -> cur.write('\r');
                            case 't' -> cur.write('\t');
                            case 'b' -> cur.write('\b');
                            case 'a' -> cur.write(7);
                            default -> i = writeUtf8(cur, line, i);
                        }
                    } else if (c == '"') {
                        if (i + 1 < n && !Character.isWhitespace(line.charAt(i + 1))) {
                            throw new IllegalArgumentException(I18n.t("args.spaceAfterQuote"));
                        }
                        done = true;
                    } else {
                        i = writeUtf8(cur, line, i);
                    }
                } else if (inSingle) {
                    if (c == '\\' && i + 1 < n && line.charAt(i + 1) == '\'') {
                        i++;
                        cur.write('\'');
                    } else if (c == '\'') {
                        if (i + 1 < n && !Character.isWhitespace(line.charAt(i + 1))) {
                            throw new IllegalArgumentException(I18n.t("args.spaceAfterQuote"));
                        }
                        done = true;
                    } else {
                        i = writeUtf8(cur, line, i);
                    }
                } else {
                    if (Character.isWhitespace(c)) {
                        done = true;
                    } else if (c == '"') {
                        inDouble = true;
                    } else if (c == '\'') {
                        inSingle = true;
                    } else {
                        i = writeUtf8(cur, line, i);
                    }
                }
                i++;
            }
            args.add(cur.toByteArray());
        }
    }

    /** Tách --command thành nhiều lệnh: theo xuống dòng và ';' nằm ngoài dấu nháy. */
    public static List<String> splitCommands(String text) {
        List<String> commands = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                cur.append(c);
                if (c == '\\' && i + 1 < text.length()) {
                    cur.append(text.charAt(++i));
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                cur.append(c);
            } else if (c == ';' || c == '\n') {
                addIfNotBlank(commands, cur);
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        addIfNotBlank(commands, cur);
        return commands;
    }

    /** Dòng của file lệnh (--file, \i): bỏ dòng trống và dòng bắt đầu bằng '#'. */
    public static List<String> fileCommands(String content) {
        return content.lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
    }

    private static void addIfNotBlank(List<String> commands, StringBuilder cur) {
        String s = cur.toString().strip();
        if (!s.isEmpty()) {
            commands.add(s);
        }
    }

    private static boolean isHex(char c) {
        return Character.digit(c, 16) >= 0;
    }

    /** Ghi ký tự tại {@code i} (cả cặp surrogate nếu có, vd emoji), trả về index ký tự cuối đã đọc. */
    private static int writeUtf8(ByteArrayOutputStream out, String line, int i) {
        char c = line.charAt(i);
        if (c < 0x80) {
            out.write(c);
            return i;
        }
        int end = Character.isHighSurrogate(c) && i + 1 < line.length() ? i + 2 : i + 1;
        out.writeBytes(line.substring(i, end).getBytes(StandardCharsets.UTF_8));
        return end - 1;
    }

    public static String text(byte[] arg) {
        return new String(arg, StandardCharsets.UTF_8);
    }
}
