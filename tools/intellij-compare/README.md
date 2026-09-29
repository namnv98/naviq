# So sánh completion của sqlctx với IntelliJ

Chạy completion của IntelliJ (plugin Database Tools, bản Ultimate) và của sqlctx trên cùng danh sách
câu SQL, cùng một DB, rồi so: với từng câu, in gợi ý **chỉ tool có** và **chỉ IntelliJ có**.

Danh sách câu SQL nằm ở **`queries.txt`**: mỗi dòng 1 câu, `|` đánh dấu vị trí con trỏ; dòng trống và
dòng bắt đầu bằng `#` bị bỏ qua. Thêm/bớt câu cần so thì sửa file này.

Hai bước, cả hai cùng đọc `queries.txt`:

1. `ij-completion-dump.groovy` (chạy trong IntelliJ): gọi completion của IntelliJ tại vị trí `|` của từng
   câu, ghi `target/ij-compare/ij-out.tsv`.
2. `IntellijCompareTest` (Java): chạy tool trên từng câu (schema đọc từ cùng DB `sqlctx_fixture`), lấy
   kết quả IntelliJ của câu đó trong `ij-out.tsv`, ghi `target/ij-compare/compare.txt`. Câu có trong
   `queries.txt` mà chưa có kết quả IntelliJ (vừa thêm, chưa chạy lại bước 1) được liệt kê riêng.

So theo tên gợi ý (phần sau dấu chấm cuối, không phân biệt hoa/thường) - tool viết `public.users` /
`users.id`, IntelliJ viết `users` / `id`.

## Yêu cầu

- IntelliJ IDEA **Ultimate** (có Database Tools), đã mở project này.
- Docker (Postgres trong `docker-compose.yml`, cổng `54329`), Maven.

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
Đợi introspect xong. Chuột phải data source → *New* → *Query Console*, để tab console đó **mở**.

### 3. Chạy dump trong IntelliJ

*Tools* → *IDE Scripting Console* → chọn **Groovy**, dán dòng dưới (sửa đường dẫn repo), đặt con trỏ
trên dòng đó và bấm **Ctrl+Enter**:

```groovy
evaluate(new File("/đường/dẫn/tới/repo/tools/intellij-compare/ij-completion-dump.groovy"))
```

Console in `Đang chạy N câu - chờ thông báo Xong`. Trong lúc chạy (~5 phút cho ~200 câu) **không gõ
phím/click trong IntelliJ**. Xong sẽ có thông báo, kết quả ở `target/ij-compare/ij-out.tsv` (ghi dần ra
`ij-out.partial.tsv`; lỗi ghi ra `ij-error.txt`). Nội dung console và thiết lập auto-complete được trả
lại như cũ.

| Triệu chứng | Nguyên nhân / cách xử lý |
|---|---|
| `Argument for @NotNull parameter 'module' ... DefaultGroovyScriptRunner` | Đã chạy bằng nút Run ▶ / Shift+F10. Phải dùng **Ctrl+Enter** trong IDE Scripting Console. |
| Chỉ dòng con trỏ được chạy, báo `unresolved reference` | Đang dán cả script vào console. Chỉ dán dòng `evaluate(...)` ở trên. |
| `Không thấy tab Query Console nào đang mở ...` | Chưa mở Query Console, hoặc tên data source không chứa `sqlctx_fixture`. |
| `Không thấy project sqlctx đang mở ...` | Project đang mở trong IntelliJ không phải repo này (thiếu `tools/intellij-compare/queries.txt`). |
| `FileNotFoundException` | Đường dẫn trong `evaluate(...)` sai. |

Không dùng console **Kotlin**: gọi lồng script Kotlin lỗi `IrScriptSymbolImpl is already bound`.

### 4. So sánh

```bash
mvn -q test -Dtest=IntellijCompareTest -DijCompare=true
```

Kết quả ở `target/ij-compare/compare.txt`:

```
198 câu: giống nhau 38, khác 160

=== drop table |
chỉ tool (72): contracts, if exists, orders, pg_aggregate, ...
chỉ IntelliJ (1): active_users
```

`IntellijCompareTest` tự bỏ qua khi chạy `mvn test` thường (không có `-DijCompare=true`).

## Đọc kết quả

Khác nhau không có nghĩa tool sai - chạy câu SQL tương ứng trên Postgres thật để biết bên nào đúng.
Các khác biệt đã kiểm chứng trước đây:

- IntelliJ sai (Postgres thật từ chối): view/materialized view ở `DROP TABLE`/`TRUNCATE`/`COMMENT ON
  TABLE`/`PUBLICATION`/`INSERT INTO`...; `tenbang%rowtype` ở `RETURNS`/`CAST` (chỉ hợp lệ trong
  PL/pgSQL); cột ở `VALUES (...)`; cột có tiền tố ở `MERGE ... UPDATE SET`; rò cột giữa các nhánh
  `UNION`.
- Khác thiết kế: IntelliJ gợi ý tên bảng/alias làm tiền tố trong biểu thức (`select | from users` ->
  `users`), tool gợi ý thẳng `users.id`; tool so khớp fuzzy nên gõ `us` còn ra `products`.
- Câu IntelliJ trả 0 gợi ý: phần lớn đúng là rỗng (`listen |`), nhưng có thể do popup chưa kịp hiện.
- Tool thiếu thật, đã sửa: tên bảng/view làm kiểu composite (`RETURNS users`, `CAST(x AS users)`...).

## Các file

| File | Vai trò |
|---|---|
| `queries.txt` | Danh sách câu SQL cần so (cả 2 phía cùng đọc) |
| `fixture.sql` | Dựng DB `sqlctx_fixture` |
| `ij-completion-dump.groovy` | Chạy trong IntelliJ: completion từng câu -> `target/ij-compare/ij-out.tsv` |
| `../../src/test/java/com/sqlctx/completion/IntellijCompareTest.java` | Chạy tool trên từng câu, so -> `target/ij-compare/compare.txt` |
