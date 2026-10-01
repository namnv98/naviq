# Thêm một DB mới

Mọi thứ phụ thuộc DB nằm sau `dialect/DialectAdapter`: kết nối, tải schema, SQL của `\l \du \d`, banner, sửa text câu lệnh trước khi gửi, lexer tô màu, gợi ý. Phần còn lại của app chỉ gọi `DialectAdapters.of(dialect)`.

1. Thêm giá trị vào enum `schema/Dialect`.
2. Viết `XxxDataSource` (giữ connection, `get`/`reconnect`/`close`) và phần nạp schema trong `SchemaLoader`.
3. Viết `XxxAdapter implements DialectAdapter`. Xem `PostgresAdapter` làm mẫu.
4. Thêm một `case` trong `DialectAdapters.of`.
5. Sửa chỗ đổi chuỗi tên dialect thành enum ở `SqlctxCli`, `ContextManager`, `ConnectionProfileStore` (3 chỗ, vì `Dialect` là enum đóng).
6. Muốn có gợi ý SQL: thêm grammar ANTLR trong `src/main/antlr4/com/sqlctx/antlr4/xxx/` (không cần `@header { package ... }`, plugin tự sinh theo thư mục; grammar chỉ để `import` thì đặt trong `src/main/antlr4/imports/`) và một `SuggestionService`, trả về từ `DialectAdapter.suggest`.

Không có gì trong `cli/` phải sửa.
