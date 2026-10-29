package com.sqlctx.net;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Định dạng số liệu (byte, thời gian) và màu ANSI - màu tự tắt khi stdout không phải terminal (ghi ra file, pipe). */
final class NetFormat {

    static boolean color = System.getenv("NO_COLOR") == null && stdoutIsTerminal();

    private NetFormat() {
    }

    /** stdout là terminal thật (không phải pipe / file). */
    static boolean stdoutIsTerminal() {
        try {
            String target = Files.readSymbolicLink(Path.of("/proc/self/fd/1")).toString();
            return target.startsWith("/dev/pts/") || target.startsWith("/dev/tty");
        } catch (IOException | UnsupportedOperationException e) {
            return System.console() != null;
        }
    }

    private static String c(String code, String s) {
        return color ? "\u001b[" + code + "m" + s + "\u001b[0m" : s;
    }

    static String red(String s) {
        return c("1;31", s);
    }

    static String green(String s) {
        return c("32", s);
    }

    static String yellow(String s) {
        return c("33", s);
    }

    static String blue(String s) {
        return c("36", s);
    }

    static String magenta(String s) {
        return c("35", s);
    }

    static String bold(String s) {
        return c("1", s);
    }

    static String dim(String s) {
        return c("2", s);
    }

    static String bytes(long n) {
        if (n < 1024) {
            return n + " B";
        }
        if (n < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", n / 1024.0);
        }
        if (n < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", n / 1048576.0);
        }
        return String.format(Locale.ROOT, "%.2f GB", n / 1073741824.0);
    }

    static String duration(double seconds) {
        if (seconds < 0.001) {
            return String.format(Locale.ROOT, "%.0f µs", seconds * 1e6);
        }
        if (seconds < 1) {
            return String.format(Locale.ROOT, "%.0f ms", seconds * 1000);
        }
        if (seconds < 60) {
            return String.format(Locale.ROOT, "%.1fs", seconds);
        }
        return String.format(Locale.ROOT, "%dm%02ds", (long) seconds / 60, (long) seconds % 60);
    }

    /** IPv6 dạng ngắn như ip/ss in ({@code 0:0:0:0:0:0:0:1} → {@code ::1}); IPv4 giữ nguyên. */
    static String compactIp(String ip) {
        ip = ip.replaceAll("%.*$", "");
        if (!ip.contains(":") || ip.contains("::")) {
            return ip;
        }
        String[] g = ip.split(":");
        int bestStart = -1;
        int bestLen = 1;
        for (int i = 0; i < g.length; ) {
            int j = i;
            while (j < g.length && g[j].replaceFirst("^0+", "").isEmpty()) {
                j++;
            }
            if (j - i > bestLen) {
                bestStart = i;
                bestLen = j - i;
            }
            i = j == i ? i + 1 : j;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < g.length; i++) {
            if (i == bestStart) {
                sb.append("::");
                i += bestLen - 1;
                continue;
            }
            if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != ':') {
                sb.append(':');
            }
            String h = g[i].replaceFirst("^0+(?=.)", "");
            sb.append(h.toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    /** Cắt/đệm cho vừa đúng {@code width} ký tự (để các cột thẳng hàng). */
    static String fit(String s, int width) {
        if (s.length() > width) {
            return s.substring(0, width - 1) + "…";
        }
        return s + " ".repeat(width - s.length());
    }
}
