package com.quemsi.model.flow.db.sql;

import java.util.ArrayList;
import java.util.List;

import com.quemsi.model.util.CommonHelpers;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DbEnumType {
    private String schema;
    private String name;
    @Builder.Default
    private List<String> labels = new ArrayList<>();

    public String qualifiedName() {
        return CommonHelpers.qualifiedName(schema, name);
    }
}
