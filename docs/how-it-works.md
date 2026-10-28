# Cách gợi ý hoạt động

Hai tầng độc lập, kết quả ghép ở cuối. Tầng cú pháp cho biết *loại gì* hợp lệ tại con trỏ; tầng ngữ nghĩa cho biết *tên gì* đang có trong scope. Mã nằm ở `sql/completion/`.

## 1. Chuẩn bị đầu vào (`sql/completion/input`)

`CompletionInputPreparer` tách phần đang gõ dở (`prefix`) và biết có đang ở dạng `alias.` không (`dotMode`). Nếu con trỏ đang dính vào cuối một định danh thì tầng cú pháp dùng vị trí lùi 1 ký tự, để hỏi "ở đây có thể là gì" thay vì coi định danh dở là token đã gõ xong.

## 2. Tầng cú pháp: đi trên ATN (`sql/completion/syntactic`)

Lex câu gốc, không parse. Tìm token tại con trỏ (token caret). Sau đó `CompletionEngineBase` đi trên ATN của parser:

- `enterRule(rule, i)` trả lời: vào rule R ở token thứ i thì thoát ra được ở những token nào. Lần đi không chạm caret chỉ phụ thuộc (R, i) nên được nhớ lại (`ruleExitCache`); lần đi chạm caret sinh gợi ý phụ thuộc call stack nên không được nhớ. Grammar ANTLR không có đệ quy trái nên không thể quay lại đúng (R, i) khi đang tính.
- `walkRuleBody` duyệt sâu (DFS) trong thân rule trên các cặp (state, token index). Gặp transition khớp token thì tiến 1 token, gặp lời gọi rule con thì gọi `enterRule`, gặp epsilon hoặc predicate đúng thì đi tiếp không tốn token.
- Khi tới caret không còn token để khớp: mọi token mà transition ở đó chấp nhận chính là gợi ý.
- `ignoredTokens` (định danh, literal, toán tử, ngoặc, `;`) không bao giờ thành gợi ý keyword.
- `preferredRules` (`qualified_name`, `columnref`, `typename`, `func_name`, `table_alias`, `colid`, `any_name`): nếu caret nằm trong một rule loại này thì ghi lại *rule* thay vì bung ra từng token bên trong. Đây là cách biết "ở đây cần một tên bảng" mà không liệt kê cả trăm từ khoá có thể mở đầu một tên.
- Với keyword đơn có chuỗi bắt buộc theo sau (vd `NOT` → `EXISTS`), engine trả luôn chuỗi để gợi ý `not exists`.

Có sẵn biến thể `CompletionEngineWithFlowSet` dùng follow-set tính trước (`FollowSetsByState`) để cắt nhánh sớm; mặc định đang dùng `CompletionEngineDefault`. Chi tiết thuật toán: [syntactic.md](syntactic.md).

## 3. Tầng ngữ nghĩa: dựng scope (`sql/completion/semantic`)

`CursorTokenPatcher` lex câu và, nếu con trỏ rơi vào khoảng trống giữa hai token, chèn một token giả (kiểu Identifier) vào *danh sách token* chứ không nối chuỗi ký tự. Nối chuỗi từng làm lexer dính placeholder vào định danh liền kề. Sau đó parse, và `ScopeBuilder` (một parse-tree listener) đi qua cây:

- mỗi khối SELECT/UPDATE/DELETE/INSERT/MERGE... là một `Scope` có cha, chứa map alias → bảng;
- subquery và CTE trở thành `DerivedScope` với danh sách cột chiếu ra (`projectedColumns`, `hasWildcard`), để `alias.` mở ra đúng cột của subquery;
- phát hiện `u.` dang dở (dấu chấm cụt) và alias nó trỏ tới;
- subtree bị ANTLR vá lỗi (error node) thì không đăng ký alias từ đó, để không tin dữ liệu sai.

Mọi callback đều phải chịu null/thiếu: lỗi ở đâu thì bỏ đúng chỗ đó, không làm hỏng phần scope đã dựng được. Ngoại lệ không lọt ra ngoài; bị ghi vào log file.

## 4. Ghép và lọc (`sql/completion/suggestion`)

`PostgresSuggestionService` / `OracleSuggestionService` lấy tập rule khớp ở caret rồi chọn nguồn:

