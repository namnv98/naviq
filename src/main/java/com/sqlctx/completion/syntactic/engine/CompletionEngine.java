package com.sqlctx.completion.syntactic.engine;

import com.sqlctx.completion.model.CandidatesResult;
import org.antlr.v4.runtime.Parser;

import java.util.Map;

public class CompletionEngine {

    private final CompletionEngineBase engine;

    public CompletionEngine(Parser parser, Map<Integer, Boolean> ignoredTokens, Map<Integer, Boolean> preferredRules) {
        this.engine = new CompletionEngineDefault(parser, ignoredTokens, preferredRules);
    }

    public CandidatesResult collectCandidates(int caretTokenIndex) {
        return engine.collectCandidates(caretTokenIndex);
    }
}
