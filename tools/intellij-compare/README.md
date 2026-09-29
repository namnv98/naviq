# So sánh completion của sqlctx với IntelliJ

Chạy completion của IntelliJ (plugin Database Tools, bản Ultimate) trên đúng các câu SQL trong test
Postgres, cùng một schema fixture, rồi so với gợi ý của sqlctx. Dùng để phát hiện chỗ tool thiếu/thừa
gợi ý so với một IDE chuẩn.

So relation (bảng/view/materialized view, gồm cả kiểu composite ở RETURNS/CAST/CREATE DOMAIN), cột
(kèm bảng/alias đứng trước) và role - KHÔNG lọc bớt theo whitelist/schema nào, IntelliJ là mẫu gốc: DB
thật có gì (kể cả bảng hệ thống `pg_catalog`/`information_schema`, role nội bộ `pg_*`...) so cái đó.
Hàm built-in và keyword vẫn không so (không có cách phân biệt tin cậy hàm "built-in" ở cả 2 phía) nhưng
vẫn đếm số lượng. Vai/database đều hiện dạng giống hệt nhau trong dump của IntelliJ (không phân biệt
được chỉ qua cấu trúc chuỗi) nên `compare.py` tự tra `pg_roles` trên DB thật (không hardcode) để tách
đúng loại. Khi hai bên khác nhau, chạy câu SQL tương ứng trên Postgres thật để biết bên nào đúng.

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

### 7. (tuỳ chọn) Cập nhật test chống hồi quy

Các câu mà bước 6 xác nhận khớp HOÀN TOÀN với IntelliJ có thể chốt lại thành test chạy trong
`mvn test` bình thường (không cần Docker/IntelliJ lúc chạy test), để phát hiện sau này tool trôi khỏi
tập đã xác nhận đúng:

```bash
python3 tools/intellij-compare/generate_golden.py
```

Ghi đè `src/test/resources/completion/intellij-golden.tsv` (commit file này vào git). Test tương ứng:
`src/test/java/com/sqlctx/completion/IntellijGoldenRegressionTest.java`. Chỉ chạy lại bước này khi đã
review `compare.txt` và xác nhận các câu mới khớp/không khớp là đúng (không tự động tin theo, vì
IntelliJ có thể sai - xem mục "Đọc kết quả" bên dưới).

## Đọc kết quả

- **Khác nhau không có nghĩa tool sai.** Lần so gần nhất (Postgres 18, không lọc gì, 174 câu): 89 câu
  khớp hoàn toàn; phần khác phần lớn là IntelliJ gợi ý sai và đã được Postgres thật từ chối:
  - Role/database: IntelliJ gợi role/tên database bất kể vị trí (kể cả không phải vị trí role, ví dụ
    `select | from users` vẫn có role `postgres` lẫn vào) - đúng vị trí CẦN role thật
    (`alter aggregate agg1(int4) owner to |`) thì IntelliJ lại trả 0 gợi ý (đã xác nhận lặp lại ở 2 lần
    capture độc lập, không phải lỗi timing) trong khi tool gợi đủ.
  - Relkind: view/materialized view (kể cả bảng hệ thống `pg_catalog`) ở `ANALYZE`/`VACUUM`/`TRUNCATE`/
    `REINDEX`/`LOCK TABLE`/`COMMENT ON TABLE`/`CREATE INDEX ON`/`ALTER EXTENSION...ADD TABLE`/
    `CREATE PUBLICATION...FOR TABLE`/`INSERT INTO` - verify trực tiếp bằng Postgres thật (vd
    `ANALYZE pg_locks` báo *"cannot analyze non-tables"* dù `pg_locks` là bảng hệ thống thật).
  - Kiểu composite ở `RETURNS`/`CAST`/`CREATE DOMAIN`/`ADD COLUMN`...: IntelliJ gợi dạng
    `tenbang%rowtype`, cú pháp đó CHỈ hợp lệ trong khai báo biến PL/pgSQL - dùng ở các vị trí này bị
    Postgres thật từ chối *"syntax error at or near %"*; tool gợi đúng dạng bare-name.
  - Cột: `VALUES (...)`, `MERGE ... UPDATE SET` (target column không được qualify) - Postgres thật từ
    chối cả 2.
- **Khác biệt thiết kế, không phải lỗi**: IntelliJ gợi ý tên bảng/alias làm tiền tố trong biểu thức
  (`select | from users` -> `users`), tool gợi ý thẳng `users.id`. Tool so khớp fuzzy (subsequence) nên
  gõ `us` còn ra `products`; IntelliJ chỉ so theo prefix.
- **Câu IntelliJ trả 0 gợi ý** được liệt kê riêng: phần lớn đúng là rỗng (`listen |`, `checkpoint |`),
  nhưng có thể là popup chưa kịp hiện - chạy lại riêng các câu đó (sửa `ij-cases.txt`) trước khi kết
  luận.
- Chỗ tool thiếu thật tìm được qua công cụ này (đã sửa): tên bảng/view làm **kiểu composite** ở vị
  trí khai báo kiểu (`RETURNS users`, `CAST(x AS users)`, `CREATE DOMAIN d AS users`...); role/database
  hiện dạng giống hệt nhau trong dump IntelliJ nên phải tra `pg_roles` trên DB thật mới tách đúng loại
  (xem `real_roles()` trong `compare.py`) - lúc đầu lỡ dùng whitelist tên cố định, làm ẩn mất role/
  database thật lẫn vào nhau.

## Các file

| File | Vai trò |
|---|---|
| `fixture.sql` | Dựng DB `sqlctx_fixture` khớp `CompletionFixtures.installPostgres()` |
| `extract_cases.py` | Trích câu SQL từ test Postgres -> `target/ij-compare/ij-cases.txt` |
| `ij-completion-dump.groovy` | Chạy trong IntelliJ: gọi completion từng câu -> `ij-out.tsv` |
| `../../src/test/java/com/sqlctx/completion/IntellijCompareDumpTest.java` | Dump gợi ý của tool -> `tool-out.tsv` |
| `compare.py` | So 2 file -> `compare.txt` |
| `generate_golden.py` | Chốt các câu đã khớp thành `../../src/test/resources/completion/intellij-golden.tsv` |
| `../../src/test/java/com/sqlctx/completion/IntellijGoldenRegressionTest.java` | Test chống hồi quy, chạy trong `mvn test` bình thường (đọc golden file, không cần Docker/IntelliJ) |
