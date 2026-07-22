package com.quemsi.model.flow.db.sql;

import java.util.Locale;
import java.util.Set;

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
    private static final Set<String> NON_ORDERABLE_DATA_TYPES = Set.of(
        "BLOB", "CLOB", "NCLOB", "BFILE", "LONG", "LONG RAW",
        "TINYBLOB", "MEDIUMBLOB", "LONGBLOB",
        "TINYTEXT", "TEXT", "MEDIUMTEXT", "LONGTEXT",
        "IMAGE", "NTEXT", "XMLTYPE", "XML"
    );

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
    /** SQL Server typed XML: schema.collection used as xml([schema].[collection]). */
    @Getter
    @Setter
    private String xmlSchemaCollection;

    /**
     * Columns that cannot appear in {@code ORDER BY} (LOB / long / xml-like types).
     * Used when paging tables that have no primary key.
     */
    @JsonIgnore
    public boolean isOrderable() {
        String type = dataType != null && !dataType.isBlank() ? dataType : columnType;
        if (type == null || type.isBlank()) {
            return true;
        }
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        int paren = normalized.indexOf('(');
        if (paren > 0) {
            normalized = normalized.substring(0, paren).trim();
        }
        return !NON_ORDERABLE_DATA_TYPES.contains(normalized);
    }
}
