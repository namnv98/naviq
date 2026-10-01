# Cách gợi ý hoạt động

Hai tầng độc lập, kết quả ghép ở cuối. Tầng cú pháp cho biết *loại gì* hợp lệ tại con trỏ; tầng ngữ nghĩa cho biết *tên gì* đang có trong scope. Mã nằm ở `completion/`.

## 1. Chuẩn bị đầu vào (`completion/input`)

`CompletionInputPreparer` tách phần đang gõ dở (`prefix`) và biết có đang ở dạng `alias.` không (`dotMode`). Nếu con trỏ đang dính vào cuối một định danh thì tầng cú pháp dùng vị trí lùi 1 ký tự, để hỏi "ở đây có thể là gì" thay vì coi định danh dở là token đã gõ xong.

## 2. Tầng cú pháp: đi trên ATN (`completion/syntactic`)

Lex câu gốc, không parse. Tìm token tại con trỏ (token caret). Sau đó `CompletionEngineBase` đi trên ATN của parser:

- `enterRule(rule, i)` trả lời: vào rule R ở token thứ i thì thoát ra được ở những token nào. Lần đi không chạm caret chỉ phụ thuộc (R, i) nên được nhớ lại (`ruleExitCache`); lần đi chạm caret sinh gợi ý phụ thuộc call stack nên không được nhớ. Grammar ANTLR không có đệ quy trái nên không thể quay lại đúng (R, i) khi đang tính.
- `walkRuleBody` duyệt sâu (DFS) trong thân rule trên các cặp (state, token index). Gặp transition khớp token thì tiến 1 token, gặp lời gọi rule con thì gọi `enterRule`, gặp epsilon hoặc predicate đúng thì đi tiếp không tốn token.
- Khi tới caret không còn token để khớp: mọi token mà transition ở đó chấp nhận chính là gợi ý.
- `ignoredTokens` (định danh, literal, toán tử, ngoặc, `;`) không bao giờ thành gợi ý keyword.
- `preferredRules` (`qualified_name`, `columnref`, `typename`, `func_name`, `table_alias`, `colid`, `any_name`): nếu caret nằm trong một rule loại này thì ghi lại *rule* thay vì bung ra từng token bên trong. Đây là cách biết "ở đây cần một tên bảng" mà không liệt kê cả trăm từ khoá có thể mở đầu một tên.
- Với keyword đơn có chuỗi bắt buộc theo sau (vd `NOT` → `EXISTS`), engine trả luôn chuỗi để gợi ý `not exists`.

Có sẵn biến thể `CompletionEngineWithFlowSet` dùng follow-set tính trước (`FollowSetsByState`) để cắt nhánh sớm; mặc định đang dùng `CompletionEngineDefault`. Chi tiết thuật toán: [syntactic.md](syntactic.md).

## 3. Tầng ngữ nghĩa: dựng scope (`completion/semantic`)

`CursorTokenPatcher` lex câu và, nếu con trỏ rơi vào khoảng trống giữa hai token, chèn một token giả (kiểu Identifier) vào *danh sách token* chứ không nối chuỗi ký tự. Nối chuỗi từng làm lexer dính placeholder vào định danh liền kề. Sau đó parse, và `ScopeBuilder` (một parse-tree listener) đi qua cây:

- mỗi khối SELECT/UPDATE/DELETE/INSERT/MERGE... là một `Scope` có cha, chứa map alias → bảng;
- subquery và CTE trở thành `DerivedScope` với danh sách cột chiếu ra (`projectedColumns`, `hasWildcard`), để `alias.` mở ra đúng cột của subquery;
- phát hiện `u.` dang dở (dấu chấm cụt) và alias nó trỏ tới;
- subtree bị ANTLR vá lỗi (error node) thì không đăng ký alias từ đó, để không tin dữ liệu sai.

Mọi callback đều phải chịu null/thiếu: lỗi ở đâu thì bỏ đúng chỗ đó, không làm hỏng phần scope đã dựng được. Ngoại lệ không lọt ra ngoài; bị ghi vào log file.

## 4. Ghép và lọc (`completion/suggestion`)

`PostgresSuggestionService` / `OracleSuggestionService` lấy tập rule khớp ở caret rồi chọn nguồn:

- keyword: từ token của tầng cú pháp;
- tên bảng/view: từ `SchemaIndex` (nạp một lần từ catalog, nạp lại sau DDL);
- cột: từ scope, dạng `alias.cột` kèm kiểu dữ liệu;
- alias: tự đề xuất tên alias cho bảng đang gõ;
- kiểu dữ liệu, hàm: từ catalog (Oracle dùng danh sách tĩnh vì không có view liệt kê hàm dựng sẵn).

Rồi loại nhiễu theo ngữ cảnh (`MatchedRuleResolver`): định danh đã đóng bằng khoảng trắng thì không gợi ý tiếp, `varchar(|)` không gợi ý kiểu mới, tên sequence/index không gợi ý tên bảng, partition bound không gợi ý cột, v.v.

## 5. Xếp hạng (`completion/ranking`)

`SuggestFilter` so sánh tuần tự, bậc sau chỉ xét khi bậc trước bằng nhau:

1. loại khớp: chính xác < tiền tố < fuzzy (subsequence);
2. thói quen rõ ràng (đã chọn đủ nhiều lần gần đây) đứng trước;
3. độ khít fuzzy (phạt khoảng trống, thưởng đầu từ/camelCase);
4. loại gợi ý (alias < cột < bảng < keyword...);
5. trọng số frecency;
6. độ dài tên, chỉ để phá hoà cuối.

Frecency (`CompletionHistory`): trọng số = min(số lần chọn, 10) × 3, suy giảm theo thời gian với chu kỳ nửa 14 ngày. Lưu trong `~/.sqlctx/completion_history.properties`, không gửi đi đâu.

## Các phần còn lại

- `cli/terminal`: menu gợi ý vẽ bằng escape code (`MenuCompleter`, `TerminalMenu`), tô màu bằng cùng lexer của dialect (`SqlHighlighter`).
- `cli/view`: bảng kết quả (`DataViewTable`) tự cắt theo độ rộng terminal, tính độ rộng ký tự CJK/emoji đúng; status bar dưới cùng.
- `dialect`: `DialectAdapter` gom mọi thứ phụ thuộc DB (kết nối, SQL của `\l \du \d`, banner, lexer, gợi ý).
