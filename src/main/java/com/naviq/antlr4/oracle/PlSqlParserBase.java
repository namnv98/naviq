package com.naviq.antlr4.oracle;

import org.antlr.v4.runtime.Parser;

public abstract class PlSqlParserBase extends Parser {
    protected final PlSqlParserBase p = this;

    private boolean isVersion12;
    private boolean isVersion10;

    public PlSqlParserBase(org.antlr.v4.runtime.TokenStream input) {
        super(input);
    }

    public boolean isTableAlias() {
        return getCurrentToken().getType() != PlSqlLexer.JOIN;
    }

    public boolean isVersion12() {
        return isVersion12;
    }

    public void setVersion12(boolean value) {
        this.isVersion12 = value;
    }

    public boolean isVersion10() {
        return isVersion10;
    }

    public void setVersion10(boolean value) {
        this.isVersion10 = value;
    }
}