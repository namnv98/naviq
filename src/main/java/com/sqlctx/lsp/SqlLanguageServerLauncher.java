package com.sqlctx.lsp;

import com.sqlctx.schema.Dialect;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;

/** {@code sqlctx --lsp}: chạy Language Server qua stdin/stdout cho tới khi editor gửi exit. */
public final class SqlLanguageServerLauncher {

    private SqlLanguageServerLauncher() {
    }

    /** @param startupError lỗi cấu hình/kết nối lúc khởi động (null nếu không có) - báo cho editor thay vì thoát */
    public static void run(Dialect dialect, String startupError) throws Exception {
        InputStream in = System.in;
        OutputStream out = System.out;
        // stdout chỉ dành cho JSON-RPC: lỡ có dòng System.out.println nào (driver, thư viện) là hỏng giao thức
        System.setOut(new PrintStream(System.err, true));

        SqlLanguageServer server = new SqlLanguageServer(dialect, startupError);
        Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(server, in, out);
        server.connect(launcher.getRemoteProxy());
        launcher.startListening().get();
    }
}
