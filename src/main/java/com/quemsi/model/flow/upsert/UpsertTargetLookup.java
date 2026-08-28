package com.quemsi.model.flow.upsert;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.quemsi.model.flow.db.sql.DbTable;

public interface UpsertTargetLookup {
    Set<String> existingKeys(DbTable table, List<String> keyColumns, Collection<String> candidateKeys);

    /**
     * Maps unique-key string to the target row's match-key string for candidates present on the target.
     */
    Map<String, String> uniqueToMatchKey(DbTable table, List<String> uniqueColumns, List<String> matchColumns,
            Collection<String> uniqueKeys);

    /**
     * Existing target rows keyed by match-key string. Values are aligned to {@link DbTable#orderedColumns()}.
     * A {@code null} value array means the row exists but column values were not loaded.
     */
    Map<String, Object[]> existingRows(DbTable table, List<String> keyColumns, Collection<String> candidateKeys);
}
