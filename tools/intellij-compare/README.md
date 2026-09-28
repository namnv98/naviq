# So sánh completion của sqlctx với IntelliJ

Chạy completion của IntelliJ (plugin Database Tools, bản Ultimate) trên đúng các câu SQL trong test
Postgres, cùng một schema fixture, rồi so với gợi ý của sqlctx. Dùng để phát hiện chỗ tool thiếu/thừa
gợi ý so với một IDE chuẩn.

Chỉ so trong phạm vi schema fixture: relation (bảng/view/materialized view), cột (kèm bảng/alias
đứng trước) và role. Hàm built-in, bảng `pg_catalog` mà IntelliJ biết thêm và keyword không được so.
Khi hai bên khác nhau, chạy câu SQL tương ứng trên Postgres thật để biết bên nào đúng.

## Yêu cầu

- IntelliJ IDEA **Ultimate** (có Database Tools), đã mở project này.
- Docker (Postgres trong `docker-compose.yml`, cổng `54329`).
- Python 3, Maven.

## Các bước

Mọi lệnh chạy từ thư mục gốc repo.

### 1. Dựng DB fixture

```bash
docker compose up -d postgres
docker exec -i sqlctx-postgres psql -U tester -d naviq -v ON_ERROR_STOP=1 < tools/intellij-compare/fixture.sql
```

### 2. Tạo data source trong IntelliJ

Cửa sổ *Database* → **+** → *Data Source* → *PostgreSQL*:

| Trường   | Giá trị          |
|----------|------------------|
| Host     | `localhost`      |
| Port     | `54329`          |
| User     | `tester`         |
| Password | `x`              |
| Database | `sqlctx_fixture` |

Tên data source phải **chứa `sqlctx_fixture`** (tên mặc định `sqlctx_fixture@localhost` là được).
Đợi introspect xong (thấy `users`, `orders`… trong `public`).

Chuột phải data source → *New* → *Query Console*. Để tab console đó **mở** (không cần đang chọn).

### 3. Trích danh sách câu SQL từ test

```bash
python3 tools/intellij-compare/extract_cases.py
```

Tạo `target/ij-compare/ij-cases.txt`.

### 4. Chạy dump trong IntelliJ

*Tools* → *IDE Scripting Console* → chọn **Groovy**, dán dòng dưới (sửa đường dẫn repo), đặt con
trỏ trên dòng đó và bấm **Ctrl+Enter**:

```groovy
evaluate(new File("/đường/dẫn/tới/repo/tools/intellij-compare/ij-completion-dump.groovy"))
```

Console in `Đang chạy N câu - chờ thông báo Xong`. Trong lúc chạy (~5 phút cho ~200 câu) **không gõ
phím/click trong IntelliJ**. Xong sẽ có thông báo, kết quả ở `target/ij-compare/ij-out.tsv`; nội dung
console và thiết lập auto-complete được trả lại như cũ. Kết quả ghi dần ra `ij-out.partial.tsv`;
lỗi (nếu có) ghi ra `ij-error.txt` kèm stack trace.

Lỗi hay gặp:

| Triệu chứng | Nguyên nhân / cách xử lý |
|---|---|
| `Argument for @NotNull parameter 'module' ... DefaultGroovyScriptRunner` | Đã chạy bằng nút Run ▶ / Shift+F10 (chạy như Run Configuration). Phải dùng **Ctrl+Enter** trong IDE Scripting Console. |
| Chỉ dòng con trỏ được chạy, báo `unresolved reference` | Đang dán cả script vào console. Chỉ dán dòng `evaluate(...)` ở trên. |
| `Không thấy tab Query Console nào đang mở ...` | Chưa mở Query Console của data source, hoặc tên data source không chứa `sqlctx_fixture`. |
| `Không thấy target/ij-compare/ij-cases.txt ...` | Chưa chạy bước 3, hoặc project đang mở không phải repo này. |
| `FileNotFoundException` | Đường dẫn trong `evaluate(...)` sai - IntelliJ phải đọc được file (không dùng thư mục tạm của tiến trình khác). |

Không dùng console **Kotlin** để chạy script: gọi lồng script Kotlin (`IdeScriptEngine.eval`) lỗi
`IrScriptSymbolImpl is already bound`.

### 5. Dump gợi ý của tool

```bash
mvn -q test -Dtest=IntellijCompareDumpTest -DijCompare=true
```

Tạo `target/ij-compare/tool-out.tsv` (test này tự bỏ qua khi không có `-DijCompare=true`).

### 6. So sánh

```bash
python3 tools/intellij-compare/compare.py
```

Kết quả ở `target/ij-compare/compare.txt` (và `compare.json`).

## Đọc kết quả

- **Khác nhau không có nghĩa tool sai.** Lần so đầu tiên (Postgres 18, 198 câu): 125 câu khớp; phần
  khác phần lớn là IntelliJ gợi ý sai và đã được Postgres thật từ chối, ví dụ view/materialized view ở
  `TRUNCATE`/`COMMENT ON TABLE`/`PUBLICATION`/`INSERT INTO`, cột có tiền tố ở `MERGE ... UPDATE SET`,
  cột ở `VALUES (...)`, rò cột giữa các nhánh `UNION`.
- **Khác biệt thiết kế, không phải lỗi**: IntelliJ gợi ý tên bảng/alias làm tiền tố trong biểu thức
  (`select | from users` -> `users`), tool gợi ý thẳng `users.id`. Tool so khớp fuzzy (subsequence) nên
  gõ `us` còn ra `products`; IntelliJ chỉ so theo prefix.
- **Câu IntelliJ trả 0 gợi ý** được liệt kê riêng: phần lớn đúng là rỗng (`listen |`, `checkpoint |`),
  nhưng có thể là popup chưa kịp hiện - chạy lại riêng các câu đó (sửa `ij-cases.txt`) trước khi kết
  luận.
- Chỗ tool thiếu thật tìm được qua công cụ này (đã sửa): tên bảng/view làm **kiểu composite** ở vị
  trí khai báo kiểu (`RETURNS users`, `CAST(x AS users)`, `CREATE DOMAIN d AS users`...).

## Các file

| File | Vai trò |
|---|---|
| `fixture.sql` | Dựng DB `sqlctx_fixture` khớp `CompletionFixtures.installPostgres()` |
| `extract_cases.py` | Trích câu SQL từ test Postgres -> `target/ij-compare/ij-cases.txt` |
| `ij-completion-dump.groovy` | Chạy trong IntelliJ: gọi completion từng câu -> `ij-out.tsv` |
| `../../src/test/java/com/sqlctx/completion/IntellijCompareDumpTest.java` | Dump gợi ý của tool -> `tool-out.tsv` |
| `compare.py` | So 2 file -> `compare.txt` |
