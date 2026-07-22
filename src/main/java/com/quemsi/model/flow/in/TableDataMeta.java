package com.quemsi.model.flow.in;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-table metadata stored as {@code tables/{qualifiedName}/meta.json} in a backup archive.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TableDataMeta {
    private String tableName;
    private Integer pageSize;
    private Integer totalPages;
    private Integer totalRecords;
    @Builder.Default
    /** tabular (default for RDBMS) or document (MongoDB). */
    private String dataFormat = TableData.FORMAT_TABULAR;
}
