package com.naviq.oracle.semantic;

import java.util.List;
import java.util.Map;

/**
 * Hợp đồng CHUNG (không phụ thuộc dialect nào) cho "1 subquery/CTE trả ra cột gì" - tách ra từ
 * {@link SemanticScope.Scope} để {@code DerivedColumnExpander} (tầng suggests, dùng chung cho mọi
 * dialect) không phải phụ thuộc thẳng vào class {@code Scope} - vốn gắn chết với cách
 * {@link SemanticScope} (Postgres-specific) xây dựng scope qua các listener theo rule của
 * PostgreSQLParser.
 * <p>
 * Khi thêm dialect khác (vd Oracle PL/SQL), 1 class {@code PlSqlSemanticScope.Scope} tương ứng chỉ
 * cần implement interface này (thường đã sẵn có shape tương tự vì khái niệm "SELECT list trả ra
 * cột gì, có wildcard không, alias con nào nhìn thấy được" là phổ quát cho mọi SQL dialect) là
 * {@code DerivedColumnExpander} dùng được ngay, không cần sửa gì.
 */
public interface DerivedScope {

    /**
     * Tên cột mà scope này PROJECT ra (SELECT list của chính nó) - chỉ chứa cột suy ra được tên
     * (có alias, hoặc là 1 columnName đơn giản không alias); biểu thức phức tạp không alias bị bỏ
     * qua (không suy ra được tên cột thật).
     */
    List<String> projectedColumns();

    /**
     * true nếu SELECT list có ít nhất 1 "*" (bare "SELECT *" hoặc "alias.*") - nghĩa là
     * projectedColumns() KHÔNG đủ để biết hết cột output, còn phụ thuộc cột thật của (các) bảng
     * nguồn trong FROM - caller cần tự mở rộng qua schema (xem cách dùng ở DerivedColumnExpander).
     */
    boolean hasWildcard();

    /**
     * Alias -> tên bảng thật (hoặc dạng hiển thị "<cte#N>"/"<subquery#N>") thấy được TRONG scope
     * này - dùng khi hasWildcard() để biết mở rộng "*" ra những bảng nguồn nào.
     */
    Map<String, String> visibleAliases();

    /**
     * Như visibleAliases() nhưng CHỈ phần trỏ tới subquery/CTE khác (đệ quy 1 cấp khi wildcard lại
     * trỏ tới 1 subquery/CTE khác nữa).
     */
    Map<String, ? extends DerivedScope> visibleDerivedScopes();
}