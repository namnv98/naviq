package com.naviq.completion.syntactic.engine.support;

import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.atn.PredicateTransition;

/**
 * Đánh giá predicate ngữ nghĩa {@code {...}?} của grammar khi đi trên ATN.
 * <p>
 * Không có ngữ cảnh parse thật nên dùng {@link ParserRuleContext#EMPTY}. Chấp nhận được vì các predicate
 * còn hiệu lực trong grammar chỉ đọc cờ của parser ({@code p.isVersion12()}, {@code p.isTableAlias()}...),
 * không đọc ngữ cảnh rule.
 */
public final class AtnPredicates {

    private AtnPredicates() {
    }

    public static boolean holds(Parser parser, PredicateTransition transition) {
        return transition.getPredicate().eval(parser, ParserRuleContext.EMPTY);
    }
}
