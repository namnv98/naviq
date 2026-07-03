package com.naviq.datasource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Load + index schema từ DB thật. Tách riêng khỏi PostgresCompletionEngine vì đây
 * là 1 trách nhiệm hoàn toàn độc lập ("biết schema có gì") - không liên quan gì tới
 * việc parse/resolve alias của SemanticScope hay dự đoán token của
 * AntlrCompletionEngineFix.
 */
public class SchemaIndex {

    public static volatile List<SchemaLoader.SchemaInfo> DB_SCHEMA;
    public static volatile Map<String, SchemaLoader.TableInfo> TABLE_INDEX;
    public static volatile Map<String, SchemaLoader.TableInfo> SCHEMA_TABLE_INDEX;
    public static volatile List<String> FUNCTIONS;
    public static volatile List<String> DATA_TYPES;


    static {
        reload();
    }

    public static void reload() {
        try {
            DB_SCHEMA = SchemaLoader.loadSchema(PostgresDataSource.get());
            TABLE_INDEX = buildIndex(DB_SCHEMA);
            DATA_TYPES = SchemaLoader.loadDataTypes(PostgresDataSource.get());
            SCHEMA_TABLE_INDEX = buildSchemaTableIndex(DB_SCHEMA);
            FUNCTIONS = SchemaLoader.loadFunctions(PostgresDataSource.get());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<String, SchemaLoader.TableInfo> buildIndex(List<SchemaLoader.SchemaInfo> schemas) {
        Map<String, SchemaLoader.TableInfo> index = new HashMap<>();
        for (SchemaLoader.SchemaInfo s : schemas) {
            for (SchemaLoader.TableInfo t : s.tables()) {
                index.put(t.fullName(), t);
                index.put(t.name(), t);
            }
        }
        return index;
    }

    private static Map<String, SchemaLoader.TableInfo> buildSchemaTableIndex(List<SchemaLoader.SchemaInfo> schemas) {
        Map<String, SchemaLoader.TableInfo> index = new HashMap<>();
        for (SchemaLoader.SchemaInfo s : schemas) {
            for (SchemaLoader.TableInfo t : s.tables()) {
                index.put(t.fullName(), t);
            }
        }
        return index;
    }

    public static List<SchemaLoader.DBColumnInfo> getColumnsOfTable(String tableName) {
        SchemaLoader.TableInfo t = TABLE_INDEX.get(tableName);
        if (t == null) return List.of();
        return t.columns().stream().toList();
    }

    public static List<SchemaLoader.TableInfo> getTablesBySchema(String schemaName) {
        return DB_SCHEMA.stream()
            .filter(s -> s.name().equals(schemaName))
            .findFirst()
            .map(s -> s.tables().stream().toList())
            .orElse(List.of());
    }
}
