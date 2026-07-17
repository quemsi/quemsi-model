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
    private String schema;
    private String name;
    /** Full CREATE FUNCTION / CREATE OR REPLACE FUNCTION statement. */
    private String definition;
    /** Identity argument list from pg_get_function_identity_arguments (for DROP). */
    private String identityArguments;

    public String qualifiedName() {
        return CommonHelpers.qualifiedName(schema, name);
    }
}
