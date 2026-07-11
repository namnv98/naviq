# ATN giải thích bằng ẩn dụ phòng và cửa

## Tưởng tượng grammar là 1 tấm bản đồ trò chơi

Bạn đang chơi 1 trò chơi: mỗi lượt bạn đứng ở **1 căn phòng**, và từ phòng đó có vài **cánh cửa** dẫn sang phòng khác.

Để đi qua 1 cánh cửa, có 3 loại:

1. **Cửa cần mật khẩu** — bạn phải nói đúng 1 từ cụ thể (vd phải nói "SELECT") thì cửa mới mở, và bạn *tốn 1 lượt nói*
   để qua cửa đó.
2. **Cửa miễn phí** — mở sẵn, bạn cứ bước qua, không cần nói gì, không tốn lượt nào cả.
3. **Cửa dẫn vào 1 mê cung con** — bước qua cửa này nghĩa là bạn phải đi hết 1 mê cung nhỏ khác trước (đi qua nhiều
   phòng, nói nhiều mật khẩu khác), xong xuôi mới được quay lại mê cung chính, đúng tại điểm ngay sau cửa, để tiếp tục
   đi tiếp.

**ATN chính là tấm bản đồ này.** Mỗi rule trong grammar là 1 mê cung. `RuleTransition` là "cửa dẫn vào mê cung con" (mê
cung con = rule khác). Cửa miễn phí chính là cái người ta gọi là "epsilon" — chỉ là 1 cái tên kỹ thuật cho "cửa không
cần nói gì".

## Việc engine đang làm

Bạn đưa cho engine 1 câu bạn đã nói (`SELECT name`), engine **giả vờ đi trong mê cung** đúng theo những từ đó — mỗi từ
bạn nói khớp với đúng 1 cửa cần mật khẩu, nó bước qua. Khi bạn nói hết câu (caret), nó đang đứng ở **1 căn phòng cụ thể
nào đó**. Lúc đó nó nhìn quanh phòng: *"phòng này có những cửa nào?"* — tên mật khẩu trên mỗi cửa đó chính là **gợi ý**
cho bạn.

## Sơ đồ minh hoạ

```mermaid
flowchart LR
    A([Bắt đầu]) -->|SELECT<br/>mật khẩu| B([Phòng 1])
    B -->|vào mê cung con| C([Điểm vào])

    subgraph M[" Mê cung con: columnref "]
        C -->|Identifier<br/>mật khẩu| D([Hết mê cung con])
    end

    D -->|quay lại mê cung chính| E{Caret!<br/>hết từ}
    E -->|nhìn quanh,<br/>thấy 2 cửa mở| F([COMMA<br/>mật khẩu, lặp])
    E --> G([FROM<br/>miễn phí])
    F --> H[/Kết quả:<br/>suggestedTokens = COMMA, FROM/]
    G --> H
```

## Đi qua từng hàm trong code, theo đúng câu chuyện trên

**`collectCandidates(caretTokenIndex)`** — đây là lúc bạn bắt đầu ván chơi. Nó dọn sạch kết quả cũ, đọc lại toàn bộ
những lời bạn đã nói (`readTokens`), rồi bước chân vào mê cung chính (rule gốc của grammar), tại đúng ô đầu tiên trên
bàn cờ (`tokenIndex = 0`). Mọi thứ xảy ra sau đó chỉ là hệ quả của 1 lời gọi duy nhất: `enterRule(...)`.

**`enterRule(start, tokenIndex)`** — đây là hành động "bước vào 1 mê cung". Trước khi đi, nó tự hỏi: *"mình đã từng đứng
đúng chỗ này trong mê cung này chưa?"* Nếu rồi, khỏi mất công đi lại, lấy ngay kết quả cũ ra dùng. Nếu chưa, nó đánh dấu
tạm "chỗ này coi như ngõ cụt" (phòng khi đi vòng quay lại đúng chỗ, khỏi lặp vô tận), rồi mới thật sự đi: hết lời để nói
thì nhìn quanh lấy gợi ý (`handleReachedCaretInsideRule`), còn lời thì dò cửa đi tiếp (`walkRuleBody`). Xong xuôi, nó
xoá cái đánh dấu tạm, ghi đè bằng kết quả thật.

