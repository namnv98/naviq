package com.sqlctx.completion.model;

/**
 * 1 token đã đọc: loại token và offset ký tự trong văn bản gốc.
 */
public record InputToken(int type, int startPosition, int stopPosition) {
}