- keyword: từ token của tầng cú pháp;
- tên bảng/view: từ `SchemaIndex` (nạp một lần từ catalog, nạp lại sau DDL); bảng tìm được bằng tên trần theo search_path (Oracle: schema hiện tại) thì gợi ý tên trần, còn lại `schema.bảng`;
- điều kiện JOIN: ở `JOIN t x ON |` (và sau `AND` trong điều kiện đó), gợi ý `x.fk = y.pk` theo khoá ngoại giữa bảng vừa JOIN (token đứng trước `ON`) và các alias khác;
- cột: từ scope, dạng `alias.cột` kèm kiểu dữ liệu;
- alias: tự đề xuất tên alias cho bảng đang gõ (tránh trùng alias đã có và từ khoá: `order_notes` -> `on1`);
- `JOIN |`: cả cụm `bảng alias on điều kiện` cho bảng có quan hệ với bảng đã có trong FROM;
- index, sequence, trigger ở `DROP/ALTER INDEX|SEQUENCE|TRIGGER |`; giá trị enum ở `cột_enum = |`; field của `date_trunc(|`;
- `select *|` -> danh sách cột của FROM; `group by |` -> các biểu thức không gộp của SELECT;
- tên cần quote (`"OrderItems"`, cột trùng từ khoá) được chèn trong ngoặc kép; tên người dùng gõ được chuẩn hoá theo quy tắc của DB khi tra schema (`FROM USERS` vẫn ra bảng `users`);
- kiểu dữ liệu, hàm: từ catalog (Oracle dùng danh sách tĩnh vì không có view liệt kê hàm dựng sẵn); hàm kèm chữ ký (overload ít tham số nhất, `+N` nếu còn overload khác).

Rồi loại nhiễu theo ngữ cảnh (`MatchedRuleResolver`): định danh đã đóng bằng khoảng trắng thì không gợi ý tiếp, `varchar(|)` không gợi ý kiểu mới, tên sequence/index không gợi ý tên bảng, partition bound không gợi ý cột, v.v.

## 5. Xếp hạng (`sql/completion/ranking`)

`SuggestFilter` so sánh tuần tự, bậc sau chỉ xét khi bậc trước bằng nhau:

1. loại khớp: chính xác < tiền tố < fuzzy (subsequence, ký tự đầu phải ở đầu 1 từ: `id` khớp `user_id`, `us` không khớp `products`);
2. thói quen rõ ràng (đã chọn đủ nhiều lần gần đây) đứng trước;
3. độ khít fuzzy (phạt khoảng trống, thưởng đầu từ/camelCase);
4. loại gợi ý (điều kiện JOIN < alias < cột < bảng < keyword...);
5. khoảng cách scope: cột của scope tại caret trước cột của scope cha (subquery tương quan);
6. trọng số frecency;
7. độ dài tên, chỉ để phá hoà cuối.

Frecency (`CompletionHistory`): trọng số = min(số lần chọn, 10) × 3, suy giảm theo thời gian với chu kỳ nửa 14 ngày. Lưu trong `~/.sqlctx/completion_history.properties`, không gửi đi đâu.

## Giới hạn thời gian

Gợi ý chạy ở mỗi phím gõ nên mỗi tầng có budget (`CompletionBudget`, mặc định 300ms): hết giờ thì tầng cú pháp trả rỗng (không gợi ý keyword), tầng ngữ nghĩa bỏ scope.

Để gõ ở cuối câu dài vẫn nhanh:
- `IncrementalExitCache`: bảng "vào rule R ở token i thì ra ở đâu" giữ qua các lần gõ khi các token trước caret không đổi - chỉ chuỗi rule chứa caret phải đi lại. Đúng vì engine chỉ cất kết quả không chạm caret (chỉ đọc token trước caret).
- `CandidatesCache`: menu gợi ý và dòng báo lỗi cùng 1 phím dùng chung 1 lần chạy engine.
- `TwoStageParse`: parse ngữ nghĩa bằng SLL; lỗi thì thử SLL với 1 định danh giả sau caret ("... and|" -> "... and x"), chỉ khi vẫn lỗi mới parse LL.

`IncrementalCacheEquivalenceTest` giả lập gõ ~200 câu và so kết quả có cache với tính lại từ đầu.

## Dòng gợi ý và Language Server

`InputHints` tính chữ ký hàm bao quanh con trỏ và lỗi cú pháp thật: đoạn đã gõ không còn là phần đầu của câu hợp lệ nào (engine cú pháp không tới được cuối), lỗi chỉ do câu chưa gõ xong thì không báo. REPL hiện nó ở dòng trên status bar và gạch chân token lỗi; `sql/lsp/SqlLanguageServer` dùng cùng các phần đó cho completion, signatureHelp và diagnostics.

## Các phần còn lại

- `sql/repl` và `ui`: menu gợi ý vẽ bằng escape code (`sql/repl/MenuCompleter` dùng `ui/TerminalMenu`), tô màu bằng cùng lexer của dialect (`sql/repl/SqlHighlighter`).
- `ui`: bảng kết quả (`DataViewTable`) tự cắt theo độ rộng terminal, tính độ rộng ký tự CJK/emoji đúng; status bar dưới cùng.
- `dialect`: `DialectAdapter` gom mọi thứ phụ thuộc DB (kết nối, SQL của `\l \du \d`, banner, lexer, gợi ý).
