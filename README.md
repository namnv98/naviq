# sqlctx

Một REPL SQL cho PostgreSQL và Oracle, gợi ý dựa trên grammar thật.

```
app> select id, name from users
╭────┬──────╮
│ id │ name │
├────┼──────┤
│  1 │ a    │
│  2 │ b    │
╰────┴──────╯
2 row(s), 2 col(s)
Query: 0.003s  ·  Fetch: 0.003s  ·  Total: 0.006s
```

## Điểm nổi bật

- **Dựa trên grammar** // gợi ý bằng cách đi trên ATN của grammar ANTLR thật tới đúng vị trí con trỏ, không phải khớp mẫu từ khoá
- **Hiểu scope** // biết alias, CTE, subquery: `sub.` gợi ý đúng cột của subquery
- **Chịu được gõ dở** // câu chưa hoàn chỉnh vẫn có gợi ý
- **Context** // lưu kết nối theo tên và chuyển qua lại bằng `\ctx`, kiểu kubectx
- **Hai dialect** // PostgreSQL và Oracle, mỗi bên grammar riêng, đứng sau chung 1 interface `DialectAdapter`

## Cài

```sh
git clone <repo> && cd sqlctx
./install.sh
```

Cần JDK 21 và Maven. Cài `sqlctx` vào `~/.local/bin`. `docker compose up -d` dựng sẵn Postgres (54329) và Oracle (1521) để thử.

## Dùng

```sh
sqlctx --context=dev --host=127.0.0.1 --port=5432 --dbname=app --user=me --password=x
sqlctx --context=dev        # lần sau
sqlctx                      # mở lại context lần trước
```

Oracle: thêm `--dialect=oracle`; `--dbname` là service name.

Chạy không cần vào REPL:

```sh
sqlctx --context=dev --command="select 1; select 2"
sqlctx --context=dev --file=migrate.sql
```

### Lệnh

```
\ctx                 danh sách context
\ctx NAME            chuyển context
\ctx -               quay về context trước
\ctx save NAME       lưu kết nối hiện tại
\c DB                đổi database cùng server
\l                   danh sách database
\dt \dn \df \du      bảng, schema, hàm, user/role
\d TABLE             cột, kiểu, khoá chính
\o FILE              xuất kết quả (.csv, .json cho câu kế tiếp; đuôi khác ghi nối tiếp như log)
\o                   tắt xuất file
\i FILE              chạy 1 file SQL
\e                   sửa câu gần nhất bằng $EDITOR
\q                   thoát
```

`Tab` mở menu gợi ý, `Ctrl+T` bật/tắt multi-line (chạy khi gặp `;`). `UPDATE`, `DELETE`, `DROP`, `TRUNCATE`, `ALTER` hỏi `[y/N]` trước khi chạy. Kết quả dài mở pager. `EXPLAIN` được tô màu.

### File dữ liệu

`~/.sqlctx/` chứa `connections.conf` (mật khẩu dạng plaintext, quyền 600), `history.txt`, `current_context` và lịch sử gợi ý. Log debug: `~/sqlctx-debug.log`.

### SSL

Postgres: `--sslmode=require` (kèm `--sslrootcert`, `--sslcert`, `--sslkey`). Oracle: `--ssl=true --sslwallet=DIR`. Chưa thử với server TLS thật.

## Build

```sh
mvn package      # target/sqlctx-1.0.0.jar
mvn test         # 574 test cho completion engine
```

Tầng REPL chưa có test tự động.

## Thiết kế

- [Cách gợi ý hoạt động](docs/how-it-works.md)
- [Thêm một DB mới](docs/adding-a-dialect.md)

## Giới hạn

- Chỉ có PostgreSQL và Oracle.
- Đi trên grammar không có error recovery: token trước con trỏ không parse được thì không có gợi ý keyword.
