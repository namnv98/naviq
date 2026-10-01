package com.sqlctx.completion.model;

import java.util.Map;
import java.util.Set;

/** Những gì tầng ngữ nghĩa biết tại caret - dùng chung cho mọi dialect. */
public interface CaretScope {

    /** Phần trước dấu chấm cụt ngay trước caret ("u." -> "u"); null nếu caret không đứng sau dấu chấm cụt. */
    String qualifier();

    /** Bảng thật mà {@link #qualifier()} trỏ tới; null nếu là alias lạ hoặc trỏ tới CTE/subquery. */
    String qualifierResolvesTo();

    /** Scope của CTE/subquery mà {@link #qualifier()} trỏ tới; null nếu là bảng thật. */
    Scope qualifierDerivedScope();

    /** alias -> tên bảng thật hoặc "&lt;cte#N&gt;"/"&lt;subquery#N&gt;". */
    Map<String, String> visibleAliases();

    /** Chỉ alias trỏ tới CTE/subquery. */
    Map<String, Scope> visibleDerivedScopes();

    /** Alias bảng đích của DML/DDL đang gõ (UPDATE/MERGE/INSERT...); null nếu không có. */
    String ddlTargetAlias();

    Set<String> visibleCteNames();
}
