package com.quemsi.model.flow.upsert;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.dto.UpsertConfig;
import com.quemsi.model.dto.UpsertConfig.OnExisting;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.ReferenceInfo;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.subset.SqlSubsetSupport;
import com.quemsi.model.util.CommonHelpers;

public class UpsertPlanner {
    private final UpsertMatchKeyResolver matchKeyResolver = new UpsertMatchKeyResolver();
    private final UpsertSchemaGate schemaGate = new UpsertSchemaGate();

    public UpsertPlan plan(DbModel sourceModel, DbModel targetModel, UpsertConfig config,
            UpsertRowSource rowSource, UpsertTargetLookup lookup) {
        if (config == null || config.getTables() == null || config.getTables().isEmpty()) {
            throw Exceptions.badRequest("upsert-tables-required").get();
        }
        List<String> allowlist = config.getTables().stream()
            .filter(n -> n != null && !n.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
        if (allowlist.isEmpty()) {
            throw Exceptions.badRequest("upsert-tables-required").get();
        }

        schemaGate.assertCompatible(sourceModel, targetModel, allowlist);

        Map<String, DbTable> selected = new LinkedHashMap<>();
        Map<String, UpsertMatchKey> matchKeys = new LinkedHashMap<>();
        for (String name : allowlist) {
            DbTable sourceTable = UpsertTables.resolve(sourceModel, name);
            selected.put(sourceTable.qualifiedName(), sourceTable);
            matchKeys.put(sourceTable.qualifiedName(), matchKeyResolver.resolve(sourceModel, sourceTable));
        }

        List<String> ordered = new ArrayList<>();
        Set<String> selectedNames = selected.keySet();
        for (DbTable table : sourceModel.orderedTables()) {
            if (selectedNames.contains(table.qualifiedName())) {
                ordered.add(table.qualifiedName());
            }
        }
        for (String name : selectedNames) {
            if (!ordered.contains(name)) {
                ordered.add(name);
            }
        }

        List<UpsertFailure> failures = new ArrayList<>();
        failRemappedParentFks(sourceModel, selectedNames, matchKeys, failures);

        Map<String, List<Object[]>> rowsByTable = new LinkedHashMap<>();
        int totalRows = 0;
        for (String qualifiedName : ordered) {
            List<Object[]> rows = rowSource.loadRows(qualifiedName);
            if (rows == null) {
                rows = List.of();
            }
            rowsByTable.put(qualifiedName, rows);
            totalRows += rows.size();
        }
        int maxRows = config.maxRowsOrDefault();
        if (totalRows > maxRows) {
            throw Exceptions.badRequest("upsert-max-rows-exceeded")
                .withExtra("totalRows", totalRows)
                .withExtra("maxRows", maxRows)
                .get();
        }

        OnExisting onExisting = config.onExistingOrDefault();
        List<UpsertTablePlan> tablePlans = new ArrayList<>();
        for (String qualifiedName : ordered) {
            DbTable table = selected.get(qualifiedName);
            UpsertMatchKey matchKey = matchKeys.get(qualifiedName);
            List<String> omitColumns = matchKey.isPrimaryKey() ? List.of() : pkColumnList(table);
            List<Object[]> rows = rowsByTable.get(qualifiedName);
            List<UpsertRow> sourceRows = new ArrayList<>();
            for (Object[] values : rows) {
                String key = encodeColumns(table, values, matchKey.getColumns());
                if (key == null) {
                    failures.add(new UpsertFailure(qualifiedName, "", "null match key"));
                    continue;
                }
                sourceRows.add(new UpsertRow(key, values));
            }
            Set<String> sourceKeys = sourceRows.stream().map(UpsertRow::getKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
            Map<String, Object[]> existingRows = lookup.existingRows(table, matchKey.getColumns(), sourceKeys);

            List<String> compareColumns = compareColumns(table, omitColumns, matchKey.getColumns());
            List<UpsertRow> inserts = new ArrayList<>();
            List<UpsertRow> updates = new ArrayList<>();
            List<UpsertRow> skips = new ArrayList<>();
            for (UpsertRow row : sourceRows) {
                if (!existingRows.containsKey(row.getKey())) {
                    inserts.add(row);
                    continue;
                }
                if (onExisting == OnExisting.SKIP) {
                    skips.add(row);
                    continue;
                }
                if (rowChanged(table, row, existingRows.get(row.getKey()), compareColumns)) {
                    updates.add(row);
                } else {
                    skips.add(row);
                }
            }

            probeUniqueCollisions(sourceModel, table, matchKey, sourceRows, lookup, failures);

            tablePlans.add(UpsertTablePlan.builder()
                .qualifiedName(qualifiedName)
                .table(table)
                .matchKey(matchKey)
                .omitColumns(omitColumns)
                .inserts(inserts)
                .updates(updates)
                .skips(skips)
                .build());
        }

        UpsertPlan plan = UpsertPlan.builder().tables(tablePlans).failures(failures).build();
        probeForeignKeys(sourceModel, selected, matchKeys, plan, lookup, failures);
        return plan;
    }

    private void failRemappedParentFks(DbModel sourceModel, Set<String> selectedNames,
            Map<String, UpsertMatchKey> matchKeys, List<UpsertFailure> failures) {
        if (sourceModel.getReferenceInfos() == null) {
            return;
        }
        for (ReferenceInfo fk : sourceModel.getReferenceInfos()) {
            if (!selectedNames.contains(fk.srcQualifiedName()) || !selectedNames.contains(fk.refQualifiedName())) {
                continue;
            }
            UpsertMatchKey parentMatch = matchKeys.get(fk.refQualifiedName());
            if (parentMatch != null && !parentMatch.isPrimaryKey()) {
                failures.add(new UpsertFailure(fk.srcQualifiedName(), "",
                    "FK " + fk.getConstraintName() + " references " + fk.refQualifiedName()
                        + " whose match key is not the primary key"));
            }
        }
    }

    private void probeUniqueCollisions(DbModel sourceModel, DbTable table, UpsertMatchKey matchKey,
            List<UpsertRow> sourceRows, UpsertTargetLookup lookup, List<UpsertFailure> failures) {
        List<UpsertMatchKeyResolver.UniqueIdentity> identities = matchKeyResolver.collectIdentities(sourceModel, table);
        for (UpsertMatchKeyResolver.UniqueIdentity identity : identities) {
            if (identity.columns().equals(matchKey.getColumns())) {
                continue;
            }
            Set<String> uniqueKeys = new LinkedHashSet<>();
            Map<String, String> uniqueToSourceMatch = new HashMap<>();
            for (UpsertRow row : sourceRows) {
                String uniqueKey = encodeColumns(table, row.getValues(), identity.columns());
                if (uniqueKey == null) {
                    continue;
                }
                uniqueKeys.add(uniqueKey);
                uniqueToSourceMatch.put(uniqueKey, row.getKey());
            }
            Map<String, String> targetUniqueToMatch = lookup.uniqueToMatchKey(
                table, identity.columns(), matchKey.getColumns(), uniqueKeys);
            for (Map.Entry<String, String> e : targetUniqueToMatch.entrySet()) {
                String sourceMatch = uniqueToSourceMatch.get(e.getKey());
                if (sourceMatch != null && !sourceMatch.equals(e.getValue())) {
                    failures.add(new UpsertFailure(table.qualifiedName(), sourceMatch,
                        "unique " + identity.label() + " collides with target match key " + e.getValue()));
                }
            }
        }
    }

    private void probeForeignKeys(DbModel sourceModel, Map<String, DbTable> selected,
            Map<String, UpsertMatchKey> matchKeys, UpsertPlan plan, UpsertTargetLookup lookup,
            List<UpsertFailure> failures) {
        if (sourceModel.getReferenceInfos() == null) {
            return;
        }
        Set<String> selectedNames = selected.keySet();
        for (ReferenceInfo fk : sourceModel.getReferenceInfos()) {
            if (!selectedNames.contains(fk.srcQualifiedName())) {
                continue;
            }
            UpsertMatchKey parentMatch = matchKeys.get(fk.refQualifiedName());
            if (parentMatch != null && !parentMatch.isPrimaryKey()) {
                continue;
            }
            DbTable child = selected.get(fk.srcQualifiedName());
            DbTable parent = UpsertTables.find(sourceModel, fk.refQualifiedName()).orElse(null);
            if (parent == null || CommonHelpers.isEmptyOrNull(parent.getPkColumnNames())) {
                continue;
            }
            List<String> childFkCols = new ArrayList<>(fk.getSrcColumnNames());
            UpsertTablePlan childPlan = plan.tablePlan(child.qualifiedName());
            if (childPlan == null) {
                continue;
            }
            Set<String> payloadParentKeys = new LinkedHashSet<>();
            UpsertTablePlan parentPlan = plan.tablePlan(fk.refQualifiedName());
            if (parentPlan != null) {
                parentPlan.getInserts().forEach(r -> payloadParentKeys.add(r.getKey()));
                parentPlan.getUpdates().forEach(r -> payloadParentKeys.add(r.getKey()));
                parentPlan.getSkips().forEach(r -> payloadParentKeys.add(r.getKey()));
            }
            List<UpsertRow> childRows = new ArrayList<>();
            childRows.addAll(childPlan.getInserts());
            childRows.addAll(childPlan.getUpdates());
            Set<String> fkKeys = new LinkedHashSet<>();
            Map<String, String> fkToChildMatch = new HashMap<>();
            for (UpsertRow row : childRows) {
                String fkKey = encodeColumns(child, row.getValues(), childFkCols);
                if (fkKey == null) {
                    continue;
                }
                fkKeys.add(fkKey);
                fkToChildMatch.put(fkKey, row.getKey());
            }
            Set<String> existingParent = lookup.existingKeys(parent, parent.getPkColumnNames(), fkKeys);
            for (String fkKey : fkKeys) {
                if (payloadParentKeys.contains(fkKey) || existingParent.contains(fkKey)) {
                    continue;
                }
                failures.add(new UpsertFailure(child.qualifiedName(), fkToChildMatch.get(fkKey),
                    "FK " + fk.getConstraintName() + " parent key " + fkKey + " not found"));
            }
        }
    }

    static boolean rowChanged(DbTable table, UpsertRow source, Object[] targetValues, List<String> compareColumns) {
        if (compareColumns == null || compareColumns.isEmpty()) {
            return false;
        }
        if (source == null || source.getValues() == null || targetValues == null) {
            return true;
        }
        for (String columnName : compareColumns) {
            int idx = columnIndex(table, columnName);
            Object left = idx >= 0 && idx < source.getValues().length ? source.getValues()[idx] : null;
            Object right = idx >= 0 && idx < targetValues.length ? targetValues[idx] : null;
            if (!valuesEqual(left, right)) {
                return true;
            }
        }
        return false;
    }

    static boolean valuesEqual(Object left, Object right) {
        if (left == null && right == null) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        if (left instanceof byte[] leftBytes && right instanceof byte[] rightBytes) {
            return Arrays.equals(leftBytes, rightBytes);
        }
        if (Objects.equals(left, right)) {
            return true;
        }
        return Objects.equals(SqlSubsetSupport.canonicalPkPart(left), SqlSubsetSupport.canonicalPkPart(right));
    }

    static List<String> compareColumns(DbTable table, List<String> omitColumns, List<String> matchColumns) {
        Set<String> skip = new LinkedHashSet<>();
        if (omitColumns != null) {
            skip.addAll(omitColumns);
        }
        if (matchColumns != null) {
            skip.addAll(matchColumns);
        }
        List<String> columns = new ArrayList<>();
        for (DbColumn column : table.orderedColumns()) {
            if (!skip.contains(column.getName())) {
                columns.add(column.getName());
            }
        }
        return columns;
    }

    static String encodeColumns(DbTable table, Object[] values, List<String> columns) {
        if (values == null || columns == null || columns.isEmpty()) {
            return null;
        }
        Object[] parts = new Object[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            int idx = columnIndex(table, columns.get(i));
            if (idx < 0 || idx >= values.length) {
                return null;
            }
            Object value = values[idx];
            if (value == null) {
                return null;
            }
            parts[i] = value;
        }
        return SqlUpsertSupport.encodeKey(parts);
    }

    static int columnIndex(DbTable table, String columnName) {
        DbColumn[] ordered = table.orderedColumns();
        for (int i = 0; i < ordered.length; i++) {
            if (ordered[i].getName().equals(columnName)) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> pkColumnList(DbTable table) {
        if (CommonHelpers.isEmptyOrNull(table.getPkColumnNames())) {
            return List.of();
        }
        return List.copyOf(table.getPkColumnNames());
    }
}
