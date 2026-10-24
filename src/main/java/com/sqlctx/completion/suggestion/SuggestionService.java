package com.sqlctx.completion.suggestion;

import com.sqlctx.completion.model.Suggestion;

import java.util.List;

/**
 * Điểm vào chung của tầng gợi ý cho 1 dialect SQL. Mỗi dialect (Oracle, Postgres) có 1
 * implementation riêng vì ngữ pháp khác nhau, nhưng chia sẻ cùng hợp đồng này.
 */
public interface SuggestionService {

    List<Suggestion> suggest(String sql, Integer cursorCharPos);
}
