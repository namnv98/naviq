package com.naviq.completion.syntacticv1;

import org.antlr.v4.runtime.atn.ATNState;

/**
 * Một phần tử trong hàng đợi BFS khi duyệt ATN: đang ở state nào, tại token nào,
 * với call-stack (đường đi các rule đã gọi) nào.
 */
public record PipelineEntry(ATNState state, int tokenIndex, RuleCallStack stackSnapshot) {
}
