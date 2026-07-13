package com.naviq.completion.syntactic.engine.model;

/**
 * 1 token đã đọc, kèm offset ký tự thật trong văn bản gốc (startPosition,
 * stopPosition). Chỉ cần thêm 2 con số này (so với dùng thẳng org.antlr...Token)
 * để phục vụ đúng 1 feature: RuleTextRangeResolver (đổi vị trí token -> offset
 * ký tự). Nếu không cần feature đó, dùng thẳng Token gốc của ANTLR cũng được.
 */
public record InputToken(int type, int startPosition, int stopPosition) {
}
