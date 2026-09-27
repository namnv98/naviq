package com.sqlctx.cli.terminal;

import com.sqlctx.cli.anchor.Anchor;
import com.sqlctx.cli.anchor.AnchorStrategy;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.Status;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

public class TerminalMenu {
    private final LineReader reader;
    private int menuRow = -1;
    private int lastHeight = 0;
    private List<String> lastRendered = new ArrayList<>();
    private int lastDrawCol = 1;
    private int lastMenuRow = -1;
    private boolean visible = false;

    public TerminalMenu(LineReader reader) {
        this.reader = reader;
    }


    private List<String> restoreLines = new ArrayList<>();
    private int restoreCol = 1;

    public void show(List<AttributedString> lines, AnchorStrategy strategy) {
        show(lines, strategy, 1, 1, List.of());
    }

    /**
     * @param restoreLines Nội dung THẬT (đã có prefix secondary-prompt nếu cần) đang
     *                      nằm ở các dòng mà menu sắp vẽ đè lên - vd các dòng còn lại
     *                      của 1 câu SQL multi-line phía dưới cursor. Khi hide(), các
     *                      dòng này sẽ được TỰ IN LẠI đúng vị trí, vì REDISPLAY của
     *                      JLine không biết những dòng này đã bị ghi đè bởi ANSI thô
     *                      (nằm ngoài model nội bộ nó tự theo dõi).
     */
    public void show(List<AttributedString> lines, AnchorStrategy strategy,
        int gapBelowCursor, int bottomPadding, List<String> restoreLines) {
        Terminal term = reader.getTerminal();
        PrintWriter out = term.writer();
        int width = term.getWidth();
        int termHeight = term.getHeight();

        reader.callWidget(LineReader.REDISPLAY);

        Anchor anchor = strategy.resolve(term);
        if (anchor == null) return; // terminal không trả được vị trí cursor → huỷ vẽ

        int cursorAbsRow = anchor.row();
        int cursorAbsCol = anchor.col();

        int height = anchor.height();

        // Các dòng cuối màn hình thuộc về status bar của JLine, menu không được vẽ hay cuộn vào đó.
        Status status = Status.getStatus(term, false);
        int statusRows = status == null ? 0 : status.size();
        int lastVisibleRow = termHeight - statusRows - bottomPadding;
        int menuStartRow = cursorAbsRow + gapBelowCursor;
        int menuEndRow = menuStartRow + height - bottomPadding;

        if (menuEndRow > lastVisibleRow) {
            int scrollLines = menuEndRow - lastVisibleRow;
            out.print("\u001b[" + scrollLines + "S");
            cursorAbsRow -= scrollLines;
            menuStartRow -= scrollLines;

            // Nội dung cũ (kể cả menu lần trước) vừa bị cuộn lên cùng scrollLines dòng -
            // KHÔNG bị xóa bởi thao tác cuộn (\u001b[NS chỉ DỊCH nội dung lên, không xóa
            // nó) - phải chủ động xóa NGAY tại vị trí MỚI (đã trừ scrollLines) ở đây,
            // vì đoạn "xóa vị trí cũ" phía dưới sẽ KHÔNG chạy được nữa một khi
            // lastRendered.clear() ngay sau đây làm nó thành no-op (size=0).
            if (lastMenuRow >= 0) {
                lastMenuRow -= scrollLines;
                for (int i = 0; i < lastRendered.size(); i++) {
                    out.print("\u001b[" + (lastMenuRow + i) + ";" + lastDrawCol + "H");
                    out.print("\u001b[2K");
                    if (i < this.restoreLines.size()) {
                        out.print("\u001b[" + (lastMenuRow + i) + ";" + restoreCol + "H");
                        out.print(this.restoreLines.get(i));
                    }
                }
            }

            out.print("\u001b[" + cursorAbsRow + ";" + cursorAbsCol + "H");
            lastRendered.clear(); // vị trí đổi → vẽ lại hết
            reader.callWidget(LineReader.REDISPLAY);
            out.flush();
        }

        // Chốt an toàn: không bao giờ vẽ dòng rộng hơn terminal (tràn sẽ tự xuống dòng và để rác).
        lines = lines.stream()
                .map(l -> l.columnLength() > width ? l.columnSubSequence(0, width) : l)
                .toList();
        int menuWidth = lines.stream().mapToInt(AttributedString::columnLength).max().orElse(0);
        int drawCol = Math.min(cursorAbsCol, width - menuWidth);
        if (drawCol < 1) drawCol = 1;

//        Cursor savedCursor = term.getCursorPosition(null);
//        int savedRow = savedCursor.getY() + 1;
//        int savedCol = savedCursor.getX() + 1;
        out.print("\u001b[s");

        // Xóa dòng thừa ở VỊ TRÍ CŨ trước khi vẽ mới
        if (lastMenuRow >= 0 && (lastDrawCol != drawCol || lastMenuRow != menuStartRow)) {
            for (int i = 0; i < lastRendered.size(); i++) {
                out.print("\u001b[" + (lastMenuRow + i) + ";" + lastDrawCol + "H");
                out.print("\u001b[2K");
                // Khôi phục text thật đã bị đè ở VỊ TRÍ CŨ - dùng restoreLines của
                // LẦN GỌI TRƯỚC (field này CHƯA bị ghi đè bởi tham số restoreLines
                // của lần gọi hiện tại - việc đó xảy ra SAU, ở cuối hàm).
                if (i < this.restoreLines.size()) {
                    out.print("\u001b[" + (lastMenuRow + i) + ";" + restoreCol + "H");
                    out.print(this.restoreLines.get(i));
                }
            }
            lastRendered.clear();
        }

        // Diff update
        for (int i = 0; i < height; i++) {
            String ansi = lines.get(i).toAnsi();
            String cached = i < lastRendered.size() ? lastRendered.get(i) : null;
            if (!ansi.equals(cached)) {
                out.print("\u001b[" + (menuStartRow + i) + ";" + drawCol + "H");
                out.print(ansi);
                out.print("\u001b[K");
            }
        }
        // Xóa dòng thừa nếu menu co lại (cùng vị trí) - CÙNG lý do, phải khôi phục
        // luôn những dòng vừa "lộ ra" do menu ngắn lại, dùng restoreLines CŨ (lần
        // gọi trước) vì cùng đang ở vị trí cũ (lastMenuRow == menuStartRow ở nhánh này).
        for (int i = height; i < lastRendered.size(); i++) {
            out.print("\u001b[" + (menuStartRow + i) + ";" + drawCol + "H");
            out.print("\u001b[2K");
            if (i < this.restoreLines.size()) {
                out.print("\u001b[" + (menuStartRow + i) + ";" + restoreCol + "H");
                out.print(this.restoreLines.get(i));
            }
        }

//        out.print("\u001b[" + savedRow + ";" + savedCol + "H");
        out.print("\u001b[u");

        // Cập nhật cache
        lastRendered = new ArrayList<>();
        for (int i = 0; i < height; i++) {
            lastRendered.add(lines.get(i).toAnsi());
        }
        lastDrawCol = drawCol;
        lastMenuRow = menuStartRow;

        menuRow = menuStartRow;
        lastHeight = height;

        this.restoreLines = restoreLines != null ? restoreLines : List.of();
        this.restoreCol = 1; // dòng multi-line luôn bắt đầu từ cột 1, không phải drawCol của menu

        reader.callWidget(LineReader.REDISPLAY);
        term.flush();
        visible = true;
    }

