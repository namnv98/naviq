package com.sqlctx.cli.startup;

import com.sqlctx.datasource.ConnectionProfileStore;
import com.sqlctx.dialect.DialectAdapter;
import com.sqlctx.dialect.DialectAdapters;
import com.sqlctx.schema.Dialect;
import com.sqlctx.schema.SchemaIndex;
import org.jline.reader.LineReader;

import static com.sqlctx.cli.session.SqlctxSession.DIM;
import static com.sqlctx.cli.session.SqlctxSession.GREEN;
import static com.sqlctx.cli.session.SqlctxSession.RESET;
import static com.sqlctx.cli.session.SqlctxSession.TITLE_COLOR;

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
            TITLE_COLOR + "sqlctx CLI 1.0.0 — SQL completion & data browser" + RESET + "\n" +
                "\n" +
                DIM + "Connected:  " + RESET + GREEN + user + "@" + host + ":" + port + "/" + db + " (" + dialect + ")" + RESET + "\n" +
                DIM + "Config:     " + RESET + ConnectionProfileStore.configDir() + "\n" +
                DIM + "Server:     " + RESET + serverVersion + "\n" +
                DIM + "Driver:     " + RESET + driver + "\n" +
                DIM + "Encoding:   " + RESET + encoding + "\n" +
                DIM + "Time:       " + RESET + "server " + serverTime + " (" + serverZone + ")" + DIM + "  ·  " + RESET + "client " + clientZone + "\n" +
                "\n" +
                DIM + "Schema:     " + RESET + schemaCount + " schema(s), " + tableCount + " table/view(s), " + columnCount + " column(s) indexed\n" +
                DIM + "Suggest:    " + RESET + SchemaIndex.functions.size() + " function(s), " + SchemaIndex.dataTypes.size() + " data type(s)\n" +
                "\n" +
                DIM + "Home:       http://your-cli.dev" + RESET + "\n";

        reader.printAbove(info);
    }
}
