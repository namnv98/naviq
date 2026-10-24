// Dump completion của IntelliJ (DatabaseTools) cho từng câu SQL test của sqlctx - xem README.md cùng thư mục.
// Chạy trong IDE Scripting Console (Groovy, phím Ctrl+Enter - KHÔNG dùng nút Run):
//   evaluate(new File("<repo>/tools/intellij-compare/ij-completion-dump.groovy"))
// Đọc câu SQL từ <repo>/tools/intellij-compare/queries.txt, ghi <repo>/target/ij-compare/ij-out.tsv -
// IntellijCompareTest (Java) đọc cùng queries.txt + file kết quả này để so với tool.
// Yêu cầu: tab Query Console của data source tên chứa "sqlctx_fixture" đang MỞ (không cần đang chọn).
// KHÔNG gõ phím/click trong IDE cho tới khi hiện thông báo "Xong".
import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.completion.CodeCompletionHandlerBase
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiDocumentManager

import javax.swing.Timer
import java.awt.event.ActionListener

class SqlctxCompletionDumper {
    static final String QUERIES = "tools/intellij-compare/queries.txt"

    // Project đang mở chính là repo sqlctx (có queries.txt); kết quả ghi vào tools/intellij-compare/out
    // (KHÔNG phải target/ - Maven/IntelliJ rebuild hay xoá sạch target/, mất hết kết quả capture).
    static final String REPO = ProjectManager.getInstance().openProjects.collect { it.basePath }
            .find { new File(it, QUERIES).isFile() }
    static final String DIR = REPO + "/tools/intellij-compare/out"

    // queries.txt: mỗi dòng 1 câu ("|" = con trỏ), bỏ dòng trống/dòng bắt đầu bằng "#".
    static List<String> readQueries() {
        return new File(REPO, QUERIES).readLines("UTF-8").findAll { it.trim() && !it.trim().startsWith("#") }
    }

    def project
    def editor
    List<List<String>> cases
    StringBuilder out = new StringBuilder()
    def settings = CodeInsightSettings.getInstance()
    boolean savedAuto
    boolean savedSmart
    String originalText
    int idx = 0
    long startedAt
    long stableSince
    int lastCount = -1
    Timer poller

    static String unescape(String s) {
        StringBuilder b = new StringBuilder()
        int i = 0
        while (i < s.length()) {
            char c = s.charAt(i)
            if (c == ('\\' as char) && i + 1 < s.length()) {
                char n = s.charAt(i + 1)
                b.append(n == ('n' as char) ? '\n' : String.valueOf(n))
                i += 2
            } else {
                b.append(c)
                i++
            }
        }
        return b.toString()
    }

    static String escape(String s) {
        return (s ?: "").replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")
    }

    // Data source (id -> tên) của project, tra qua plugin Database (classloader riêng của plugin).
    static Map<String, String> dataSources(p) {
        try {
            def cl = PluginManagerCore.getPlugin(PluginId.getId("com.intellij.database")).pluginClassLoader
            def facade = Class.forName("com.intellij.database.psi.DbPsiFacade", true, cl).getInstance(p)
            Map<String, String> m = [:]
            facade.dataSources.each { ds -> m[ds.uniqueId.toString()] = ds.name.toString() }
            return m
        } catch (Throwable t) {
            return [:]
        }
    }

    void init() {
        def found = []
        ProjectManager.getInstance().openProjects.each { p ->
            def ids = dataSources(p).findAll { k, v -> v.contains("sqlctx_fixture") }.keySet()
            FileEditorManager.getInstance(p).openFiles.each { f ->
                if (ids.any { id -> f.path.contains("/consoles/db/" + id + "/") }) {
                    found << [p, f]
                }
            }
        }
        if (found.isEmpty()) {
            def names = ProjectManager.getInstance().openProjects.collect { dataSources(it).values() }.flatten()
            throw new IllegalStateException("Không thấy tab Query Console nào đang mở của data source tên chứa 'sqlctx_fixture'. Data source hiện có: " + names)
        }
        project = found[0][0]
        def file = found[0][1]
        FileEditorManager.getInstance(project).openFile(file, true)
        editor = (FileEditorManager.getInstance(project).getEditors(file).find { it instanceof TextEditor } as TextEditor).editor
        if (REPO == null) {
            throw new IllegalStateException("Không thấy project sqlctx đang mở (thiếu " + QUERIES + ")")
        }
        new File(DIR).mkdirs()
        // [khoá ghi ra file (escape xuống dòng/tab), câu SQL thật]
        cases = readQueries().collect { [escape(it), it] }
        originalText = editor.document.text
        savedAuto = settings.AUTOCOMPLETE_ON_CODE_COMPLETION
        savedSmart = settings.AUTOCOMPLETE_ON_SMART_TYPE_COMPLETION
        settings.AUTOCOMPLETE_ON_CODE_COMPLETION = false
        settings.AUTOCOMPLETE_ON_SMART_TYPE_COMPLETION = false
        // Callback của javax.swing.Timer KHÔNG có write-intent lock của IntelliJ -> phải chuyển qua
        // Application.invokeLater (có lock) trước khi đụng document/PSI/lookup.
        poller = new Timer(150, { ApplicationManager.getApplication().invokeLater({ poll() } as Runnable) } as ActionListener)
    }

