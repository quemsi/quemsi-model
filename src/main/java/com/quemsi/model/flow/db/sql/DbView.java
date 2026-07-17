package com.quemsi.model.flow.db.sql;

import java.util.HashSet;
import java.util.Set;

import com.quemsi.model.util.CommonHelpers;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DbView {
    private String schema;
    private String name;
    /** SELECT body only (without CREATE VIEW … AS). */
    private String definition;
    /** Qualified names of other views this view depends on. */
    @Builder.Default
    private Set<String> dependsOnViews = new HashSet<>();

    public String qualifiedName() {
        return CommonHelpers.qualifiedName(schema, name);
    }
}
