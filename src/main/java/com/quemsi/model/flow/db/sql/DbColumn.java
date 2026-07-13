package com.quemsi.model.flow.db.sql;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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
    @Setter
    private String defaultConstraintName;
    @Getter
    private boolean nullable;
    @Getter
    private boolean identity;
    @Getter
    private String comment;
}
