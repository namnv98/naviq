package com.naviq.completion.syntactic.engine.feature;

import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.atn.ATNState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class MazeZoomGraph {

    public static final boolean ENABLED = false;
    public static final String TARGET_RULE_NAME = System.getProperty("naviq.trace.zoom.rule", "");
    public static final String FILE_PATH = System.getProperty("naviq.trace.zoom.file", "naviq-zoom.mmd");
    public static final boolean FIND_PATH_TO_CARET = !"false".equals(System.getProperty("naviq.trace.zoom.prune", "false"));
    public static final int MAX_ROOMS = Integer.getInteger("naviq.trace.zoom.max", 1000);
    public static final boolean SHOW_DEAD_ENDS = true;

    private enum Shape {
        ROOM,
        RULE_STOP,
        DECISION_OUTPUT,
    }

    private record Room(String id, String label, Shape shape, String contextId) {
    }

    private record Door(String fromRoom, String label, String toRoom, boolean dashed) {
    }

    private final Parser parser;
    private boolean recording = false;
    private boolean doneOnce = false;
    private boolean seenAnyMaze = false;
    private int nestedEnterCount = 0;

    private final List<Room> rooms = new ArrayList<>();
    private final List<Door> doors = new ArrayList<>();
    private final Set<String> seenRoomIds = new HashSet<>();

    private final Map<String, String> contextRuleName = new LinkedHashMap<>();
    private final Map<String, String> contextParent = new HashMap<>();
    private final Deque<String> contextStack = new ArrayDeque<>();
    private int contextSeq = 0;
    private int shortcutSeq = 0;
    private int deadEndSeq = 0;
    private int suggSeq = 0;
    private final Set<String> deadEndRoomIds = new HashSet<>();

    public MazeZoomGraph(Parser parser) {
        this.parser = parser;
    }

    private boolean isRecording() {
        return ENABLED && recording;
    }

    public void onEnterMaze(ATNState start, int tokenIndex) {
        if (!ENABLED || doneOnce) return;

        if (recording) {
            nestedEnterCount++;
            String parentContext = contextStack.peek();
            String contextId = "ctx" + (contextSeq++);
            contextRuleName.put(contextId, safeRuleName(start.ruleIndex));
            contextParent.put(contextId, parentContext);
            contextStack.push(contextId);
            registerRoom(start.stateNumber, tokenIndex, "🚪 Điểm vào\\n" + safeRuleName(start.ruleIndex), Shape.ROOM, contextId);
            return;
        }

        boolean isRootMaze = !seenAnyMaze;
        seenAnyMaze = true;
        boolean shouldStart = TARGET_RULE_NAME.isEmpty()
                ? isRootMaze
                : safeRuleName(start.ruleIndex).equals(TARGET_RULE_NAME);
        if (shouldStart) {
            recording = true;
            nestedEnterCount = 1;
            rooms.clear();
            doors.clear();
            seenRoomIds.clear();
            contextRuleName.clear();
            contextParent.clear();
            contextStack.clear();
            contextSeq = 0;
            shortcutSeq = 0;
            deadEndSeq = 0;
            deadEndRoomIds.clear();
            suggSeq = 0;
            registerRoom(start.stateNumber, tokenIndex, "🚪 Điểm vào\\n" + safeRuleName(start.ruleIndex), Shape.ROOM, null);
        }
    }

    public void onExitMaze() {
        if (!ENABLED || doneOnce || !recording) return;
        if (nestedEnterCount > 1) {
            contextStack.pop();
        }
        if (--nestedEnterCount == 0) {
            recording = false;
            doneOnce = true;
            dumpToFile();
        }
    }

    private String roomId(int stateNumber, int tokenIndex) {
        return "s" + stateNumber + "_" + tokenIndex;
    }

    private void registerRoom(int stateNumber, int tokenIndex, String label, Shape shape, String contextId) {
        String id = roomId(stateNumber, tokenIndex);
        if (seenRoomIds.add(id)) {
            rooms.add(new Room(id, label, shape, contextId));
        }
    }

    public void onVisitState(ATNState state, int tokenIndex, boolean isRuleStop, boolean atCaretHere) {
        if (!isRecording()) return;
        String ctx = contextStack.peek();
        if (isRuleStop) {
            registerRoom(state.stateNumber, tokenIndex, "🏁 Hết mê cung\\n@" + tokenIndex,
                    atCaretHere ? Shape.DECISION_OUTPUT : Shape.RULE_STOP, ctx);
        } else {
            registerRoom(state.stateNumber, tokenIndex, "phòng " + state.stateNumber + "\\n@" + tokenIndex, Shape.ROOM, ctx);
        }
    }

    public void onPasswordDoor(ATNState from, int fromTokenIndex, ATNState to, int toTokenIndex, String tokenLabel) {
        if (!isRecording()) return;
        String ctx = contextStack.peek();
        registerRoom(to.stateNumber, toTokenIndex, "phòng " + to.stateNumber + "\\n@" + toTokenIndex, Shape.ROOM, ctx);
        doors.add(new Door(roomId(from.stateNumber, fromTokenIndex), tokenLabel + "\\nmật khẩu",
                roomId(to.stateNumber, toTokenIndex), false));
    }

    public void onPasswordDoorRejected(ATNState from, int fromTokenIndex, String expectedLabel, String actualTokenLabel) {
        if (!isRecording() || !SHOW_DEAD_ENDS) return;
        String deadEndId = "dead" + (deadEndSeq++);
        rooms.add(new Room(deadEndId, "❌ Không khớp\\ncần: " + expectedLabel + "\\ngõ: " + actualTokenLabel,
                Shape.ROOM, contextStack.peek()));
        deadEndRoomIds.add(deadEndId);
        doors.add(new Door(roomId(from.stateNumber, fromTokenIndex), expectedLabel + "\\n(không khớp)", deadEndId, true));
    }

    public void onWildcardDoor(ATNState from, int fromTokenIndex, ATNState to, int toTokenIndex) {
        if (!isRecording()) return;
        String ctx = contextStack.peek();
        registerRoom(to.stateNumber, toTokenIndex, "phòng " + to.stateNumber + "\\n@" + toTokenIndex, Shape.ROOM, ctx);
        doors.add(new Door(roomId(from.stateNumber, fromTokenIndex), "bất kỳ\\nmật khẩu",
                roomId(to.stateNumber, toTokenIndex), false));
    }

    public void onFreeDoor(ATNState from, int tokenIndex, ATNState to, boolean hasCondition) {
        if (!isRecording()) return;
        String ctx = contextStack.peek();
        registerRoom(to.stateNumber, tokenIndex, "phòng " + to.stateNumber + "\\n@" + tokenIndex, Shape.ROOM, ctx);
        doors.add(new Door(roomId(from.stateNumber, tokenIndex), hasCondition ? "miễn phí\\n(có điều kiện)" : "miễn phí",
                roomId(to.stateNumber, tokenIndex), false));
    }

    public void onRuleDoorEnter(ATNState from, int fromTokenIndex, ATNState subRuleStart) {
        if (!isRecording()) return;
        String entryRoomId = roomId(subRuleStart.stateNumber, fromTokenIndex);
        doors.add(new Door(roomId(from.stateNumber, fromTokenIndex), "vào mê cung con", entryRoomId, false));
    }

    public void onRuleDoorExit(int subRuleIndex, ATNState followState, int exitTokenIndex) {
        if (!isRecording()) return;
        ATNState stopState = parser.getATN().ruleToStopState[subRuleIndex];
        String exitRoomId = roomId(stopState.stateNumber, exitTokenIndex);
        String ctx = contextStack.peek();
        registerRoom(followState.stateNumber, exitTokenIndex, "phòng " + followState.stateNumber + "\\n@" + exitTokenIndex, Shape.ROOM, ctx);
        doors.add(new Door(exitRoomId, "quay lại\\nmê cung chính", roomId(followState.stateNumber, exitTokenIndex), false));
    }

    public void onRuleDoorShortcutAtCaret(ATNState from, int fromTokenIndex, int subRuleIndex) {
        if (!isRecording()) return;
        String id = "shortcut" + (shortcutSeq++);
        rooms.add(new Room(id, "🌀 Mê cung con (VIP)\\n" + safeRuleName(subRuleIndex), Shape.DECISION_OUTPUT, contextStack.peek()));
        doors.add(new Door(roomId(from.stateNumber, fromTokenIndex), "đường tắt VIP", id, true));
    }

    /**
     * Mê cung con CHẾT HẲN (input lỗi, ví dụ gõ nhầm từ khoá bắt buộc) và
     * RuleResyncSkipper vừa "cứu" bằng cách nhảy tới {@code toTokenIndex} —
     * vẽ 1 nhánh nét đứt riêng biệt, KHÁC với {@link #onRuleDoorExit} bình
     * thường (đi ra đúng đường sống thật), để người xem sơ đồ phân biệt được
     * ngay đây là đường "vá lỗi" chứ không phải đường ATN thật sự tồn tại.
     * <p>
     * Vẽ từ ĐÚNG phòng "Điểm vào" của mê cung con vừa chết (khớp theo
     * {@code ruleToStartState} + {@code fromTokenIndex}, do {@link #onEnterMaze}
     * / {@link #onRuleDoorEnter} đã đăng ký) tới 1 phòng đánh dấu riêng
     * "⚡ Resync" — không lồng vào context nào của mê cung con đã chết (nó
     * chết rồi, không có gì bên trong để zoom), đặt ở khung cha hiện tại.
     */
    public void onRuleResync(int subRuleIndex, int fromTokenIndex, int toTokenIndex) {
        if (!isRecording()) return;
        ATNState startState = parser.getATN().ruleToStartState[subRuleIndex];
        String entryRoomId = roomId(startState.stateNumber, fromTokenIndex);
        String resyncId = "resync" + (deadEndSeq++);
        rooms.add(new Room(resyncId,
                "⚡ Resync\\nbỏ qua lỗi trong " + safeRuleName(subRuleIndex) + "\\n→ @" + toTokenIndex,
                Shape.ROOM, contextStack.peek()));
        deadEndRoomIds.add(resyncId);
        doors.add(new Door(entryRoomId, "resync\\n(bỏ qua token lỗi)", resyncId, true));
    }

    /**
     * Literal-resync tại 1 cửa mật khẩu đơn lẻ (xem
     * RuleResyncSkipper#findResyncPointForLabel) — khác {@link #onRuleResync}
     * (cứu cả 1 rule con chết hẳn ở ranh giới RuleTransition): đây là cứu
     * ĐÚNG 1 cửa đơn, không qua subrule nào, chỉ bỏ qua rác giữa đường để
     * khớp lại đúng loại token cửa này cần. Vẽ nét đứt riêng, nối thẳng tới
     * phòng đích thật ({@code to}) sau khi khớp — không tạo phòng đánh dấu
     * trung gian như onRuleResync, vì ở đây không có khái niệm "mê cung con
     * đã chết" để đánh dấu, chỉ có 1 cửa đã "nuốt rác" trước khi khớp.
     */
    public void onPasswordDoorResync(ATNState from, int fromTokenIndex, int resyncTokenIndex, ATNState to, String matchedTokenLabel) {
        if (!isRecording()) return;
        String ctx = contextStack.peek();
        int toTokenIndex = resyncTokenIndex + 1;
        registerRoom(to.stateNumber, toTokenIndex, "phòng " + to.stateNumber + "\\n@" + toTokenIndex, Shape.ROOM, ctx);
        doors.add(new Door(roomId(from.stateNumber, fromTokenIndex),
                "⚡ resync, bỏ qua rác\\nkhớp: " + matchedTokenLabel,
                roomId(to.stateNumber, toTokenIndex), true));
    }

    public void onCaretSuggestionHere(ATNState state, int tokenIndex) {
        if (!isRecording()) return;
        String id = roomId(state.stateNumber, tokenIndex);
        for (int i = 0; i < rooms.size(); i++) {
            Room r = rooms.get(i);
            if (r.id().equals(id)) {
                rooms.set(i, new Room(r.id(), r.label(), Shape.DECISION_OUTPUT, r.contextId()));
                return;
            }
        }
        registerRoom(state.stateNumber, tokenIndex, "phòng " + state.stateNumber + "\\n@" + tokenIndex, Shape.DECISION_OUTPUT, contextStack.peek());
    }

    public void onSuggestedToken(ATNState state, int tokenIndex, String tokenLabel) {
        if (!isRecording()) return;
        onCaretSuggestionHere(state, tokenIndex);
        String leafId = "sugg" + (suggSeq++);
        rooms.add(new Room(leafId, "✅ " + tokenLabel, Shape.DECISION_OUTPUT, contextStack.peek()));
        doors.add(new Door(roomId(state.stateNumber, tokenIndex), tokenLabel, leafId, false));
    }

    private String safeRuleName(int ruleIndex) {
        String[] names = parser.getRuleNames();
        return ruleIndex >= 0 && ruleIndex < names.length ? names[ruleIndex] : ("rule#" + ruleIndex);
    }

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

    public String render() {
        boolean hasCaretRoom = rooms.stream().anyMatch(r -> r.shape() == Shape.DECISION_OUTPUT);
        boolean findPath = FIND_PATH_TO_CARET && hasCaretRoom;
        Set<String> keep = findPath ? roomsThatCanReachCaret() : null;

        Map<String, Integer> otherDoorsFrom = new HashMap<>();
        if (findPath) {
            for (Door d : doors) {
                if (keep.contains(d.fromRoom()) && !keep.contains(d.toRoom())) {
                    otherDoorsFrom.merge(d.fromRoom(), 1, Integer::sum);
                }
            }
        }

        Map<String, List<Room>> roomsByContext = new LinkedHashMap<>();
        for (Room r : rooms) {
            if (keep != null && !keep.contains(r.id())) continue;
            roomsByContext.computeIfAbsent(r.contextId(), k -> new ArrayList<>()).add(r);
        }
        Map<String, List<String>> childrenOf = new LinkedHashMap<>();
        for (var e : contextParent.entrySet()) {
            String parentKey = e.getValue() == null ? "" : e.getValue();
            childrenOf.computeIfAbsent(parentKey, k -> new ArrayList<>()).add(e.getKey());
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

        renderContext(sb, null, roomsByContext, childrenOf, otherDoorsFrom, 1);

        sb.append("\n");
        for (Door d : doors) {
            if (keep != null && (!keep.contains(d.fromRoom()) || !keep.contains(d.toRoom()))) continue;
            sb.append("  ").append(d.fromRoom()).append(d.dashed() ? " -.->" : " -->")
                    .append("|\"").append(d.label()).append("\"| ").append(d.toRoom()).append("\n");
        }
        sb.append("\n  classDef output fill:#ffe08a,stroke:#c77700,stroke-width:2px;\n");
        sb.append("  classDef deadend fill:#ffd6d6,stroke:#c0392b,stroke-dasharray: 2 2;\n");
        for (Room r : rooms) {
            if (keep != null && !keep.contains(r.id())) continue;
            if (r.shape() == Shape.DECISION_OUTPUT) {
                sb.append("  class ").append(r.id()).append(" output;\n");
            }
            if (deadEndRoomIds.contains(r.id())) {
                sb.append("  class ").append(r.id()).append(" deadend;\n");
            }
        }
        return sb.toString();
    }

    private void renderContext(StringBuilder sb, String contextId, Map<String, List<Room>> roomsByContext,
                               Map<String, List<String>> childrenOf, Map<String, Integer> otherDoorsFrom, int indent) {
        List<Room> myRooms = roomsByContext.getOrDefault(contextId, List.of());
        List<String> children = childrenOf.getOrDefault(contextId == null ? "" : contextId, List.of());
        if (myRooms.isEmpty() && children.isEmpty()) return;

        String pad = "  ".repeat(indent);
        if (contextId != null) {
            sb.append(pad).append("subgraph ").append(contextId)
                    .append(" [\"🌀 Mê cung con: ").append(contextRuleName.get(contextId)).append("\"]\n");
        }
        for (Room r : myRooms) {
            String label = r.label();
            Integer otherDoors = otherDoorsFrom.get(r.id());
            if (otherDoors != null) {
                label += "\\n(+" + otherDoors + " cửa khác chưa đi, ẩn)";
            }
            String open, close;
            switch (r.shape()) {
                case DECISION_OUTPUT -> { open = "{{"; close = "}}"; }
                case RULE_STOP -> { open = "(("; close = "))"; }
                default -> { open = "(["; close = "])"; }
            }
            sb.append(pad).append("  ").append(r.id()).append(open).append("\"").append(label).append("\"").append(close).append("\n");
        }
        for (String child : children) {
            renderContext(sb, child, roomsByContext, childrenOf, otherDoorsFrom, indent + 1);
        }
        if (contextId != null) {
            sb.append(pad).append("end\n");
        }
    }

    public void dumpToFile() {
        try {
            Files.writeString(Path.of(FILE_PATH), render(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[MazeZoomGraph] Không ghi được file " + FILE_PATH + ": " + e.getMessage());
        }
    }
}