**`handleReachedCaretInsideRule(start, tokenIndex)`** — đây là khoảnh khắc bạn vừa bước chân vào 1 mê cung thì hết lời
để nói luôn (chưa kịp đi thêm bước nào bên trong). Nếu mê cung này là loại "đặc biệt" (như `columnref`,
`qualified_name` — những thứ có ý nghĩa nghiệp vụ), nó không thèm liệt kê từng cửa mật khẩu trần trụi bên trong nữa, mà
chốt luôn: "bạn đang cần điền 1 thứ thuộc loại này". Còn nếu là mê cung bình thường, nó vẫn phải dò cửa như thường (gọi
`walkRuleBody`) để biết cửa nào đang mở.

**`canExitWithoutConsumingToken(start)`** — câu hỏi ở đây rất cụ thể: *"đứng ngay tại cửa vào mê cung này, tôi có thể
coi như xong luôn mà không cần nói thêm lời nào không?"* Nó đi thử các cửa miễn phí và cửa vào mê cung con khác (đều
không tốn lời), hễ chạm được "hết mê cung" thì đúng — trả lời có. Gặp cửa mật khẩu là dừng ngay nhánh đó, vì cửa mật
khẩu đồng nghĩa "còn nợ ít nhất 1 lời".

**`walkRuleBody(start, startTokenIndex)`** — đây là màn dò đường thật sự bên trong 1 mê cung. Nó đi từng phòng một, và
với mỗi phòng, xét hết các cửa của phòng đó rồi định tuyến sang đúng người xử lý cửa loại đó. Nếu 1 phòng nào chạm tới "
hết mê cung", nó ghi lại đây là 1 điểm có thể thoát ra.

**`handleRuleDoor`** — xử lý đúng cửa vào mê cung con: đi hết mê cung con đó (gọi lại `enterRule`), rồi bất kể đi ra ở
đâu, luôn tiếp tục từ đúng điểm ngay sau cửa (`rt.followState`) trong mê cung chính.

**`handleFreeDoorWithCondition`** và **`handleFreeDoor`** — đây là 2 kiểu cửa miễn phí: 1 loại có điều kiện đi kèm (mở
nếu điều kiện đúng), 1 loại mở sẵn hoàn toàn. Cả 2 đều không tốn lời nói, chỉ là bước qua rồi tiếp tục.

**`handlePasswordDoor`** — đây là nơi mọi gợi ý thật sự được sinh ra. Nếu hết lời để nói rồi, tên mật khẩu trên cửa này
chính là gợi ý, trừ khi nó nằm trong danh sách bị bỏ qua. Nếu còn lời, nó so xem lời tiếp theo có đúng mật khẩu không —
đúng thì bước qua (tốn 1 lời), sai thì im lặng, coi như ngõ cụt.

## Vì sao phải đi từ đầu, không nhảy thẳng tới Caret

Đúng là 1 khi đã biết chắc đang đứng ở đúng 1 ngã rẽ cụ thể, danh sách cửa ở đó là cố định — không quan tâm chữ trước đó
là gì. Nhưng vấn đề là: **làm sao biết mình đang đứng ở đúng ngã rẽ nào?**

Ví dụ 2 câu này đều "vừa gõ xong 1 identifier, hết từ":

- `SELECT name` → đang ở ngã rẽ `COMMA` / `FROM`
- `SELECT name FROM users WHERE id` → đang ở 1 ngã rẽ khác hẳn (so sánh `=`, `>`, `IN`...)

Cả 2 câu kết thúc giống hệt nhau về hình thức ("vừa gõ 1 identifier"), nhưng là 2 phòng khác nhau, cửa khác nhau hoàn
toàn. Không đi từ đầu thì không thể phân biệt được đang ở trường hợp nào — phải đi đúng đường mới xác định được đang
đứng ở phòng nào.

Tóm lại: đi từ đầu = xác định **đang đứng ở đâu**. Danh sách cửa tại 1 vị trí cụ thể thì đúng là tĩnh, tính 1 lần dùng
lại mãi (đây là lý do bản gốc có cache `FollowSetsByState`) — nhưng đó là chuyện khác, không thay thế được việc phải xác
định vị trí trước.

