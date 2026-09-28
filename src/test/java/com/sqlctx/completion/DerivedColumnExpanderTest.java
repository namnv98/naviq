package com.sqlctx.completion;

import com.sqlctx.completion.model.Scope;
import com.sqlctx.completion.model.Suggestion;
import com.sqlctx.completion.suggestion.DerivedColumnExpander;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class DerivedColumnExpanderTest {

    @Test
    @DisplayName("2 scope wildcard tham chiếu vòng lẫn nhau - phải dừng (chống lặp vô hạn), không treo/StackOverflow")
    void cyclicWildcardScopesTerminate() {
        var a = new Scope(1, null);
        var b = new Scope(2, null);
        a.hasWildcard = true;
        b.hasWildcard = true;
        a.projectedColumns.add("x");
        a.aliases.put("b", "<cte#2>");
        a.derivedScopeAliases.put("b", b);
        b.aliases.put("a", "<cte#1>");
        b.derivedScopeAliases.put("a", a);

        List<Suggestion> suggests = new ArrayList<>();
        assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> DerivedColumnExpander.addDerivedColumns(suggests, "t", a));
        assertEquals(List.of("t.x"), suggests.stream().map(Suggestion::getKey).toList());
    }
}
