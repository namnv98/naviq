package com.naviq.antlr4.postgresql;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.Lexer;

/**
 * Base class required by PostgreSQLLexer.g4's {@code options { superClass = PostgreSQLLexerBase; }}.
 * <p>
 * Bytebase's original PostgreSQLLexerBase (github.com/bytebase/postgresql-parser) is written in
 * GO, not Java - there is no official Java version to copy. This is a NO-OP Java stand-in: it
 * makes the grammar compile and lets ordinary SQL (SELECT/INSERT/UPDATE/DELETE/DDL/expressions)
 * tokenize correctly, since none of the methods below gate plain ASCII identifiers or keywords.
 * <p>
 * What each stub actually guards, and what "no-op" gives up:
 * <ul>
 *   <li>{@link #pushTag()}/{@link #popTag()}/{@link #isTag()} - track the tag between the two
 *       {@code $} in a dollar-quoted string, e.g. {@code $tag$ ... $tag$}. No-op means the lexer
 *       cannot correctly match a NAMED dollar-quote tag (only the bare {@code $$ ... $$} form,
 *       which doesn't need a tag). If your completion input can contain named dollar-quoted
 *       bodies (common inside {@code CREATE FUNCTION ... AS $body$ ... $body$}), this needs a
 *       real implementation (a stack of tag strings pushed/popped/compared against the current
 *       one - see {@code string_stack.go} in the bytebase repo for the real logic to port).</li>
 *   <li>{@link #checkLA(char)} - lookahead used to decide where a run of operator characters
 *       ends when it's adjacent to {@code +}/{@code -} (Postgres has special rules so trailing
 *       {@code +}/{@code -} don't get swallowed into a custom operator token). Returning
 *       {@code false} always means this edge case in operator tokenizing is not specially
 *       handled - most SQL won't hit it, but exotic custom-operator text might tokenize
 *       differently than real Postgres.</li>
 *   <li>{@link #charIsLetter()}/{@link #CheckIfUtf32Letter()} - Unicode-letter classification for
 *       identifier start characters in the 0x100-0xFFFF and surrogate-pair ranges. Returning
 *       {@code false} means non-ASCII-letter Unicode identifiers (e.g. Vietnamese, CJK column/
 *       table names) won't be recognized as valid identifier starts. Plain ASCII identifiers
 *       ([a-zA-Z_]) are unaffected - that path has no predicate at all.</li>
 *   <li>{@link #HandleLessLessGreaterGreater()} - disambiguates {@code <<}/{@code >>} from
 *       custom operator sequences. No-op leaves default token matching in place.</li>
 *   <li>{@link #HandleNumericFail()} - recovery hook for a malformed numeric literal. No-op means
 *       no special recovery; ANTLR's normal error handling applies instead.</li>
 *   <li>{@link #UnterminatedBlockCommentDebugAssert()} - a debug-only assertion for block-comment
 *       state; safe to leave empty in production.</li>
 * </ul>
 * If any of these edge cases matter for your use case, port the corresponding logic from
 * bytebase's {@code postgresql_lexer_base.go} (and {@code string_stack.go} for the tag stack).
 */
public abstract class PostgreSQLLexerBase extends Lexer {

    /**
     * Self-aliases used by the grammar's embedded actions ("l.pushTag()", "p.checkLA(...)").
     * Both names refer to this same lexer instance - the grammar just uses two different short
     * names for readability in different rules.
     */
    protected final PostgreSQLLexerBase l = this;
    protected final PostgreSQLLexerBase p = this;

    protected PostgreSQLLexerBase(CharStream input) {
        super(input);
    }

    // ---- dollar-quoted string tag tracking ($tag$ ... $tag$) ----

    public void pushTag() {
        // no-op stand-in - see class javadoc
    }

    public void popTag() {
        // no-op stand-in - see class javadoc
    }

    public boolean isTag() {
        return false;
    }

    // ---- operator / identifier disambiguation predicates ----

    public boolean checkLA(char c) {
        return false;
    }

    public boolean charIsLetter() {
        return false;
    }

    public boolean CheckIfUtf32Letter() {
        return false;
    }

    // ---- misc lexing edge cases ----

    public void HandleLessLessGreaterGreater() {
        // no-op stand-in - see class javadoc
    }

    public void HandleNumericFail() {
        // no-op stand-in - see class javadoc
    }

    public void UnterminatedBlockCommentDebugAssert() {
        // no-op stand-in - see class javadoc
    }
}
