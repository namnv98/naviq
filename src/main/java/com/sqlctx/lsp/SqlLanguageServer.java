package com.sqlctx.lsp;

import com.sqlctx.util.I18n;

import com.sqlctx.completion.hint.InputHints;
import com.sqlctx.completion.input.CompletionInputPreparer;
import com.sqlctx.completion.model.Suggestion;
import com.sqlctx.completion.model.SuggestionType;
import com.sqlctx.dialect.DialectAdapters;
import com.sqlctx.format.SqlFormatter;
import com.sqlctx.schema.Dialect;
import com.sqlctx.schema.SchemaIndex;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Language Server cho file .sql: cùng engine gợi ý của REPL (gợi ý theo grammar + scope + schema thật), chữ ký hàm
 * khi gõ trong ngoặc, gạch lỗi cú pháp. Chạy qua stdio bằng {@code sqlctx --lsp --context=<tên>} - kết nối/schema
 * lấy từ context như REPL. Mỗi lần chỉ phân tích câu chứa caret ({@link StatementSlicer}).
 */
public class SqlLanguageServer implements LanguageServer, LanguageClientAware, TextDocumentService, WorkspaceService {

    static final String REFRESH_COMMAND = "sqlctx.refreshSchema";
    /** Chờ người dùng ngừng gõ một chút rồi mới báo lỗi cú pháp cả file. */
    private static final long DIAGNOSTICS_DELAY_MS = 300;

