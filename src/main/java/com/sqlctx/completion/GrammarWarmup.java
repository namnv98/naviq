package com.sqlctx.completion;

import com.sqlctx.completion.hint.InputHints;
import com.sqlctx.schema.Dialect;

import java.util.List;

/**
 * Lần phân tích đầu tiên của mỗi grammar trong 1 JVM chậm hơn hẳn (Oracle ~0.6-2s, sau đó vài ms): ANTLR dựng dần DFA
 * cache khi chạy. Lần đó lại vượt budget nên phím Tab đầu tiên không có gợi ý, dòng báo lỗi cú pháp không hiện. Chạy
 * trước vài câu tiêu biểu (engine gợi ý + parser 2 tầng, qua {@link InputHints#syntaxProblemIn}) để làm nóng các cache
 * dùng chung đó. Các cache đều an toàn khi chạy song song với lúc người dùng gõ.
 */
public final class GrammarWarmup {

    private GrammarWarmup() {
    }

    private static final List<String> STATEMENTS = List.of(
            "select a.id, b.name from t1 a join t2 b on a.id = b.id left join t3 c on c.x = b.x where a.id = 1 order by ",
            "select count(*) from t group by x having count(*) > 1 order by 1",
            "select * from dual wher x = 1 order by ",
            "insert into t (a, b) values (1, 'x')",
            "update t set a = 1 where b in (select b from u where ",
            "delete from t where a = 1 and b like 'x%'",
            "with q as (select 1 as a from t) select a from q where ");

    private static final int MAX_ROUNDS = 6;
    private static final long WARM_MILLIS = 50;

    /** Chạy cho tới khi 1 vòng đủ nhanh (hoặc hết số vòng). Không ném lỗi. */
    public static void warm(Dialect dialect) {
        for (int round = 0; round < MAX_ROUNDS; round++) {
            long start = System.nanoTime();
            for (String sql : STATEMENTS) {
                InputHints.syntaxProblemIn(sql, dialect);
            }
            if ((System.nanoTime() - start) / 1_000_000 < WARM_MILLIS) {
                return;
            }
        }
    }

    /** Làm nóng {@code first} trước rồi tới dialect còn lại (\c đổi được dialect giữa chừng), ở thread nền. */
    public static void warmInBackground(Dialect first) {
        Thread t = new Thread(() -> {
            warm(first);
            warm(first == Dialect.ORACLE ? Dialect.POSTGRES : Dialect.ORACLE);
        }, "grammar-warmup");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }
}
