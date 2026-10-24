package com.sqlctx.cli.terminal;

import com.sqlctx.completion.model.SuggestionType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

class MenuCompleterIconTest {

    /** Thêm loại gợi ý mới mà quên icon thì menu hiện icon dấu hỏi mặc định - bug thật đã gặp với "join". */
    @ParameterizedTest
    @EnumSource(SuggestionType.class)
    void everySuggestionTypeHasOwnIcon(SuggestionType type) {
        assertNotEquals(MenuCompleter.typeIcon("<không có loại này>"), MenuCompleter.typeIcon(type.label()),
                "loại '" + type.label() + "' chưa có icon riêng trong MenuCompleter.typeIcon");
    }
}