Đi từ đầu còn giúp phát hiện input sai ngữ pháp: nếu gõ câu không hợp lệ (vd thiếu tên cột), tới cửa cần `Identifier` mà
không có token đó, code không push đi tiếp đâu cả — ngõ cụt, không phòng nào đạt tới, không có gợi ý (đúng như kỳ vọng:
câu sai thì không nên gợi ý bừa).

## Ví dụ mở rộng: nhiều nhánh hơn (vòng lặp + rẽ nhánh WHERE tuỳ chọn)

Grammar đầy đủ hơn: `select_stmt : SELECT columnref (COMMA columnref)* FROM qualified_name (WHERE bool_expr)? ;`

```mermaid
flowchart LR
    A([Bắt đầu]) -->|SELECT<br/>mật khẩu| B([Phòng 1])
    B -->|vào mê cung con| C1([Điểm vào])

    subgraph M1[" Mê cung con: columnref (cột) "]
        C1 -->|Identifier<br/>mật khẩu| D1([Hết mê cung con])
    end

    D1 -->|quay lại mê cung chính| E{Ngã rẽ 1<br/>hết từ?}
    E -->|COMMA<br/>mật khẩu, lặp lại| C1
    E -->|FROM<br/>miễn phí| G([Phòng sau FROM])
    G -->|vào mê cung con| H([Điểm vào])
    subgraph M2[" Mê cung con: qualified_name (tên bảng) "]
        H -->|Identifier<br/>mật khẩu| I([Hết mê cung con])
    end

    I -->|quay lại mê cung chính| J{Ngã rẽ 2<br/>hết từ?}
    J -->|WHERE<br/>mật khẩu| K([Phòng sau WHERE])
    J -->|miễn phí| L[/Kết thúc câu/]
    K -->|vào mê cung con| P([Điểm vào])
    subgraph M3[" Mê cung con: bool_expr (điều kiện) "]
        P -->|columnref, = , giá trị| Q([Hết mê cung con])
    end
    Q -->|quay lại mê cung chính| R[/Kết thúc câu/]
    E -.->|gợi ý tại Ngã rẽ 1| S1[/suggestedTokens = COMMA, FROM/]
    J -.->|gợi ý tại Ngã rẽ 2| S2[/suggestedTokens = WHERE, EOF/]
```

### Đi từng bước qua sơ đồ trên

**Bước 1 — `Bắt đầu → Phòng 1`**: nói `SELECT` (cửa mật khẩu), tốn 1 lời, bước qua.

**Bước 2 — `Phòng 1 → Điểm vào (columnref)`**: gặp cửa vào mê cung con — `enterRule()` được gọi đệ quy, đi vào mê cung
`columnref`.

**Bước 3 — bên trong `M1`**: nói `Identifier` (tên cột đầu tiên), tốn 1 lời, chạm `Hết mê cung con` (`RULE_STOP` của
`columnref`).

**Bước 4 — `quay lại mê cung chính`**: thoát mê cung con, code nhảy đúng tới `rt.followState` — chính là **Ngã rẽ 1**.

**Bước 5 — tại Ngã rẽ 1, nếu hết từ**: nhìn quanh thấy 2 cửa mở — `COMMA` và `FROM` → `suggestedTokens = {COMMA, FROM}`.

**Bước 6a — nếu người dùng gõ tiếp `,`**: đi theo cửa `COMMA`, quay ngược lại đúng `Điểm vào (columnref)` — vòng lặp
`(COMMA columnref)*` lặp thêm 1 vòng, quay lại Bước 2.

**Bước 6b — nếu gõ tiếp `FROM`**: cửa mật khẩu bình thường (không dẫn vào mê cung con) — sang `Phòng sau FROM`.

**Bước 7 — `Phòng sau FROM → Điểm vào (qualified_name)`**: lại 1 cửa vào mê cung con khác — đệ quy `enterRule()` lần
nữa, lần này vào mê cung `qualified_name`.

**Bước 8 — bên trong `M2`**: nói `Identifier` (tên bảng), chạm `Hết mê cung con`.

**Bước 9 — `quay lại mê cung chính`**: thoát mê cung con lần 2, nhảy tới `followState` mới — đây chính là **Ngã rẽ 2**,
hoàn toàn khác Ngã rẽ 1 dù cùng "vừa nói xong 1 Identifier, hết từ".

