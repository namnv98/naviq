package com.naviq.antlr4.postgresql;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.Parser;

/**
 * Base class required by PostgreSQLParser.g4's {@code options { superClass = PostgreSQLParserBase; }}.
 * <p>
 * Bytebase's original PostgreSQLParserBase (github.com/bytebase/postgresql-parser) is written in
 * GO, not Java. This is a NO-OP Java stand-in.
 * <p>
 * {@link #ParseRoutineBody(ParserRuleContext)} is called from {@code createfunc_opt_list} after a
 * {@code CREATE FUNCTION ... AS ...} function body has been parsed as opaque text - the real
 * implementation re-parses that body text against the appropriate inner grammar (SQL, or
 * PL/pgSQL) once the function's declared LANGUAGE is known, attaching the result into
 * {@code Definition}. No-op here means the function body is left as opaque, un-analyzed text:
 * completion INSIDE a function body (e.g. column names inside a PL/pgSQL block) won't resolve,
 * but the surrounding CREATE FUNCTION statement itself still parses and completes normally
 * (LANGUAGE, RETURNS, parameter list, etc. are unaffected since they're parsed by the regular
 * grammar, not by this hook).
 * <p>
 * If routine-body completion matters for your use case, port the real logic from bytebase's
 * {@code postgresql_parser_base.go}.
 */
public abstract class PostgreSQLParserBase extends Parser {

    /**
     * Self-alias used by the grammar's embedded action ("p.ParseRoutineBody(localctx)").
     */
    protected final PostgreSQLParserBase p = this;

    protected PostgreSQLParserBase(TokenStream input) {
        super(input);
    }

    public void ParseRoutineBody(ParserRuleContext ctx) {
        // no-op stand-in - see class javadoc
    }
}
