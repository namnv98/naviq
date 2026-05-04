package com.naviq.completion.model;

import org.antlr.v4.runtime.Token;
import com.example.PostgreSQLParser;

import java.util.List;

public final class SqlTokenUtil {
    private SqlTokenUtil() {
    }

    public static int nextVisible(List<Token> tokens, int from, int limit) {
        while (from < limit && tokens.get(from).getChannel() != Token.DEFAULT_CHANNEL) from++;
        return from;
    }

    public static boolean isHidden(Token t) {
        return t.getChannel() != Token.DEFAULT_CHANNEL;
    }

    public static int matchingParen(List<Token> tokens, int openIdx, int limit) {
        int depth = 1, i = openIdx + 1;
        while (i < limit && depth > 0) {
            int type = tokens.get(i++).getType();
            if (type == PostgreSQLParser.LPAREN) depth++;
            else if (type == PostgreSQLParser.RPAREN) depth--;
        }
        return i - 1;
    }

    public static boolean isInsideFromList(List<Token> tokens, int commaIdx) {
        for (int i = commaIdx - 1; i >= Math.max(0, commaIdx - 50); i--) {
            if (isHidden(tokens.get(i))) continue;
            int type = tokens.get(i).getType();
            if (type == PostgreSQLParser.FROM) return true;
            if (type == PostgreSQLParser.SELECT || type == PostgreSQLParser.JOIN) return false;
        }
        return false;
    }
}