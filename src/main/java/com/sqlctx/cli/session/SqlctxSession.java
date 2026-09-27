package com.sqlctx.cli.session;

import com.sqlctx.datasource.ConnectionProfile;
import com.sqlctx.dialect.DialectAdapters;
import com.sqlctx.schema.Dialect;
import org.jline.reader.LineReader;

import java.nio.file.Path;

/**
 * State dùng chung xuyên suốt 1 phiên CLI (context/kết nối đang dùng, \o đang bật, câu lệnh gần nhất...)
 * và vài helper nhỏ mà hầu hết package khác (cli.command, cli.repl...) đều cần - tách khỏi orchestrator
 * (SqlctxCli, chỉ nên lo main()/REPL loop/keymap) để các phần xử lý từng lệnh không phải "biết" về nhau,
 * chỉ cần biết SqlctxSession.
 */
public final class SqlctxSession {

    private SqlctxSession() {
    }

    public static volatile boolean MULTI_LINE = false;
    public static volatile boolean quitRequested = false;
    /** true khi chạy --file/--command (không có TTY thật, không cần vẽ/xoá màn hình gì cả). */
    public static volatile boolean BATCH_MODE = false;

    /** Cách in lại kết quả gần nhất (dùng khi đổi cỡ cửa sổ). */
    public static volatile Runnable lastOutput;

    /** Câu lệnh gần nhất đã chạy (kể cả chạy lỗi) - dùng cho \e (mở $EDITOR sửa lại). */
    public static volatile String lastStatement = "";

    /** \o <file> đang bật - null nghĩa là không xuất file, chỉ in ra màn hình như bình thường. */
    public static volatile Path outputFile;
    /** "csv" | "json" | "text" - suy ra từ đuôi file lúc \o <file>. csv/json là xuất 1 lần (tự tắt sau khi
     *  ghi xong câu lệnh kế tiếp); text là ghi nối tiếp (log liên tục) cho tới khi người dùng gõ \o tắt. */
    public static volatile String outputFormat;

    /** Tên context (kiểu kubectx) đang dùng - null nếu phiên này khởi động bằng --host=/... tay, không qua --context=. */
    public static volatile String currentContextName = null;
    /**
     * Connection ĐANG SỐNG ngay TRƯỚC lần "\ctx" gần nhất - chụp lại TOÀN BỘ thông số (không chỉ tên),
     * để "\ctx -" quay lại được đúng chỗ ngay cả khi chỗ đó chưa từng có tên (vd connection lúc mới mở CLI).
     */
    public static volatile ConnectionProfile previousConnection = null;

    public static final String RED = "\u001b[31m";
    public static final String YELLOW = "\u001b[33m";
    public static final String RESET = "\u001b[0m";
    public static final String DIM = "\u001b[38;5;245m";
    public static final String TITLE_COLOR = "\u001b[1;36m";
    public static final String GREEN = "\u001b[1;32m";

    public static void say(LineReader reader, String text) {
        reader.printAbove(text);
        lastOutput = () -> {
            var out = reader.getTerminal().writer();
            out.print(text);
            if (!text.endsWith("\n")) {
                out.print("\n");
            }
        };
    }

    public static java.sql.Connection connection(Dialect dialect) {
        return DialectAdapters.of(dialect).connection();
    }
}
