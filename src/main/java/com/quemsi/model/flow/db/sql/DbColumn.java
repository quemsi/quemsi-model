package com.quemsi.model.flow.db.sql;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.quemsi.model.flow.db.sql.DbModel.ReferencedColumn;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DbColumn {
    @JsonIgnore
    @Getter
    private DbTable table;
    @Getter
    private String name;
    @Getter
    private String dataType;
    @Getter
    private ReferencedColumn references;
    @Getter
    private String constraintName;
    @Getter
    private Integer ordinalPosition;
    @Getter
    private Integer maxLength;
    @Getter
    private String columnType;
    @Getter
    private Integer numPrecision;
    @Getter
    private Integer numScale;
    @Getter
    private String columnKey;
    @Getter
    private String columnDefault;
    @Getter
    private boolean nullable;
}
