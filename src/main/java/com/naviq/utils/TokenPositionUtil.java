package com.naviq.utils;

import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;

/** Tìm token index tương ứng với 1 vị trí cursor (char offset) - dùng chung nhiều nơi. */
public class TokenPositionUtil {

    public static int findCaretTokenIndex(CommonTokenStream tokenStream, int cursorCharPos) {
        tokenStream.fill();
        var tokens = tokenStream.getTokens();
        for (int i = 0; i < tokens.size() - 1; i++) {
            Token t = tokens.get(i);
            if (t.getChannel() != Token.DEFAULT_CHANNEL) continue;
            if (t.getStartIndex() <= cursorCharPos && cursorCharPos <= t.getStopIndex()) return i;
            if (t.getStartIndex() > cursorCharPos) return i;
        }
        return tokens.size() - 1;
    }
}
