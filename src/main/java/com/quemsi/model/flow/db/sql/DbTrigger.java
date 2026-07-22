package com.quemsi.model.flow.db.sql;

import com.quemsi.model.util.CommonHelpers;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DbTrigger {
    public static final String SCOPE_TABLE = "TABLE";
    public static final String SCOPE_DATABASE = "DATABASE";

    private String schema;
    private String tableName;
    private String name;
    /** Full CREATE TRIGGER statement from catalog / pg_get_triggerdef. */
    private String definition;
    private String functionSchema;
    private String functionName;
    /**
     * TABLE (default) for DML triggers on a table; DATABASE for SQL Server DDL triggers
     * under Programmability → Database Triggers.
     */
    @Builder.Default
    private String scope = SCOPE_TABLE;

    public boolean isDatabaseLevel() {
        return SCOPE_DATABASE.equalsIgnoreCase(scope);
    }

    public String qualifiedTableName() {
        return CommonHelpers.qualifiedName(schema, tableName);
    }
}
