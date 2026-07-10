package com.naviq.completion.syntacticv1;

/**
 * Một token trong luồng input, được rút gọn chỉ còn những trường cần cho việc
 * duyệt ATN: loại token và vị trí ký tự bắt đầu/kết thúc trong văn bản gốc.
 */
public record InputToken(int type, int startPosition, int stopPosition) {
}