**Bước 10 — tại Ngã rẽ 2, nếu hết từ**: nhìn quanh thấy 2 cửa — `WHERE` và "kết thúc câu" (`EOF`/miễn phí) →
`suggestedTokens = {WHERE, EOF}`.

**Bước 11a — nếu gõ tiếp `WHERE`**: sang `Phòng sau WHERE`, lại gặp cửa vào mê cung con `bool_expr`, lặp lại đúng cơ
chế "vào mê cung con → quay lại mê cung chính" lần thứ 3, rồi tới `Kết thúc câu`.

**Bước 11b — nếu không gõ `WHERE`**: đi thẳng cửa miễn phí, câu kết thúc luôn tại Ngã rẽ 2.

**Điểm mấu chốt xuyên suốt cả 11 bước**: mỗi lần "vào mê cung con → quay lại mê cung chính" đều dùng đúng 1 cơ chế duy
nhất trong code — `handleRuleDoor` gọi đệ quy `enterRule()`, rồi resume tại `rt.followState`. Số lượng mê cung con lồng
nhau (ở đây là 3) không làm code phức tạp hơn, vì đó chỉ là cùng 1 đoạn code chạy lặp lại nhiều lần.

## Ghép thẳng vào tên biến trong code

| Ẩn dụ                                        | Tên thật trong code                                                                          |
|----------------------------------------------|----------------------------------------------------------------------------------------------|
| Căn phòng                                    | `ATNState`                                                                                   |
| Cửa cần mật khẩu                             | `handlePasswordDoor()` — ăn 1 token thật (`AtomTransition`/`SetTransition`/...)              |
| Cửa miễn phí                                 | `handleFreeDoor()` — epsilon transition (`t.isEpsilon()`)                                    |
| Cửa miễn phí có điều kiện                    | `handleFreeDoorWithCondition()` — semantic predicate (`PredicateTransition`)                 |
| Cửa dẫn vào mê cung con                      | `handleRuleDoor()` — gọi rule con qua `RuleTransition`                                       |
| "Đi hết mê cung con, quay lại mê cung chính" | `enterRule()` chạy đệ quy xong, trả về `exitTok`, code `queue.push(rt.followState, exitTok)` |
| "Nhìn quanh phòng liệt kê tên các cửa"       | đoạn code trong `handlePasswordDoor()`: `if (atCaret) { suggestedTokens.add(sym); }`         |

## Hai khái niệm còn lại: "đã đi qua chưa" và "có thể coi như xong không"

**"Đã đi qua đúng chỗ này chưa?"** — mỗi mê cung, tại mỗi vị trí lời nói cụ thể, chỉ cần dò 1 lần. Lần sau có ai (kể cả
1 nhánh khác) hỏi lại đúng câu đó, cứ trả lời y hệt lần trước, khỏi dò lại. Đây là `ruleExitCache` trong code — nó nhớ
đúng theo cặp (mê cung, vị trí lời nói), và có 1 mẹo nhỏ: trong lúc đang dò dở, nó tạm ghi "coi như ngõ cụt" vào chỗ nhớ
đó trước, để nếu lỡ đi vòng quay lại đúng chỗ đang dở này (mê cung có đường vòng), nó không bị lặp mãi — dò xong thật sự
rồi mới ghi đè lại bằng kết quả thật.

**"Mê cung này có thể coi như xong ngay không, dù chưa nói thêm gì?"** — có những mê cung mà bạn vừa bước vào là có thể
coi như xong luôn (không phải cửa nào cũng bắt buộc phải mở), nhờ toàn đường miễn phí dẫn thẳng ra ngoài. Đây là
`canExitWithoutConsumingToken()` — nó chỉ đi theo cửa miễn phí (và cửa vào mê cung con khác, vì cửa đó cũng không tốn
lời) để dò xem có lối nào ra ngoài mà không cần mở cửa mật khẩu nào không. Nếu KHÔNG có lối như vậy, thì mê cung cha
phải hiểu là "còn nợ ít nhất 1 lời nữa bên trong", và không được phép coi như xong.

## Engine không duyệt hết mọi cửa — nó lọc theo đúng từ kế tiếp

Một câu hỏi hay: *"nó có duyệt hết mọi nhánh của ATN không, hay chỉ đi theo đúng từ kế tiếp?"*

Câu trả lời: **còn từ để nói thì lọc chặt, chỉ hết từ (tại caret) mới buộc phải liệt kê hết**.

