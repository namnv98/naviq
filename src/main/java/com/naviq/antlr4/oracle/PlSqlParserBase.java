package com.naviq.antlr4.oracle;

import org.antlr.v4.runtime.Parser;

public abstract class PlSqlParserBase extends Parser {
    protected final PlSqlParserBase p = this;

    private boolean isVersion12;
    private boolean isVersion10;

    public PlSqlParserBase(org.antlr.v4.runtime.TokenStream input) {
        super(input);
    }

//    public boolean isTableAlias() {
//        return getCurrentToken().getTokenIndex() != PlSqlLexer.JOIN;
//    }

    protected boolean isTableAlias() {
        String text = getCurrentToken().getText().toUpperCase();
        if (text.equals("NATURAL") || text.equals("CROSS") || text.equals("JOIN") || text.equals("INNER")
                || text.equals("OUTER") || text.equals("LEFT") || text.equals("RIGHT") || text.equals("FULL")) {
            return false;
        }

        return true;
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