    static String kindOf(e) {
        for (x in [e.object, e.psiElement]) {
            try {
                def k = x?.kind
                if (k != null) return k.toString()
            } catch (Throwable ignored) {
            }
        }
        return e.psiElement != null ? e.psiElement.class.simpleName : e.object.class.simpleName
    }

    void record(String key, lookup) {
        if (lookup != null) {
            lookup.items.eachWithIndex { e, i ->
                def p = new LookupElementPresentation()
                e.renderElement(p)
                out << key << '\t' << i << '\t' << escape(e.lookupString) << '\t' << kindOf(e) << '\t' <<
                        escape(p.typeText) << '\t' << escape(p.tailText) << '\n'
            }
        }
        out << key << "\t-1\t\t\t\t\n"
        // ghi dần để theo dõi được tiến độ (và không mất kết quả nếu dừng giữa chừng)
        new File(DIR + "/ij-out.partial.tsv").text = out.toString()
    }

    void finish() {
        settings.AUTOCOMPLETE_ON_CODE_COMPLETION = savedAuto
        settings.AUTOCOMPLETE_ON_SMART_TYPE_COMPLETION = savedSmart
        WriteCommandAction.runWriteCommandAction(project, { editor.document.setText(originalText) } as Runnable)
        new File(DIR + "/ij-out.tsv").text = out.toString()
        Messages.showInfoMessage(project, "Xong " + cases.size() + " câu -> " + DIR + "/ij-out.tsv", "sqlctx completion dump")
    }

    void fail(Throwable t) {
        settings.AUTOCOMPLETE_ON_CODE_COMPLETION = savedAuto
        settings.AUTOCOMPLETE_ON_SMART_TYPE_COMPLETION = savedSmart
        poller?.stop()
        def sw = new StringWriter()
        t.printStackTrace(new PrintWriter(sw))
        new File(DIR + "/ij-error.txt").text = "case #" + idx + "\n" + sw
        Messages.showErrorDialog(project, "Lỗi ở câu #" + idx + ": " + t + "\nChi tiết: " + DIR + "/ij-error.txt", "sqlctx completion dump")
    }

    void startCase() {
        try {
            if (idx >= cases.size()) {
                finish()
                return
            }
            String raw = cases[idx][1]
            int caret = raw.indexOf('|')
            String sql = raw.substring(0, caret) + raw.substring(caret + 1)
            LookupManager.getActiveLookup(editor)?.hideLookup(true)
            WriteCommandAction.runWriteCommandAction(project, { editor.document.setText(sql) } as Runnable)
            PsiDocumentManager.getInstance(project).commitDocument(editor.document)
            editor.caretModel.moveToOffset(caret)
            startedAt = System.currentTimeMillis()
            lastCount = -1
            stableSince = startedAt
            CodeCompletionHandlerBase.createHandler(CompletionType.BASIC).invokeCompletion(project, editor, 1)
            poller.restart()
        } catch (Throwable t) {
            fail(t)
        }
    }

    void poll() {
        if (!poller.isRunning()) {
            return
        }
        try {
            long now = System.currentTimeMillis()
            def lookup = LookupManager.getActiveLookup(editor)
            boolean ready
            if (lookup != null) {
                int n = lookup.items.size()
                if (lookup.isCalculating() || n != lastCount) {
                    lastCount = n
                    stableSince = now
                    ready = false
                } else {
                    ready = now - stableSince > 500
                }
            } else {
                ready = now - startedAt > 4000
            }
            if (!ready && now - startedAt < 10000) return
            poller.stop()
            record(cases[idx][0], lookup)
            lookup?.hideLookup(true)
            idx++
            def next = new Timer(100, { ApplicationManager.getApplication().invokeLater({ startCase() } as Runnable) } as ActionListener)
            next.repeats = false
            next.start()
        } catch (Throwable t) {
            fail(t)
        }
    }
}

def dumper = new SqlctxCompletionDumper()
dumper.init()
ApplicationManager.getApplication().invokeLater({ dumper.startCase() } as Runnable)
"Đang chạy " + dumper.cases.size() + " câu - chờ thông báo Xong"
