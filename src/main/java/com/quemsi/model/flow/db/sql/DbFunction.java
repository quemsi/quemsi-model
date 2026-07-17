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
public class DbFunction {
    public static final String TYPE_FUNCTION = "FUNCTION";
    public static final String TYPE_PROCEDURE = "PROCEDURE";

    private String schema;
    private String name;
    /** FUNCTION or PROCEDURE (defaults to FUNCTION when null for older backups). */
    private String routineType;
    /** Full CREATE [OR REPLACE] FUNCTION/PROCEDURE statement. */
    private String definition;
    /** Identity argument list (e.g. Postgres pg_get_function_identity_arguments). */
    private String identityArguments;

    public String qualifiedName() {
        return CommonHelpers.qualifiedName(schema, name);
    }

    public String resolvedRoutineType() {
        if (routineType == null || routineType.isBlank()) {
            return TYPE_FUNCTION;
        }
        return routineType.trim().toUpperCase();
    }
}