    private final Dialect dialect;
    private final Map<String, String> documents = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> pendingDiagnostics = new ConcurrentHashMap<>();
    private final ScheduledExecutorService diagnostics = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "lsp-diagnostics");
        t.setDaemon(true);
        return t;
    });
    private LanguageClient client;
    /** Lỗi cấu hình/kết nối lúc khởi động; khác null thì không nạp schema, chỉ còn gợi ý keyword. */
    private final String startupError;

    public SqlLanguageServer(Dialect dialect) {
        this(dialect, null);
    }

    public SqlLanguageServer(Dialect dialect, String startupError) {
        this.dialect = dialect;
        this.startupError = startupError;
    }

    @Override
    public void connect(LanguageClient client) {
        this.client = client;
    }

    // ── vòng đời ─────────────────────────────────────────────────────

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        ServerCapabilities caps = new ServerCapabilities();
        caps.setTextDocumentSync(TextDocumentSyncKind.Full);
        caps.setCompletionProvider(new CompletionOptions(false, List.of(".", " ", "(", ",", "*")));
        caps.setSignatureHelpProvider(new SignatureHelpOptions(List.of("(", ",")));
        caps.setExecuteCommandProvider(new ExecuteCommandOptions(List.of(REFRESH_COMMAND)));
        caps.setDocumentFormattingProvider(true);
        return CompletableFuture.completedFuture(new InitializeResult(caps, new ServerInfo("sqlctx", "1.0.0")));
    }

    @Override
    public void initialized(InitializedParams params) {
        if (startupError != null) {
            // vẫn chạy (gợi ý keyword, báo lỗi cú pháp) để editor không chỉ báo "server stopped" mà không rõ vì sao
            if (client != null) {
                client.showMessage(new MessageParams(MessageType.Error,
                        I18n.t("lsp.keywordsOnly", startupError)));
            }
            return;
        }
        loadSchema();
    }

    private void loadSchema() {
        SchemaIndex.reloadInBackground(dialect, error -> {
            if (client == null) {
                return;
            }
            client.logMessage(error == null
                    ? new MessageParams(MessageType.Info, "sqlctx: schema loaded, " + SchemaIndex.schemaTableIndex.size() + " tables")
                    : new MessageParams(MessageType.Error, "sqlctx: cannot load schema - " + error.getMessage()));
            documents.keySet().forEach(this::scheduleDiagnostics); // kiểu enum/bảng mới có thể đổi kết quả
        });
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        diagnostics.shutdownNow();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        System.exit(0);
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return this;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return this;
    }

    // ── đồng bộ nội dung file ────────────────────────────────────────

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        documents.put(params.getTextDocument().getUri(), params.getTextDocument().getText());
        scheduleDiagnostics(params.getTextDocument().getUri());
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        // đồng bộ Full: mỗi thay đổi mang nguyên nội dung mới
        List<TextDocumentContentChangeEvent> changes = params.getContentChanges();
        if (!changes.isEmpty()) {
            documents.put(params.getTextDocument().getUri(), changes.get(changes.size() - 1).getText());
            scheduleDiagnostics(params.getTextDocument().getUri());
        }
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        documents.remove(uri);
        ScheduledFuture<?> pending = pendingDiagnostics.remove(uri);
        if (pending != null) {
            pending.cancel(false);
        }
        if (client != null) {
            client.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()));
        }
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
    }

    // ── gợi ý ────────────────────────────────────────────────────────

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        return CompletableFuture.supplyAsync(() -> {
            String text = documents.getOrDefault(params.getTextDocument().getUri(), "");
            int offset = Positions.toOffset(text, params.getPosition());
            return Either.forRight(completionAt(text, offset));
        });
    }

    /** Gợi ý tại offset của cả file; thứ tự giữ đúng xếp hạng của engine (sortText). */
    CompletionList completionAt(String text, int offset) {
        StatementSlicer.Slice slice = StatementSlicer.sliceAt(text, offset, dialect);
        String statement = text.substring(slice.start(), slice.end());
        int local = offset - slice.start();
        var input = CompletionInputPreparer.buildInput(statement, local);
        List<Suggestion> suggestions = DialectAdapters.of(dialect).suggest(input);

        Range replace = new Range(Positions.toPosition(text, offset - input.prefix().length()), Positions.toPosition(text, offset));
        List<CompletionItem> items = new ArrayList<>(suggestions.size());
        for (int i = 0; i < suggestions.size(); i++) {
            Suggestion s = suggestions.get(i);
            CompletionItem item = new CompletionItem(s.getKey());
            item.setKind(kindOf(s.getType()));
            item.setDetail(s.getColumnType() == null ? s.getType().label() : s.getType().label() + " · " + s.getColumnType());
            item.setTextEdit(Either.forLeft(new TextEdit(replace, s.getKey())));
            item.setSortText(String.format("%05d", i));
            // editor lọc lại theo chữ trong range: cho khớp cả khi gõ phần sau dấu chấm ("u.na" -> "u.name")
            item.setFilterText(input.prefix().contains(".") || s.getType() == SuggestionType.COLUMNS ? input.prefix() + s.getKey() : s.getKey());
            items.add(item);
        }
        // engine đã tự lọc theo chữ đang gõ: gõ thêm thì editor phải hỏi lại, không lọc tiếp trên danh sách cũ
        return new CompletionList(true, items);
    }

    private static CompletionItemKind kindOf(SuggestionType type) {
        return switch (type) {
            case TABLE -> CompletionItemKind.Struct;
            case VIEW, MATERIALIZED_VIEW -> CompletionItemKind.Interface;
            case COLUMN -> CompletionItemKind.Field;
            case COLUMNS, JOIN -> CompletionItemKind.Snippet;
            case FUNCTION -> CompletionItemKind.Function;
            case KEYWORD, COMMAND -> CompletionItemKind.Keyword;
            case DATATYPE -> CompletionItemKind.TypeParameter;
            case SCHEMA, DATABASE -> CompletionItemKind.Module;
            case ALIAS -> CompletionItemKind.Variable;
            case VALUE -> CompletionItemKind.Value;
            case INDEX, SEQUENCE -> CompletionItemKind.Reference;
            case TRIGGER -> CompletionItemKind.Event;
            case ROLE, TABLESPACE, OTHER -> CompletionItemKind.Constant;
        };
    }

    // ── chữ ký hàm ───────────────────────────────────────────────────

    @Override
    public CompletableFuture<SignatureHelp> signatureHelp(SignatureHelpParams params) {
        return CompletableFuture.supplyAsync(() -> {
            String text = documents.getOrDefault(params.getTextDocument().getUri(), "");
            return signatureAt(text, Positions.toOffset(text, params.getPosition()));
        });
    }

    SignatureHelp signatureAt(String text, int offset) {
        StatementSlicer.Slice slice = StatementSlicer.sliceAt(text, offset, dialect);
        var call = InputHints.callAt(text.substring(slice.start(), slice.end()), offset - slice.start(), dialect);
        String signature = call == null ? null : SchemaIndex.functionSignatures.get(call.function());
        if (signature == null) {
            return null;
        }
        SignatureInformation info = new SignatureInformation(call.function() + signature);
        List<ParameterInformation> parameters = new ArrayList<>();
        int open = signature.indexOf('(');
        int close = signature.lastIndexOf(')');
        if (open == 0 && close > 0) {
            for (String p : splitArguments(signature.substring(1, close))) {
                parameters.add(new ParameterInformation(p));
            }
        }
        info.setParameters(parameters);
        SignatureHelp help = new SignatureHelp(List.of(info), 0, Math.min(call.argumentIndex(), Math.max(0, parameters.size() - 1)));
        return help;
    }

    private static List<String> splitArguments(String args) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (c == '(' || c == '[') {
                depth++;
            } else if (c == ')' || c == ']') {
                depth--;
            } else if (c == ',' && depth == 0) {
                out.add(args.substring(start, i).strip());
                start = i + 1;
            }
        }
        if (!args.isBlank()) {
            out.add(args.substring(start).strip());
        }
        return out;
    }

    // ── định dạng ────────────────────────────────────────────────────

    @Override
    public CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams params) {
        return CompletableFuture.supplyAsync(() -> {
            String text = documents.getOrDefault(params.getTextDocument().getUri(), "");
            return formatEdits(text, params.getOptions());
        });
    }

    /** 1 edit thay cả file (rỗng nếu không đổi gì); thụt lề theo tabSize của editor khi editor dùng dấu cách. */
    List<TextEdit> formatEdits(String text, FormattingOptions editorOptions) {
        SqlFormatter.Options options = SqlFormatter.Options.fromSystemProperties();
        if (editorOptions != null && editorOptions.isInsertSpaces() && editorOptions.getTabSize() > 0) {
            options = options.withIndent(editorOptions.getTabSize());
        }
        String formatted = SqlFormatter.format(text, dialect, options);
        if (formatted.equals(text)) {
            return List.of();
        }
        return List.of(new TextEdit(new Range(new Position(0, 0), Positions.toPosition(text, text.length())), formatted));
    }

    // ── lỗi cú pháp ──────────────────────────────────────────────────

    private void scheduleDiagnostics(String uri) {
        ScheduledFuture<?> previous = pendingDiagnostics.put(uri, diagnostics.schedule(() -> publishDiagnostics(uri),
                DIAGNOSTICS_DELAY_MS, TimeUnit.MILLISECONDS));
        if (previous != null) {
            previous.cancel(false);
        }
    }

    private void publishDiagnostics(String uri) {
        String text = documents.get(uri);
        if (text == null || client == null) {
            return;
        }
        client.publishDiagnostics(new PublishDiagnosticsParams(uri, diagnosticsFor(text)));
    }

    /** Mỗi câu tối đa 1 lỗi (lỗi đầu tiên - sau đó parser chỉ đoán mò). */
    List<Diagnostic> diagnosticsFor(String text) {
        List<Diagnostic> out = new ArrayList<>();
        for (StatementSlicer.Slice slice : StatementSlicer.slices(text, dialect)) {
            String statement = text.substring(slice.start(), slice.end());
            if (statement.isBlank()) {
                continue;
            }
            InputHints.SyntaxProblem problem = InputHints.syntaxProblemIn(statement, dialect);
            if (problem != null) {
                Range range = new Range(Positions.toPosition(text, slice.start() + problem.start()),
                        Positions.toPosition(text, slice.start() + problem.stop() + 1));
                out.add(new Diagnostic(range, "syntax error near '" + problem.tokenText() + "'", DiagnosticSeverity.Error, "sqlctx"));
            }
        }
        return out;
    }

    // ── workspace ────────────────────────────────────────────────────

    @Override
    public CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {
        if (REFRESH_COMMAND.equals(params.getCommand())) {
            loadSchema();
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
    }
}
