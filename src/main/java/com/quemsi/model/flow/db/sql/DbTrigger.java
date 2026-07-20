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
    private String schema;
    private String tableName;
    private String name;
    /** Full CREATE TRIGGER statement from pg_get_triggerdef. */
    private String definition;
    private String functionSchema;
    private String functionName;

    public String qualifiedTableName() {
        return CommonHelpers.qualifiedName(schema, tableName);
    }
}
