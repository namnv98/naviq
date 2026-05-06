package com.naviq.cli;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.jline.builtins.Less;
import org.jline.builtins.Options;
import org.jline.builtins.Source;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

public class JLineBuiltinLessDemo {

    private static String noWrap(String content, int width) {
        StringBuilder sb = new StringBuilder();

        for (String line : content.split("\n")) {
            while (line.length() > width) {
                sb.append(line, 0, width).append("\n");
                line = line.substring(width);
            }
            sb.append(line).append("\n");
        }

        return sb.toString();
    }

    public static void main(String[] args) throws Exception {

        Terminal terminal = TerminalBuilder.builder()
            .system(true)
            .build();

        Less less =
            new Less(
                terminal,
                Paths.get("."),
                Options.compile(Less.usage()).parse(List.of("--chop-long-lines")));

        String content = """
            +---------------+----------+-------------+----------+---------+--------------+---------------------------------------------------------------------------------------------------------------------------------------+----------+-----------+
            | usename       | usesysid | usecreatedb | usesuper | userepl | usebypassrls | passwd                                                                                                                                | valuntil | useconfig |
            +---------------+----------+-------------+----------+---------+--------------+---------------------------------------------------------------------------------------------------------------------------------------+----------+-----------+
            | devops        | 10       | true        | true     | true    | true         | SCRAM-SHA-256$4096:qo4Ix8xXwTKIcQhN98BHtw==$mo6h55Xu5YhMZbBnI2rFjzxx636q2opJriTNAvQ2scE=:gxM2O/zwRsMWwvsacxBXP+yiXRgTy7/dVcvGVkoE3hM= | <null>   | <null>    |
            | readonly_user | 82954    | false       | false    | false   | false        | SCRAM-SHA-256$4096:rGWcwb982QWtdXzEtVqUfA==$dD85qztBpo2pn4nzLVim4YwB/SKI+Xb+9aYFKUsaEPo=:DJk8KpQVlmRwSUoJZdr0mvElWjNCvHWgjIHDjVTl5oE= | <null>   | <null>    |
            +---------------+----------+-------------+----------+---------+--------------+---------------------------------------------------------------------------------------------------------------------------------------+----------+-----------+
            """;

        String processed = noWrap(content, 88888);

        Source source = new Source.InputStreamSource(
            new ByteArrayInputStream(processed.getBytes(StandardCharsets.UTF_8)),
            true,
            "content"
        );

        List<Source> sources = new ArrayList<>();
        sources.add(source);

        less.run(sources);

        terminal.close();
    }
}