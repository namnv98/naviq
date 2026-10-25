# sqlctx

Một REPL SQL cho PostgreSQL và Oracle (kèm chế độ Redis và Kafka), gợi ý dựa trên grammar thật.

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
- **Redis** // REPL riêng, gợi ý theo `COMMAND DOCS` của chính server đang nối: lệnh và tuỳ chọn đúng phiên bản, key đúng kiểu, field thật
- **Kafka** // gõ lệnh `kafka-*.sh` như tài liệu (bỏ `--bootstrap-server`), gợi ý topic/group/config thật, Avro/JSON Schema/Protobuf qua Schema Registry

## Cài

```sh
git clone <repo> && cd sqlctx
./install.sh
```

Cần JDK 21 và Maven. Cài `sqlctx` vào `~/.local/bin`. `docker compose up -d` dựng sẵn Postgres (54329), Oracle (1521), Redis (63790), Kafka (19092) và Schema Registry (18081) để thử; dữ liệu mẫu trong `seed/`.

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
\lang [vi|en]        ngôn ngữ hiển thị (cả chế độ Redis, Kafka)
\refresh             nạp lại schema cho gợi ý (khi schema đổi ngoài sqlctx)
\q                   thoát
```

`Tab` mở menu gợi ý, `Ctrl+T` bật/tắt multi-line (chạy khi gặp `;`), `Ctrl+F` (hoặc `Ctrl+Alt+L`) định dạng câu đang gõ, `Esc` đóng popup gợi ý. `UPDATE`, `DELETE`, `DROP`, `TRUNCATE`, `ALTER` hỏi `[y/N]` trước khi chạy. Kết quả dài mở pager. `EXPLAIN` được tô màu.

### File dữ liệu

`~/.sqlctx/` chứa `connections.conf` (quyền 600), `history.txt`, `current_context` và lịch sử gợi ý. Log debug: `~/sqlctx-debug.log`.

Không muốn lưu mật khẩu plaintext: bỏ `--password`, sqlctx tìm lần lượt ở biến môi trường `SQLCTX_PASSWORD`, rồi (Postgres) `PGPASSWORD` và `~/.pgpass` (hoặc `PGPASSFILE`) giống psql. Context lưu khi đó không kèm mật khẩu.

### Ngôn ngữ

Tiếng Việt hoặc tiếng Anh. Thứ tự chọn: `--lang=vi|en`, biến môi trường `SQLCTX_LANG`, `lang=` trong `~/.sqlctx/settings.conf`, rồi ngôn ngữ hệ thống (`LANG=vi_*` thì tiếng Việt, còn lại tiếng Anh). `\lang en` / `\lang vi` trong REPL đổi ngay và lưu vào `settings.conf`. Chữ nằm ở `src/main/resources/i18n/` (`messages_vi.properties`, `messages_en.properties`, và `texts_en.tsv` cho mô tả tuỳ chọn của các tool Kafka); tên cột theo tool gốc (`GROUP`, `TOPIC`, `LAG`...) và output kiểu tool gốc (`Created topic x.`) giữ nguyên tiếng Anh.

### SSL

Postgres: `--sslmode=require` (kèm `--sslrootcert`, `--sslcert`, `--sslkey`). Oracle: `--ssl=true --sslwallet=DIR`. Chưa thử với server TLS thật.

## Redis

```sh
sqlctx --context=redis-dev --dialect=redis --host=127.0.0.1 --port=63790 --password=x
sqlctx --context=redis-dev                    # lần sau
sqlctx --context=redis-dev --command="GET a; HGETALL user:1"
```

Thêm `--dbname=N` (số DB), `--user=` (ACL), `--tls=true`, `--readonly=true`. Context Redis lưu ở `~/.sqlctx/redis.conf`, tách khỏi `connections.conf`; mật khẩu không lưu thì lấy từ `SQLCTX_PASSWORD` hoặc `REDISCLI_AUTH`.

Gõ lệnh như redis-cli. Popup gợi ý tự hiện khi gõ, giống phía SQL (↓/↑/Tab chọn, Enter/Space điền, Esc đóng), theo cây tham số `COMMAND DOCS` của server, lấy lúc kết nối và cache ở `~/.sqlctx/redis-docs/` theo phiên bản (lên Redis mới không cần cập nhật sqlctx): lệnh và lệnh con, tuỳ chọn đúng chỗ (`SET k v |` ra `NX XX EX...`), key đúng kiểu với lệnh (`HGET |` chỉ ra key hash, qua `SCAN ... TYPE`), field/member thật (`HGET user:1 |`), tham số `CONFIG GET`, section `INFO`. Dòng dưới ô nhập hiện cú pháp lệnh đang gõ; thừa tham số thì gạch đỏ.

```
\ctx [NAME|-|save NAME]   context Redis       \dt [PATTERN]   key, kiểu, TTL (SCAN)
\c N                      đổi DB              \d KEY          chi tiết + xem trước
\l                        DB đang có key      \h [LỆNH]       cú pháp lệnh
\i FILE                   chạy file lệnh      \q              thoát
```

`HGETALL`, `CONFIG GET`, `ZRANGE ... WITHSCORES`, `XRANGE` hiện thành bảng. `FLUSHALL`, `DEL`, `KEYS`... hỏi `[y/N]` (batch cần `--yes`); context `readonly` chặn mọi lệnh ghi và lệnh lạ. Ctrl+C dừng `BLPOP`, `SUBSCRIBE`, `MONITOR` rồi tự nối lại.

## Kafka

```sh
sqlctx --context=kafka-dev --dialect=kafka --bootstrap-server=localhost:19092 --schema-registry=http://localhost:18081
sqlctx --context=kafka-dev                    # lần sau
sqlctx --context=kafka-dev --command='kafka-topics --list'
printf 'k1\thello\n' | sqlctx --context=kafka-dev --command='kafka-console-producer --topic t --property parse.key=true'
```

Context lưu ở `~/.sqlctx/kafka.conf`; mọi khoá không phải của sqlctx là config của client Kafka, nên SASL/SSL đặt thẳng vào đó (`security.protocol`, `sasl.mechanism`, `sasl.jaas.config`, `ssl.truststore.location`...). `--readonly=true` chặn mọi lệnh ghi.

Gõ lệnh như các tool `kafka-*.sh` của Kafka 3.9; `--bootstrap-server`/`--command-config` được nhận nhưng bỏ qua (dán lệnh từ tài liệu là chạy), `\` cuối dòng để nối dòng:

| Tool | Làm gì |
|---|---|
| `kafka-topics` | `--list --describe --create --delete --alter`, lọc `--under-replicated-partitions`..., thêm cột offset đầu/cuối |
| `kafka-console-consumer` | `--from-beginning --partition --offset --max-messages --timeout-ms --group --property print.key=true...`; Ctrl+C để dừng |
| `kafka-console-producer` | mỗi dòng 1 message (Ctrl+D để dừng; batch đọc stdin), `parse.key`, `parse.headers`, `--sync` |
| `kafka-consumer-groups` | `--list --describe (--members --state --verbose) --reset-offsets --delete --delete-offsets` |
| `kafka-configs` | topic, broker, broker-logger, quota user/client, SCRAM credential |
| `kafka-acls` | `--list --add --remove`, `--producer`/`--consumer` |
| `kafka-get-offsets`, `kafka-delete-records`, `kafka-log-dirs`, `kafka-cluster`, `kafka-metadata-quorum` | như tool gốc |
| `kafka-avro-*`, `kafka-json-schema-*`, `kafka-protobuf-console-consumer` | message qua Schema Registry (protobuf chỉ đọc, dạng thô `1: 7 2: "click"`) |
| `schema-registry` | `--list --versions --describe --register --delete --compatibility --id` (không phải tool có thật: Schema Registry chỉ có REST) |

Khác tool gốc có chủ đích: consumer không `--group` đọc bằng `assign()` (không tạo group rác); consumer có `--group` chỉ commit tới message đã in (`--max-messages` không làm mất message đã lấy về mà chưa in); byte không phải UTF-8 in dạng `\xNN`; `kafka-consumer-groups --describe` hiện cả partition group chưa từng commit (CURRENT-OFFSET `-`, LAG tính từ offset đầu) - tool gốc bỏ qua chúng nên tổng lag trông nhỏ hơn thật. `--delete`, `--reset-offsets --execute`, `kafka-acls --remove`, `kafka-delete-records`... hỏi `[y/N]` (batch cần `--yes`). `\h TOOL` xem tuỳ chọn, `\brokers` xem broker. Dữ liệu mẫu: `seed/kafka.sh`.

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

- SQL chỉ có PostgreSQL và Oracle. Kafka: chưa có `kafka-reassign-partitions`, `kafka-leader-election`, `kafka-transactions`, `kafka-streams-application-reset`; ghi Protobuf chưa hỗ trợ. Redis: chỉ server đơn (chưa có Cluster/Sentinel). Server không có `COMMAND DOCS` (Redis < 7.0, hoặc ACL chặn lệnh `COMMAND`) dùng bản mô tả đóng gói sẵn từ Valkey (`tools/redis-fallback/update.sh`), lọc theo lệnh và phiên bản của server. Lệnh module không khai báo docs vẫn chạy được nhưng không có gợi ý tham số.
- Đi trên grammar không có error recovery: token trước con trỏ không parse được thì không có gợi ý keyword.
- Báo lỗi cú pháp dựa trên grammar ANTLR: cú pháp mới mà grammar chưa có sẽ bị báo nhầm là lỗi.
- Mỗi tầng phân tích có giới hạn 300ms (`-Dsqlctx.completion.budgetMs=`); câu cực dài có thể không có gợi ý keyword.
