package com.naviq.completion.syntactic.engine.feature;

import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.atn.ATNState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * FEATURE PHỤ, KHÁC HẲN {@link TraceGraph}: TraceGraph vẽ bản đồ Ở TẦM MÊ
 * CUNG (mỗi box = 1 rule, không vẽ phòng lẻ bên trong — xem lý do ở đó).
 * Lớp này làm NGƯỢC LẠI — "ZOOM" vào ĐÚNG 1 mê cung cụ thể, vẽ ra TỪNG PHÒNG
 * ({@code ATNState}) và TỪNG CỬA thật bên trong nó, đúng phong cách sơ đồ
 * minh hoạ trong {@code ATN_ROOM_DOOR_ANALOGY.md} (nhãn "mật khẩu"/"miễn
 * phí" trên từng cửa, mê cung con thu gọn thành 1 hộp riêng).
 * <p>
 * Vì zoom tới từng phòng sẽ nổ kích thước rất nhanh nếu vẽ mọi mê cung cùng
 * lúc, lớp này CHỈ ghi lại đúng 1 mê cung MỤC TIÊU (chọn bằng tên rule qua
 * {@code -Dnaviq.trace.zoom.rule=...}), và CHỈ ghi đúng LẦN ĐẦU TIÊN mê cung
 * đó được vào trong cả câu — nếu rule đó được gọi lại nhiều lần, các lần sau
 * bị bỏ qua (tránh vẽ trùng / vẽ đè). KHÔNG truyền {@code -Dnaviq.trace.zoom.rule}
 * -> mặc định zoom vào MÊ CUNG GỐC (rule đầu tiên được vào, tức toàn bộ câu),
 * thay vì im lặng không ghi gì.
 * <p>
 * Mê cung con (gặp cửa dẫn sang rule khác trong lúc đang zoom) KHÔNG được
 * zoom tiếp vào bên trong — chỉ vẽ như 1 hộp riêng "🌀 Mê cung con: tên rule",
 * coi nó là hộp đen, y hệt cách sơ đồ minh hoạ trong tài liệu vẽ (không cần
 * biết chi tiết bên trong nó để hiểu mê cung mục tiêu đang zoom).
 * <p>
 * QUAN TRỌNG (bug đã sửa): {@code recording} giữ nguyên {@code true} suốt từ
 * lúc vào mê cung mục tiêu tới lúc thoát HẲN — kể cả lúc đang lồng sâu bên
 * trong 1 mê cung con (đang chạy {@code walkRuleBody} riêng của rule đó).
 * Nếu chỉ kiểm tra {@code recording}, mọi phòng/cửa BÊN TRONG mê cung con đó
 * cũng bị ghi nhận lẫn vào — tràn ra ngoài hộp "🌀 Mê cung con" thay vì bị
 * nuốt gọn. Nên {@link #isRecording()} phải kiểm tra ĐÚNG ĐỘ SÂU
 * ({@code nestedEnterCount == 1}, tức đang đứng ở chính mê cung mục tiêu,
 * không lồng sâu hơn) — chỉ {@link #onRuleDoor} (vẽ hộp thu gọn) được phép
 * chạy ở độ sâu khác 1.
 * <p>
 * Bật bằng {@code -Dnaviq.trace.zoom=true -Dnaviq.trace.zoom.rule=<tên rule>}.
 * File xuất ra chỉnh bằng {@code -Dnaviq.trace.zoom.file=...} (mặc định
 * {@code naviq-zoom.mmd}). Khi tắt, mọi method no-op ngay từ đầu.
 * <p>
 * TÌM ĐƯỜNG VỀ CARET: mặc định chỉ vẽ những PHÒNG CÓ THỂ dẫn tới 1 phòng
 * caret (dò ngược qua MỌI cạnh đến, không chỉ 1 cha — khác {@link TraceGraph},
 * ở tầm phòng 1 state có thể được nhiều state khác cùng hội tụ dẫn tới, nên
 * đây là dò ngược kiểu đồ thị chứ không phải cây). Nhánh không dẫn tới caret
 * bị gộp thành "+N cửa khác chưa đi, ẩn" trên đúng phòng đó. Tắt bằng
 * {@code -Dnaviq.trace.zoom.prune=false} nếu muốn xem toàn bộ (rồi tự
 * {@code mermaid.initialize({maxEdges: ...})} nếu Mermaid báo vượt giới hạn).
 * Giới hạn thêm bằng {@code -Dnaviq.trace.zoom.max=<số>} (mặc định 60) —
 * luôn ưu tiên giữ phòng GẦN CARET NHẤT trước khi cắt bớt.
 */
public final class MazeZoomGraph {

    public static final boolean ENABLED = true;
    public static final String TARGET_RULE_NAME = System.getProperty("naviq.trace.zoom.rule", "");
    public static final String FILE_PATH = System.getProperty("naviq.trace.zoom.file", "naviq-zoom.mmd");
    public static final boolean FIND_PATH_TO_CARET = !"false".equals(System.getProperty("naviq.trace.zoom.prune", "false"));
    public static final int MAX_ROOMS = Integer.getInteger("naviq.trace.zoom.max", 1000);

    /** Hình dạng phòng trên sơ đồ — khớp đúng các hình trong ví dụ minh hoạ gốc. */
    private enum Shape {
        ROOM,            // ([...]) — phòng bình thường
        RULE_STOP,       // ((...)) — hết mê cung (RULE_STOP), chưa chạm caret
        DECISION_OUTPUT, // {{...}} — nơi caret rơi vào, sinh gợi ý (giống "Ngã rẽ, hết từ" trong ví dụ)
        SUB_MAZE         // [[...]] — mê cung con thu gọn thành hộp đen
    }

    private record Room(String id, String label, Shape shape) {
    }

    private record Door(String fromRoom, String label, String toRoom, boolean dashed) {
    }

    private final Parser parser;
    private boolean recording = false;
    private boolean doneOnce = false;
    private boolean seenAnyMaze = false; // đã từng thấy enterMaze nào chưa — dùng để biết đây có phải MÊ CUNG GỐC
    private int nestedEnterCount = 0; // độ sâu lồng nhau TÍNH TỪ mê cung mục tiêu (1 = chính nó, >=2 = đã lồng vào mê cung con)

    private final List<Room> rooms = new ArrayList<>();
    private final List<Door> doors = new ArrayList<>();
    private final Set<String> seenRoomIds = new HashSet<>();
    private int subMazeSeq = 0;

    public MazeZoomGraph(Parser parser) {
        this.parser = parser;
    }

    /**
     * CHỈ true khi đang đứng ĐÚNG ở mê cung mục tiêu (không lồng sâu hơn).
     * Xem giải thích bug ở javadoc lớp — đây là chỗ sửa.
     */
    private boolean isRecording() {
        return ENABLED && recording && nestedEnterCount == 1;
    }

    /** Gọi ở đầu {@code enterRule}, cho MỌI mê cung (không chỉ mục tiêu) — tự quyết định có bắt đầu ghi hay không. */
    public void onEnterMaze(ATNState start, int tokenIndex) {
        if (!ENABLED || doneOnce) return;
        if (recording) {
            nestedEnterCount++; // đang ghi rồi, đây là 1 mê cung con lồng bên trong mục tiêu -> chỉ đếm độ lồng
            return;
        }
        boolean isRootMaze = !seenAnyMaze;
        seenAnyMaze = true;
        // Không truyền -Dnaviq.trace.zoom.rule -> mặc định "lấy full": zoom luôn
        // vào MÊ CUNG GỐC (lần enterMaze đầu tiên trong cả câu), thay vì im
        // lặng không ghi gì cả.
        boolean shouldStart = TARGET_RULE_NAME.isEmpty()
                ? isRootMaze
                : safeRuleName(start.ruleIndex).equals(TARGET_RULE_NAME);
        if (shouldStart) {
            recording = true;
            nestedEnterCount = 1;
            rooms.clear();
            doors.clear();
            seenRoomIds.clear();
            subMazeSeq = 0;
            registerRoom(start.stateNumber, tokenIndex, "🚪 Điểm vào\\n" + safeRuleName(start.ruleIndex), Shape.ROOM);
        }
    }

    /** Gọi ngay khi {@code enterRule} chuẩn bị trả về, cho MỌI mê cung. */
    public void onExitMaze() {
        if (!ENABLED || doneOnce || !recording) return;
        if (--nestedEnterCount == 0) {
            recording = false;
            doneOnce = true; // chỉ zoom đúng 1 lần cho cả collectCandidates()
            dumpToFile();
        }
    }

    private String roomId(int stateNumber, int tokenIndex) {
        return "s" + stateNumber + "_" + tokenIndex;
    }

    private void registerRoom(int stateNumber, int tokenIndex, String label, Shape shape) {
        String id = roomId(stateNumber, tokenIndex);
        if (seenRoomIds.add(id)) {
            rooms.add(new Room(id, label, shape));
        }
    }

    /** Gọi cho MỖI phòng mà walkRuleBody vừa bước vào (kể cả RULE_STOP) — chỉ có tác dụng khi đang zoom ĐÚNG mê cung mục tiêu. */
    public void onVisitState(ATNState state, int tokenIndex, boolean isRuleStop, boolean atCaretHere) {
        if (!isRecording()) return;
        if (isRuleStop) {
            registerRoom(state.stateNumber, tokenIndex, "🏁 Hết mê cung\\n@" + tokenIndex,
                    atCaretHere ? Shape.DECISION_OUTPUT : Shape.RULE_STOP);
        } else {
            registerRoom(state.stateNumber, tokenIndex, "phòng " + state.stateNumber + "\\n@" + tokenIndex, Shape.ROOM);
        }
    }

    /** Cửa cần mật khẩu — đã khớp đúng từ kế tiếp, bước qua (tốn 1 lời). */
    public void onPasswordDoor(ATNState from, int fromTokenIndex, ATNState to, int toTokenIndex, String tokenLabel) {
        if (!isRecording()) return;
        registerRoom(to.stateNumber, toTokenIndex, "phòng " + to.stateNumber + "\\n@" + toTokenIndex, Shape.ROOM);
        doors.add(new Door(roomId(from.stateNumber, fromTokenIndex), tokenLabel + "\\nmật khẩu",
                roomId(to.stateNumber, toTokenIndex), false));
    }

    /** Cửa cần mật khẩu kiểu "gõ gì cũng được" (wildcard) — vẫn tốn 1 lời, chỉ không kén tên. */
    public void onWildcardDoor(ATNState from, int fromTokenIndex, ATNState to, int toTokenIndex) {
        if (!isRecording()) return;
        registerRoom(to.stateNumber, toTokenIndex, "phòng " + to.stateNumber + "\\n@" + toTokenIndex, Shape.ROOM);
        doors.add(new Door(roomId(from.stateNumber, fromTokenIndex), "bất kỳ\\nmật khẩu",
                roomId(to.stateNumber, toTokenIndex), false));
    }

    /** Cửa miễn phí (epsilon), có hoặc không kèm điều kiện — không tốn lời. */
    public void onFreeDoor(ATNState from, int tokenIndex, ATNState to, boolean hasCondition) {
        if (!isRecording()) return;
        registerRoom(to.stateNumber, tokenIndex, "phòng " + to.stateNumber + "\\n@" + tokenIndex, Shape.ROOM);
        doors.add(new Door(roomId(from.stateNumber, tokenIndex), hasCondition ? "miễn phí\\n(có điều kiện)" : "miễn phí",
                roomId(to.stateNumber, tokenIndex), false));
    }

    /**
     * Cửa dẫn vào 1 mê cung con — đi hết mê cung con đó (không zoom vào), rồi
     * quay lại đúng followState. CHÚ Ý: hàm này KHÔNG gọi qua {@link #isRecording()}
     * bình thường vì bản thân nó CHÍNH LÀ "cửa" hợp lệ ở độ sâu 1 (mê cung mục
     * tiêu) — nhưng vẫn phải tự kiểm tra {@code nestedEnterCount == 1} vì
     * `atCaret` false không đảm bảo chỉ được gọi ở đúng độ sâu.
     */
    public void onRuleDoor(ATNState from, int fromTokenIndex, int subRuleIndex, ATNState followState, int exitTokenIndex) {
        if (!ENABLED || doneOnce || !recording || nestedEnterCount != 1) return;
        String subMazeId = "sub" + (subMazeSeq++);
        rooms.add(new Room(subMazeId, "🌀 Mê cung con\\n" + safeRuleName(subRuleIndex), Shape.SUB_MAZE));
        doors.add(new Door(roomId(from.stateNumber, fromTokenIndex), "vào mê cung con", subMazeId, false));
        registerRoom(followState.stateNumber, exitTokenIndex, "phòng " + followState.stateNumber + "\\n@" + exitTokenIndex, Shape.ROOM);
        doors.add(new Door(subMazeId, "quay lại\\nmê cung chính", roomId(followState.stateNumber, exitTokenIndex), false));
    }

    /** Đường tắt VIP tại caret: chạm cửa vào 1 mê cung con VIP, dừng ngay không đi vào bên trong. */
    public void onRuleDoorShortcutAtCaret(ATNState from, int fromTokenIndex, int subRuleIndex) {
        if (!isRecording()) return;
        String subMazeId = "sub" + (subMazeSeq++);
        rooms.add(new Room(subMazeId, "🌀 Mê cung con (VIP)\\n" + safeRuleName(subRuleIndex), Shape.DECISION_OUTPUT));
        doors.add(new Door(roomId(from.stateNumber, fromTokenIndex), "đường tắt VIP", subMazeId, true));
    }

    /** Đánh dấu 1 phòng là nơi caret rơi vào, sinh gợi ý (tô như "Ngã rẽ, hết từ" trong ví dụ minh hoạ). */
    public void onCaretSuggestionHere(ATNState state, int tokenIndex) {
        if (!isRecording()) return;
        String id = roomId(state.stateNumber, tokenIndex);
        for (int i = 0; i < rooms.size(); i++) {
            Room r = rooms.get(i);
            if (r.id().equals(id)) {
                rooms.set(i, new Room(r.id(), r.label(), Shape.DECISION_OUTPUT));
                return;
            }
        }
        registerRoom(state.stateNumber, tokenIndex, "phòng " + state.stateNumber + "\\n@" + tokenIndex, Shape.DECISION_OUTPUT);
    }

    private String safeRuleName(int ruleIndex) {
        String[] names = parser.getRuleNames();
        return ruleIndex >= 0 && ruleIndex < names.length ? names[ruleIndex] : ("rule#" + ruleIndex);
    }

    /**
     * KHÁC với {@code TraceGraph.mazesOnPathToCaret()}: ở đó mỗi mê cung chỉ
     * có ĐÚNG 1 cha (cây thật), dò ngược qua 1 con trỏ cha là đủ. Còn ở đây,
     * 1 PHÒNG (state+tokenIndex) có thể được NHIỀU phòng khác cùng dẫn tới —
     * ATN hội tụ nhánh rất thường xuyên (nhiều cửa miễn phí cùng đổ về 1
     * phòng). Nên phải dò ngược đúng kiểu đồ thị: từ mọi phòng caret, đi
     * ngược theo MỌI cạnh đến (không chỉ 1 cha), gom hết phòng nào có thể
     * chạm được caret bằng cách nào đó.
     */
    private Set<String> roomsThatCanReachCaret() {
        Map<String, List<String>> predecessorsOf = new HashMap<>();
        for (Door d : doors) {
            predecessorsOf.computeIfAbsent(d.toRoom(), k -> new ArrayList<>()).add(d.fromRoom());
        }
        Set<String> keep = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        for (Room r : rooms) {
            if (r.shape() == Shape.DECISION_OUTPUT) {
                keep.add(r.id());
                queue.add(r.id());
            }
        }
        // BFS ngược đi đúng theo thứ tự khoảng cách tăng dần tính từ caret — nên
        // dừng sớm khi đủ MAX_ROOMS vẫn đảm bảo giữ lại đúng những phòng GẦN
        // caret nhất trước, cắt bớt phòng xa hơn (ít liên quan hơn) trước tiên.
        while (!queue.isEmpty() && keep.size() < MAX_ROOMS) {
            for (String pred : predecessorsOf.getOrDefault(queue.poll(), List.of())) {
                if (keep.size() >= MAX_ROOMS) break;
                if (keep.add(pred)) {
                    queue.add(pred);
                }
            }
        }
        return keep;
    }

    /** Sinh cú pháp Mermaid — dán vào https://mermaid.live để xem đúng hình như sơ đồ minh hoạ gốc. */
    public String render() {
        boolean hasCaretRoom = rooms.stream().anyMatch(r -> r.shape() == Shape.DECISION_OUTPUT);
        boolean findPath = FIND_PATH_TO_CARET && hasCaretRoom;
        Set<String> keep = findPath ? roomsThatCanReachCaret() : null; // null = vẽ hết, không tìm đường riêng

        Map<String, Integer> otherDoorsFrom = new HashMap<>();
        if (findPath) {
            for (Door d : doors) {
                if (keep.contains(d.fromRoom()) && !keep.contains(d.toRoom())) {
                    otherDoorsFrom.merge(d.fromRoom(), 1, Integer::sum);
                }
            }
        }

        StringBuilder sb = new StringBuilder("flowchart LR\n");
        if (findPath) {
            int roomsNotShown = rooms.size() - keep.size();
            sb.append("  %% Đang ẩn ").append(roomsNotShown).append(" phòng");
            if (keep.size() >= MAX_ROOMS) {
                sb.append(" — ĐÃ ĐẠT GIỚI HẠN HIỂN THỊ (-Dnaviq.trace.zoom.max=").append(MAX_ROOMS)
                        .append("), chỉ giữ đúng ").append(MAX_ROOMS)
                        .append(" phòng GẦN CARET NHẤT. Còn nhiều phòng khác vẫn thật sự dẫn tới caret nhưng chưa vẽ —")
                        .append(" cân nhắc zoom vào 1 rule hẹp hơn, hoặc tăng -Dnaviq.trace.zoom.max\n");
            } else {
                sb.append(" không thể dẫn tới caret (bật lại bằng -Dnaviq.trace.zoom.prune=false)\n");
            }
        }

        for (Room r : rooms) {
            if (keep != null && !keep.contains(r.id())) continue;
            String label = r.label();
            Integer otherDoors = otherDoorsFrom.get(r.id());
            if (otherDoors != null) {
                label += "\\n(+" + otherDoors + " cửa khác chưa đi, ẩn)";
            }
            String open, close;
            switch (r.shape()) {
                case DECISION_OUTPUT -> { open = "{{"; close = "}}"; }
                case SUB_MAZE -> { open = "[["; close = "]]"; }
                case RULE_STOP -> { open = "(("; close = "))"; }
                default -> { open = "(["; close = "])"; }
            }
            sb.append("  ").append(r.id()).append(open).append("\"").append(label).append("\"").append(close).append("\n");
        }
        sb.append("\n");
        for (Door d : doors) {
            if (keep != null && (!keep.contains(d.fromRoom()) || !keep.contains(d.toRoom()))) continue;
            sb.append("  ").append(d.fromRoom()).append(d.dashed() ? " -.->" : " -->")
                    .append("|\"").append(d.label()).append("\"| ").append(d.toRoom()).append("\n");
        }
        sb.append("\n  classDef output fill:#ffe08a,stroke:#c77700,stroke-width:2px;\n");
        for (Room r : rooms) {
            if (keep != null && !keep.contains(r.id())) continue;
            if (r.shape() == Shape.DECISION_OUTPUT) {
                sb.append("  class ").append(r.id()).append(" output;\n");
            }
        }
        return sb.toString();
    }

    /** Ghi ra file .mmd — không throw, lỗi ghi file không được phép làm hỏng collectCandidates(). */
    public void dumpToFile() {
        try {
            Files.writeString(Path.of(FILE_PATH), render(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[MazeZoomGraph] Không ghi được file " + FILE_PATH + ": " + e.getMessage());
        }
    }
}