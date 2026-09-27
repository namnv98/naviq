package com.sqlctx.cli.command;

import org.jline.terminal.Terminal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * \e — mở $EDITOR sửa lại câu lệnh gần nhất, nạp lại vào dòng nhập cho người dùng xem/sửa tiếp/Enter.
 */
public final class EditorLauncher {

    private EditorLauncher() {
    }

    /**
     * Thứ tự tìm editor - giống quy ước hầu hết CLI (git, less, crontab...) đã dùng, không tự đặt "vi" làm
     * mặc định cứng:
     * 1. $VISUAL, 2. $EDITOR - người dùng đã tự đặt thì tôn trọng ngay, không cần tìm gì thêm.
     * 3. /etc/alternatives/editor - trên Debian/Ubuntu đây CHÍNH LÀ "editor mặc định của hệ điều hành" mà
     *    người dùng/admin đã chọn qua "update-alternatives --config editor" (Arch/macOS không có file này).
     * 4. sensible-editor - script có sẵn trên Debian/Ubuntu làm đúng việc dò editor mặc định hệ thống.
     * 5. Không có gì cấu hình sẵn (vd Arch, hoặc container tối giản) - thử vài editor hay có sẵn theo distro,
     *    ưu tiên cái dễ dùng hơn (nano có chỉ dẫn phím ngay trên màn hình) trước vi/vim.
     */
    private static String resolveEditor() {
        String visual = System.getenv("VISUAL");
        if (visual != null && !visual.isBlank()) {
            return visual;
        }
        String editor = System.getenv("EDITOR");
        if (editor != null && !editor.isBlank()) {
            return editor;
        }
        Path systemDefault = Path.of("/etc/alternatives/editor");
        if (Files.isExecutable(systemDefault)) {
            return systemDefault.toString();
        }
        if (isOnPath("sensible-editor")) {
            return "sensible-editor";
        }
        for (String candidate : List.of("nano", "vim", "vi")) {
            if (isOnPath(candidate)) {
                return candidate;
            }
        }
        return "vi"; // POSIX bắt buộc hệ thống Unix nào cũng phải có - chốt chặn cuối cùng
    }

    private static boolean isOnPath(String command) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (Files.isExecutable(Path.of(dir, command))) {
                return true;
            }
        }
        return false;
    }

    public static String openInEditor(Terminal terminal, String initialSql) throws Exception {
        Path tmp = Files.createTempFile("sqlctx_edit_", ".sql");
        try {
            Files.writeString(tmp, (initialSql == null ? "" : initialSql) + "\n");
            String editor = resolveEditor();
            terminal.writer().flush();
            // inheritIO(): tiến trình con dùng THẲNG stdin/stdout/stderr thật của terminal, không qua JLine -
            // editor (vim/nano...) tự lo raw mode của riêng nó, JLine tự thiết lập lại raw mode cần thiết
            // ở lần readLine() kế tiếp nên không cần khôi phục thủ công gì thêm ở đây.
            Process p = new ProcessBuilder(editor, tmp.toString()).inheritIO().start();
            p.waitFor();
            return Files.readString(tmp).stripTrailing();
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
