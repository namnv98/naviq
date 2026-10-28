package com.sqlctx.ui;

/**
 * 1 dòng của popup gợi ý: chữ được điền, mô tả (cột giữa, có thể null) và loại (cột phải, quyết định icon/màu -
 * xem {@link CompletionMenu}): command, keyword, option, key, topic, group, field, value, sqlctx...
 */
public record MenuItem(String value, String description, String type) {
}
