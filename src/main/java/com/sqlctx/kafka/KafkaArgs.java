package com.sqlctx.kafka;

import com.sqlctx.util.I18n;

import com.sqlctx.kafka.KafkaTools.Arity;
import com.sqlctx.kafka.KafkaTools.Opt;
import com.sqlctx.kafka.KafkaTools.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tách dòng lệnh như shell rồi đối chiếu với {@link KafkaTools}: chế độ, tuỳ chọn, giá trị, lỗi cú pháp, tuỳ chọn
 * bắt buộc còn thiếu. Dùng chung cho chạy lệnh, tô màu, gợi ý và dòng hướng dẫn.
 */
public final class KafkaArgs {

    private KafkaArgs() {
    }

    /** 1 từ của dòng lệnh: vị trí [start, end) trên dòng gốc, chữ sau khi bỏ nháy/escape. */
    public record Token(String raw, int start, int end, String text, boolean quoted) {
    }

    /**
     * Tách như sh: khoảng trắng; '...' giữ nguyên; "..." hiểu \" \\ \$ \`; '\' ngoài nháy escape ký tự sau;
     * '\' + xuống dòng là nối dòng (dán lệnh nhiều dòng từ tài liệu). Nháy chưa đóng thì kéo tới hết dòng (đang gõ).
     */
    public static List<Token> tokenize(String line) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        int n = line.length();
        while (i < n) {
            char c = line.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '\\' && i + 1 < n && line.charAt(i + 1) == '\n') {
                i += 2;
                continue;
            }
            int start = i;
            StringBuilder text = new StringBuilder();
            boolean quoted = false;
            while (i < n && !Character.isWhitespace(line.charAt(i))) {
                c = line.charAt(i);
                if (c == '\'') {
                    quoted = true;
                    int close = line.indexOf('\'', i + 1);
                    int end = close < 0 ? n : close;
                    text.append(line, i + 1, end);
                    i = close < 0 ? n : close + 1;
                } else if (c == '"') {
                    quoted = true;
                    i++;
                    while (i < n && line.charAt(i) != '"') {
                        char d = line.charAt(i);
                        if (d == '\\' && i + 1 < n && "\"\\$`\n".indexOf(line.charAt(i + 1)) >= 0) {
                            if (line.charAt(i + 1) != '\n') {
                                text.append(line.charAt(i + 1));
                            }
                            i += 2;
                        } else {
                            text.append(d);
                            i++;
                        }
                    }
                    i = Math.min(n, i + 1);
                } else if (c == '\\' && i + 1 < n) {
                    if (line.charAt(i + 1) != '\n') {
                        text.append(line.charAt(i + 1));
                    }
                    i += 2;
                } else {
                    text.append(c);
                    i++;
                }
            }
            out.add(new Token(line.substring(start, i), start, i, text.toString(), quoted));
        }
        return out;
    }

    // ------------------------------------------------------------------ đối chiếu với bảng tool

    public enum Role {
        TOOL, UNKNOWN_TOOL, MODE, OPTION, VALUE, ERROR
    }

    public record Problem(int token, String message) {
    }

    /**
     * {@code values}: tuỳ chọn -> các giá trị đã gõ (cờ: danh sách rỗng). {@code roles}/{@code kinds}: theo từng
     * token (kinds: loại giá trị nếu token là giá trị). {@code pending}: tuỳ chọn cuối dòng đang chờ giá trị.
     */
    public record Parsed(Tool tool, String mode, Map<String, List<String>> values, List<Token> tokens,
                         List<Role> roles, List<String> kinds, List<Problem> problems, Opt pending) {

        public boolean has(String option) {
            return values.containsKey(option);
        }

        public String get(String option) {
            List<String> v = values.get(option);
            return v == null || v.isEmpty() ? null : v.get(v.size() - 1);
        }

        public List<String> all(String option) {
            return values.getOrDefault(option, List.of());
        }

        /** Các nhóm tuỳ chọn bắt buộc chưa có cái nào (vd [--group, --all-groups]). */
        public List<List<String>> missing() {
            List<List<String>> out = new ArrayList<>();
            if (tool == null) {
                return out;
            }
            if (tool.hasModes() && mode == null) {
                out.add(tool.modes());
                return out;
            }
            for (List<String> group : tool.requires().getOrDefault(mode == null ? "" : mode, List.of())) {
                if (group.stream().noneMatch(values::containsKey)) {
                    out.add(group);
                }
            }
            return out;
        }
    }

    public static Parsed parse(String line) {
        List<Token> tokens = tokenize(line);
        List<Role> roles = new ArrayList<>();
        List<String> kinds = new ArrayList<>();
        List<Problem> problems = new ArrayList<>();
        Map<String, List<String>> values = new LinkedHashMap<>();
        if (tokens.isEmpty()) {
            return new Parsed(null, null, values, tokens, roles, kinds, problems, null);
        }
        Tool tool = KafkaTools.tool(tokens.get(0).text());
        if (tool == null) {
            roles.add(Role.UNKNOWN_TOOL);
            kinds.add(null);
            problems.add(new Problem(0, I18n.t("kafka.args.unknownTool", tokens.get(0).text())));
            for (int i = 1; i < tokens.size(); i++) {
                roles.add(Role.VALUE);
                kinds.add(null);
            }
            return new Parsed(null, null, values, tokens, roles, kinds, problems, null);
        }
        roles.add(Role.TOOL);
        kinds.add(null);
        String mode = null;
        Opt pending = null;
        for (int i = 1; i < tokens.size(); i++) {
            String word = tokens.get(i).text();
            if (pending != null) {
                boolean looksLikeOption = word.startsWith("--") && !tokens.get(i).quoted();
                if (pending.arity() == Arity.VALUE && looksLikeOption) {
                    problems.add(new Problem(i - 1, I18n.t("kafka.args.missingValue", pending.name())));
                    pending = null;
                } else if (pending.arity() == Arity.OPTIONAL_VALUE && looksLikeOption) {
                    pending = null;
                } else {
                    values.get(pending.name()).add(word);
                    roles.add(Role.VALUE);
                    kinds.add(pending.kind());
                    pending = null;
                    continue;
                }
            }
            if (tool.modes().contains(word) && (word.startsWith("--") || i == 1)) {
                if (mode != null && !mode.equals(word)) {
                    problems.add(new Problem(i, I18n.t("kafka.args.oneMode", String.join(", ", tool.modes()), mode)));
                    roles.add(Role.ERROR);
                } else {
                    mode = word;
                    roles.add(Role.MODE);
                }
                kinds.add(null);
                continue;
            }
            String name = word;
            String inline = null;
            int eq = word.indexOf('=');
            if (word.startsWith("--") && eq > 2) { // --topic=orders
                name = word.substring(0, eq);
                inline = word.substring(eq + 1);
            }
            Opt opt = word.startsWith("--") ? tool.option(name) : null;
            if (opt == null) {
                problems.add(new Problem(i, word.startsWith("--")
                        ? I18n.t("kafka.args.unknownOption", tool.name(), name)
                        : tool.positionalModes() && i == 1
                        ? I18n.t("kafka.args.subcommand", String.join(" | ", tool.modes()))
                        : I18n.t("kafka.args.stray", word)));
                roles.add(Role.ERROR);
                kinds.add(null);
                continue;
            }
            if (mode != null && !opt.modes().isEmpty() && !opt.modes().contains(mode)) {
                problems.add(new Problem(i, I18n.t("kafka.args.wrongMode", opt.name(), mode, String.join(", ", opt.modes()))));
                roles.add(Role.ERROR);
                kinds.add(null);
                continue;
            }
            if (values.containsKey(opt.name()) && !opt.repeatable() && opt.arity() == Arity.FLAG) {
                problems.add(new Problem(i, I18n.t("kafka.args.duplicate", opt.name())));
            }
            values.computeIfAbsent(opt.name(), k -> new ArrayList<>());
            roles.add(Role.OPTION);
            kinds.add(null);
            if (inline != null) {
                values.get(opt.name()).add(inline);
            } else if (opt.arity() != Arity.FLAG) {
                pending = opt;
            }
        }
        // tuỳ chọn đứng trước chế độ (--topic x --describe): kiểm tra lại khi đã biết chế độ
        if (mode != null) {
            for (int i = 1; i < tokens.size(); i++) {
                if (roles.get(i) != Role.OPTION) {
                    continue;
                }
                String name = tokens.get(i).text().split("=", 2)[0];
                Opt opt = tool.option(name);
                if (opt != null && !opt.modes().isEmpty() && !opt.modes().contains(mode)) {
                    problems.add(new Problem(i, I18n.t("kafka.args.wrongMode", opt.name(), mode, String.join(", ", opt.modes()))));
                    roles.set(i, Role.ERROR);
                }
            }
        }
        problems.sort(java.util.Comparator.comparingInt(Problem::token));
        return new Parsed(tool, mode, values, tokens, roles, kinds, problems, pending);
    }
}
