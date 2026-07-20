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
public class DbDomainType {
    private String schema;
    private String name;
    /** Base type e.g. integer, character varying(50). */
    private String baseType;
    private boolean notNull;
    private String defaultExpression;
    private String checkConstraintName;
    /** e.g. CHECK (VALUE >= 1901 AND VALUE <= 2155) */
    private String checkConstraintDef;

    public String qualifiedName() {
        return CommonHelpers.qualifiedName(schema, name);
    }
}
