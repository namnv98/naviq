package com.naviq.completion.syntactic.antlr;

import com.naviq.completion.syntactic.antlr.model.CandidatesResult;
import org.antlr.v4.runtime.Parser;

import java.util.Map;

public class CompletionEngine {

    private final CompletionEngineBase antlrCompletionEngineBase;

    public CompletionEngine(Parser parser, Map<Integer, Boolean> ignoredTokens, Map<Integer, Boolean> preferredRules) {
        this.antlrCompletionEngineBase = new CompletionEngineWithFlowSet(parser, ignoredTokens, preferredRules);
    }

    public CandidatesResult collectCandidates(int caretTokenIndex) {
        return antlrCompletionEngineBase.collectCandidates(caretTokenIndex, null);
    }
}