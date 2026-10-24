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
- **JOIN thông minh** // `JOIN |` gợi ý cả cụm `orders o on o.user_id = u.id`, theo khoá ngoại hoặc đoán theo tên cột
- **Dòng gợi ý** // chữ ký hàm khi gõ trong ngoặc, lỗi cú pháp giữa câu được gạch chân ngay khi gõ
- **Language Server** // `sqlctx --lsp` dùng cùng engine trong Neovim, Helix, VS Code...
- **Format** // `Ctrl+F` trong REPL, *Format Document* qua LSP, `sqlctx --format` ở dòng lệnh
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
sqlctx --context=dev --file=cleanup.sql --yes   # có DELETE/UPDATE/DROP/ALTER/TRUNCATE/MERGE
```

Chế độ này không có ai trả lời `[y/N]`: lô có câu xoá/sửa dữ liệu mà thiếu `--yes` thì bị từ chối cả lô, không câu nào chạy.

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
\refresh             nạp lại schema cho gợi ý (khi schema đổi ngoài sqlctx)
\q                   thoát
```

`Tab` mở menu gợi ý, `Ctrl+T` bật/tắt multi-line (chạy khi gặp `;`), `Ctrl+F` (hoặc `Ctrl+Alt+L`) định dạng câu đang gõ, `Esc` đóng popup gợi ý. `UPDATE`, `DELETE`, `DROP`, `TRUNCATE`, `ALTER` hỏi `[y/N]` trước khi chạy. Kết quả dài mở pager. `EXPLAIN` được tô màu.

### File dữ liệu

`~/.sqlctx/` chứa `connections.conf` (quyền 600), `history.txt`, `current_context` và lịch sử gợi ý. Log debug: `~/sqlctx-debug.log`.

Không muốn lưu mật khẩu plaintext: bỏ `--password`, sqlctx tìm lần lượt ở biến môi trường `SQLCTX_PASSWORD`, rồi (Postgres) `PGPASSWORD` và `~/.pgpass` (hoặc `PGPASSFILE`) giống psql. Context lưu khi đó không kèm mật khẩu.

### SSL

Postgres: `--sslmode=require` (kèm `--sslrootcert`, `--sslcert`, `--sslkey`). Oracle: `--ssl=true --sslwallet=DIR`. Chưa thử với server TLS thật.

## Format

```sh
sqlctx --format --file=query.sql                    # in ra stdout, không kết nối DB
cat query.sql | sqlctx --format --dialect=oracle
sqlctx --format --file=q.sql --keyword-case=lower --indent=2
```

Mệnh đề (SELECT, FROM, WHERE...) đứng riêng 1 dòng, nội dung thụt 1 cấp, mỗi cột 1 dòng, AND/OR xuống dòng, subquery thụt theo độ sâu; lời gọi hàm, `IN (...)`, `OVER (...)` giữ 1 dòng. Chỉ đổi khoảng trắng và hoa/thường của từ khoá - chuỗi, comment, `$$...$$` giữ nguyên. Khối PL/SQL Oracle giữ nguyên.

## Language Server

```sh
sqlctx --lsp --context=dev     # JSON-RPC qua stdin/stdout
```

Gợi ý (cùng engine với REPL), chữ ký hàm, gạch lỗi cú pháp, Format Document; lệnh `sqlctx.refreshSchema` nạp lại schema. Mỗi lần chỉ phân tích câu chứa con trỏ (tách theo `;`, Oracle: khối PL/SQL kết thúc bằng dòng `/`).

Neovim (0.11+):

```lua
vim.lsp.config('sqlctx', { cmd = { 'sqlctx', '--lsp', '--context=dev' }, filetypes = { 'sql' } })
vim.lsp.enable('sqlctx')
```

Helix (`~/.config/helix/languages.toml`):

```toml
[language-server.sqlctx]
command = "sqlctx"
args = ["--lsp", "--context=dev"]

[[language]]
name = "sql"
language-servers = ["sqlctx"]
```

VS Code không có client LSP chung sẵn - cần một extension client LSP tổng quát, trỏ lệnh tới `sqlctx --lsp --context=dev`.

## Build

```sh
mvn package      # target/sqlctx-1.0.0.jar
mvn test         # test completion engine, LSP, schema helpers (không cần DB)
```

Tầng REPL chưa có test tự động.

## Thiết kế

- [Cách gợi ý hoạt động](docs/how-it-works.md)
- [Tầng cú pháp hoạt động thế nào](docs/syntactic.md)
- [Thêm một DB mới](docs/adding-a-dialect.md)

## Giới hạn

- Chỉ có PostgreSQL và Oracle.
- Đi trên grammar không có error recovery: token trước con trỏ không parse được thì không có gợi ý keyword.
- Báo lỗi cú pháp dựa trên grammar ANTLR: cú pháp mới mà grammar chưa có sẽ bị báo nhầm là lỗi.
- Mỗi tầng phân tích có giới hạn 300ms (`-Dsqlctx.completion.budgetMs=`); câu cực dài có thể không có gợi ý keyword.
