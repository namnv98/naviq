package com.sqlctx.completion.syntactic.engine;

import com.sqlctx.completion.model.CandidatesResult;
import com.sqlctx.completion.syntactic.engine.support.IncrementalExitCache;
import org.antlr.v4.runtime.Parser;

import java.util.Map;

public class CompletionEngine {

    private final CompletionEngineBase engine;

    public CompletionEngine(Parser parser, Map<Integer, Boolean> ignoredTokens, Map<Integer, Boolean> preferredRules) {
        this.engine = new CompletionEngineDefault(parser, ignoredTokens, preferredRules);
    }

    /** Như constructor kia, giữ kết quả rule con qua các lần gọi trong {@code cache} (1 cache/grammar). */
    public CompletionEngine(Parser parser, Map<Integer, Boolean> ignoredTokens, Map<Integer, Boolean> preferredRules,
                            IncrementalExitCache cache) {
        this(parser, ignoredTokens, preferredRules);
        engine.useIncrementalCache(cache);
        this.cache = cache;
    }

    private IncrementalExitCache cache;

    public CandidatesResult collectCandidates(int caretTokenIndex) {
        if (cache == null) {
            return engine.collectCandidates(caretTokenIndex);
        }
        synchronized (cache) { // REPL và Language Server có thể gọi cùng lúc
            return engine.collectCandidates(caretTokenIndex);
        }
    }
}
