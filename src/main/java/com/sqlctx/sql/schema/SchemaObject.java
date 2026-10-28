package com.sqlctx.sql.schema;

/**
 * Đối tượng không phải bảng mà vẫn cần gợi ý tên: index, sequence, trigger.
 *
 * @param table bảng gắn với nó (index/trigger); null với sequence
 */
public record SchemaObject(String schema, String name, String table) {
}