Nhìn `handlePasswordDoor`:

```java
}else if(label.contains(tokens.get(cur.tokenIndex).

getType())){
        // Còn lời để nói: đúng mật khẩu thì bước qua, tốn 1 lời.
        queue.

push(new PipelineEntry(t.target, cur.tokenIndex +1));
        }
// Sai mật khẩu -> không push gì cả -> nhánh này chết ở đây.
```

Với 1 phòng có nhiều cửa mật khẩu khác nhau, chỉ cửa nào trùng đúng tên với từ kế tiếp mới được push tiếp — cửa còn lại
bị lờ đi ngay, không vào hàng đợi BFS nữa. Ngược lại, 2 loại cửa còn lại thì **luôn đi, không cần kiểm tra gì**, vì
chúng không tốn lời:

```java
}else if(t.isEpsilon()){
        queue.

push(new PipelineEntry(t.target, cur.tokenIndex));  // luôn đi
        }
```

Chỉ khi hết từ hẳn (đúng tại caret), engine mới hết cách lọc — lúc đó nó buộc phải liệt kê **toàn bộ** cửa mật khẩu còn
lại trong hàng đợi làm gợi ý, vì không còn từ nào để so sánh nữa.

| Giai đoạn          | Cửa mật khẩu                               | Cửa miễn phí / vào mê cung con           |
|--------------------|--------------------------------------------|------------------------------------------|
| Còn từ để nói      | Lọc chặt — chỉ đi đúng cửa khớp từ kế tiếp | Luôn đi, không cần lọc                   |
| Hết từ (tại caret) | Liệt kê hết, không lọc (đây là gợi ý)      | Luôn đi (không tạo gợi ý, chỉ dẫn đường) |

### Nhưng vẫn có lúc 1 từ khớp được nhiều cửa cùng lúc

Vì cửa miễn phí luôn đi vô điều kiện, có những lúc BFS đang "sống" ở **nhiều phòng khác nhau cùng lúc**, và nếu 2 phòng
đó tình cờ cùng cần đúng 1 mật khẩu, cả 2 đều qua cửa được — không phải chỉ 1 nhánh thắng.

Ví dụ điển hình: `qualified_name : Identifier (DOT Identifier)? ;` — dấu `?` tạo ra 1 cửa miễn phí dẫn tới 2 phòng khác
nhau ngay từ đầu:

```
S0 --epsilon--> S1 --Identifier--> RULE_STOP                      (nhánh: KHÔNG có schema)
S0 --epsilon--> S2 --Identifier--> S3 --DOT--> S4 --Identifier--> RULE_STOP   (nhánh: CÓ schema)
```

BFS đứng ở cả `S1` lẫn `S2` cùng lúc (do 2 cửa miễn phí từ `S0`, luôn đi vô điều kiện). Gõ `public` (1 `Identifier`): *
*cả 2 phòng đều khớp**, cả 2 cùng được push tiếp:

| Nhánh | Đang ở đâu sau khi gõ `public` | Diễn giải                                                  |
|-------|--------------------------------|------------------------------------------------------------|
| A     | `RULE_STOP`                    | `public` được hiểu là **tên bảng**                         |
| B     | `S3` (chờ `DOT`)               | `public` được hiểu là **tên schema**, đang chờ `.tên_bảng` |

Nếu caret dừng ngay đây, `suggestedTokens` sẽ gộp cả gợi ý từ nhánh A (những gì hợp lệ sau khi `qualified_name` đã xong,
vd `WHERE`/`EOF`) **lẫn** gợi ý từ nhánh B (`DOT`) — cả 2 xuất hiện cùng lúc, vì code không có khái niệm "chỉ 1 nhánh
được thắng", nó chỉ đơn giản là **mỗi nhánh đang sống tự lọc theo từ kế tiếp cho riêng nó** — và nếu nhiều nhánh cùng
sống, cùng khớp, thì tất cả cùng tiếp tục.

Không có gì thần bí cả — cả file `AntlrCompletionEngineSimple.java` chỉ đang làm đúng 1 việc: **đi theo đúng những từ
bạn đã gõ trong tấm bản đồ đó, rồi khi hết từ để đi, nhìn quanh xem còn cửa nào mở** — tên mật khẩu trên các cửa đó
chính là kết quả trả về.