    public void hide() {
        if (!visible) {
            return;
        }
        clearMenu(reader.getTerminal().writer());
        lastRendered.clear();
        lastMenuRow = -1;
        visible = false;
        reader.callWidget(LineReader.REDISPLAY);
        reader.getTerminal().flush();
    }

    /** Quên trạng thái menu mà không vẽ gì (màn hình vừa bị xoá / vẽ lại, menu cũ không còn trên màn hình). */
    public void forget() {
        lastRendered.clear();
        lastMenuRow = -1;
        menuRow = -1;
        lastHeight = 0;
        restoreLines = List.of();
        visible = false;
    }

    /** Dọn menu khi readLine đã kết thúc (Ctrl+C): không còn gọi được widget nên chỉ xoá phần đã vẽ trên màn hình. */
    public void discard(PrintWriter out) {
        clearMenu(out);
        lastRendered.clear();
        lastMenuRow = -1;
        visible = false;
        out.flush();
    }

    // ─── clearMenu ────────────────────────────────────────────────────────────
    public void clearMenu(PrintWriter out) {
        if (lastHeight <= 0 || menuRow < 0) return;
        out.print("\u001b[s");
        for (int i = 0; i < lastHeight; i++) {
            out.print("\u001b[" + (menuRow + i) + ";1H");
            out.print("\u001b[2K");
            // TỰ IN LẠI nội dung thật (nếu có) đã bị menu đè lên - REDISPLAY của
            // JLine KHÔNG biết những dòng này đã bị ghi đè bởi ANSI thô (nằm ngoài
            // model nội bộ nó tự theo dõi), nên không tự khôi phục được.
            if (i < restoreLines.size()) {
                out.print("\u001b[" + (menuRow + i) + ";" + restoreCol + "H");
                out.print(restoreLines.get(i));
            }
        }
        out.print("\u001b[u");
        lastHeight = 0;
        menuRow = -1;
        restoreLines = List.of();
    }
}