package com.quemsi.model.dto;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpsertConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final int DEFAULT_MAX_ROWS = 10_000;

    public enum OnExisting {
        UPDATE,
        SKIP
    }

    /**
     * If true (default), plan and log without writing.
     */
    @Builder.Default
    private Boolean dryRun = true;

    /**
     * What to do when a source row's match key already exists on the target.
     */
    @Builder.Default
    private OnExisting onExisting = OnExisting.UPDATE;

    /**
     * Qualified table names to upsert. Required; never defaults to all archive tables.
     */
    @Builder.Default
    private List<String> tables = new ArrayList<>();

    /**
     * Fail planning when selected source rows exceed this count.
     */
    @Builder.Default
    private Integer maxRows = DEFAULT_MAX_ROWS;

    public boolean isDryRun() {
        return dryRun == null || Boolean.TRUE.equals(dryRun);
    }

    public OnExisting onExistingOrDefault() {
        return onExisting != null ? onExisting : OnExisting.UPDATE;
    }

    public int maxRowsOrDefault() {
        return maxRows != null && maxRows > 0 ? maxRows : DEFAULT_MAX_ROWS;
    }
}
