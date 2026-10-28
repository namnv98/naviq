package com.sqlctx.cli;

import com.sqlctx.util.I18n;

import com.sqlctx.context.ConnectionProfileStore;
import com.sqlctx.sql.dialect.DialectAdapter;
import com.sqlctx.sql.dialect.DialectAdapters;
import com.sqlctx.sql.schema.Dialect;
import com.sqlctx.sql.schema.SchemaIndex;
import org.jline.reader.LineReader;

import static com.sqlctx.sql.repl.SqlctxSession.DIM;
import static com.sqlctx.sql.repl.SqlctxSession.GREEN;
import static com.sqlctx.sql.repl.SqlctxSession.RESET;
import static com.sqlctx.sql.repl.SqlctxSession.TITLE_COLOR;

/** In banner khởi động (tên/version, thông tin server, schema đã nạp) - hỏi DB vài câu 1 lần lúc mở CLI. */
public final class StartupBanner {

    private StartupBanner() {
    }

    public static void print(LineReader reader, Dialect dialect) {
        DialectAdapter adapter = DialectAdapters.of(dialect);
        String clientZone = java.time.ZoneId.systemDefault().toString();
        String serverZone = adapter.serverTimezone();
        String serverVersion = adapter.serverVersion();
        String serverTime = adapter.serverTime();
        String encoding = adapter.encoding();
        String driver = adapter.driverInfo();

        String host = System.getProperty("DB_HOST");
        String port = System.getProperty("DB_PORT");
        String db = System.getProperty("DB_DBNAME");
        String user = System.getProperty("DB_USER");

        int schemaCount = SchemaIndex.schemas.size();
        int tableCount = SchemaIndex.schemaTableIndex.size();
        int columnCount = SchemaIndex.schemaTableIndex.values().stream().mapToInt(t -> t.columns().size()).sum();

        String info =
            TITLE_COLOR + I18n.t("banner.sql.title") + RESET + "\n" +
                "\n" +
                label("banner.connected") + GREEN + user + "@" + host + ":" + port + "/" + db + " (" + dialect + ")" + RESET + "\n" +
                label("banner.config") + ConnectionProfileStore.configDir() + "\n" +
                label("banner.server") + serverVersion + "\n" +
                label("banner.driver") + driver + "\n" +
                label("banner.encoding") + encoding + "\n" +
                label("banner.time") + I18n.t("banner.timeValue", serverTime, serverZone, DIM, RESET, clientZone) + "\n" +
                "\n" +
                // schema nạp ở nền lúc khởi động (SchemaIndex.reloadInBackground) - chưa xong thì chưa có số liệu
                (schemaCount == 0
                        ? label("banner.schema") + I18n.t("banner.schemaLoading") + "\n"
                        : label("banner.schema") + I18n.t("banner.schemaCounts", schemaCount, tableCount, columnCount) + "\n" +
                          label("banner.suggest") + I18n.t("banner.suggestCounts", SchemaIndex.functions.size(), SchemaIndex.dataTypes.size()) + "\n");

        reader.printAbove(info);
    }

    /** "Kết nối:    " - nhãn cột trái của banner, căn thẳng theo độ dài nhãn dài nhất (đổi theo ngôn ngữ). */
    public static String label(String key) {
        String text = I18n.t(key) + ":";
        return DIM + text + " ".repeat(Math.max(1, 12 - text.length())) + RESET;
    }
}
