package com.naviq.postgresql.semantic;

import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.tree.ErrorNode;

/**
 * Mirror STMParserOverrides của DBeaver. PostgreSQLParser (sinh ra từ .g4 đã patch
 * "options { superClass = PostgreSQLParserBase; }") sẽ extends class này thay vì
 * extends Parser trực tiếp.
 * <p>
 * Không đổi bất kỳ hành vi parse/recovery nào của ANTLR - chỉ đánh dấu RÕ node nào
 * là do recovery tự vá vào (không phải input thật của người dùng).
 */
public abstract class PostgreSQLParserBase extends Parser {

    public PostgreSQLParserBase(TokenStream input) {
        super(input);
    }

    @Override
    public ErrorNode createErrorNode(ParserRuleContext parent, Token t) {
        return new STMTreeTermErrorNode(t);
    }
}
