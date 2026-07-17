package com.quemsi.model.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.CheckConstraint;
import com.quemsi.model.flow.db.sql.DbModel.ContraintInfo;
import com.quemsi.model.flow.db.sql.DbModel.IndexInfo;
import com.quemsi.model.flow.db.sql.DbModel.ReferenceInfo;
import com.quemsi.model.flow.db.sql.DbSequence;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.db.sql.DbView;
import com.quemsi.model.flow.db.sql.diff.DbCheckConstraintDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbColumnDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbForeignKeyDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbIndexDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbModelDiff;
import com.quemsi.model.flow.db.sql.diff.DbSequenceDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbTableDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbUniqueConstraintDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbViewDiffOp;
import com.quemsi.model.flow.db.sql.diff.DiffOpType;
import com.quemsi.model.util.CommonHelpers;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DbModelDiffExtractor {
    public DbModelDiff extract(DbModel source, DbModel target) {
        DbModelDiff diff = new DbModelDiff();

        Map<String, DbTable> sourceTables = new HashMap<>(source.getTables());
        Map<String, DbTable> targetTables = new HashMap<>(target.getTables());

        Set<String> sourceTableNames = new HashSet<>(sourceTables.keySet());
        Set<String> targetTableNames = new HashSet<>(targetTables.keySet());

        List<String> createdTables = new ArrayList<>(difference(sourceTableNames, targetTableNames));
        List<String> droppedTables = new ArrayList<>(difference(targetTableNames, sourceTableNames));
        List<String> commonTables = new ArrayList<>(intersection(sourceTableNames, targetTableNames));
        Collections.sort(createdTables);
        Collections.sort(droppedTables);
        Collections.sort(commonTables);

        for (String tableName : createdTables) {
            DbTable table = sourceTables.get(tableName);
            diff.getOperations().add(DbTableDiffOp.builder()
                .opType(DiffOpType.CREATE)
                .qualifiedName(tableName)
                .newTable(table)
                .build());
        }

        for (String tableName : droppedTables) {
            DbTable table = targetTables.get(tableName);
            diff.getOperations().add(DbTableDiffOp.builder()
                .opType(DiffOpType.DROP)
                .qualifiedName(tableName)
                .oldTable(table)
                .build());
        }

        for (String tableName : commonTables) {
            DbTable sourceTable = sourceTables.get(tableName);
            DbTable targetTable = targetTables.get(tableName);
            collectColumnDiffs(diff, tableName, sourceTable, targetTable);
        }

        Set<String> skipTables = new HashSet<>();
        skipTables.addAll(createdTables);
        skipTables.addAll(droppedTables);

        collectReferenceDiffs(diff, source, target, skipTables);
        collectUniqueConstraintDiffs(diff, source, target, skipTables);
        collectCheckConstraintDiffs(diff, source, target, skipTables);
        collectIndexDiffs(diff, source, target, skipTables);
        collectSequenceDiffs(diff, source, target);
        collectViewDiffs(diff, source, target);

        return diff;
    }

    private void collectColumnDiffs(DbModelDiff diff, String tableQualifiedName, DbTable sourceTable, DbTable targetTable) {
        Map<String, DbColumn> sourceColumns = new HashMap<>(sourceTable.getColumns());
        Map<String, DbColumn> targetColumns = new HashMap<>(targetTable.getColumns());

        Set<String> sourceColumnNames = new HashSet<>(sourceColumns.keySet());
        Set<String> targetColumnNames = new HashSet<>(targetColumns.keySet());

        List<String> addedColumns = new ArrayList<>(difference(sourceColumnNames, targetColumnNames));
        List<String> removedColumns = new ArrayList<>(difference(targetColumnNames, sourceColumnNames));
        List<String> commonColumns = new ArrayList<>(intersection(sourceColumnNames, targetColumnNames));
        Collections.sort(addedColumns);
        Collections.sort(removedColumns);
        Collections.sort(commonColumns);

        for (String columnName : addedColumns) {
            DbColumn column = sourceColumns.get(columnName);
            diff.getOperations().add(DbColumnDiffOp.builder()
                .opType(DiffOpType.CREATE)
                .qualifiedName(columnQualifiedName(tableQualifiedName, columnName))
                .tableQualifiedName(tableQualifiedName)
                .columnName(columnName)
                .newColumn(column)
                .build());
        }

        for (String columnName : removedColumns) {
            DbColumn column = targetColumns.get(columnName);
            diff.getOperations().add(DbColumnDiffOp.builder()
                .opType(DiffOpType.DROP)
                .qualifiedName(columnQualifiedName(tableQualifiedName, columnName))
                .tableQualifiedName(tableQualifiedName)
                .columnName(columnName)
                .oldColumn(column)
                .build());
        }

        for (String columnName : commonColumns) {
            DbColumn sourceColumn = sourceColumns.get(columnName);
            DbColumn targetColumn = targetColumns.get(columnName);
            if (!sameColumn(sourceColumn, targetColumn, tableQualifiedName, columnName)) {
                diff.getOperations().add(DbColumnDiffOp.builder()
                    .opType(DiffOpType.MODIFY)
                    .qualifiedName(columnQualifiedName(tableQualifiedName, columnName))
                    .tableQualifiedName(tableQualifiedName)
                    .columnName(columnName)
                    .oldColumn(targetColumn)
                    .newColumn(sourceColumn)
                    .build());
            }
        }
    }

    private void collectReferenceDiffs(DbModelDiff diff, DbModel source, DbModel target, Set<String> skipTables) {
        Map<String, ReferenceInfo> sourceRefs = source.getReferenceInfos().stream()
            .collect(Collectors.toMap(this::referenceKey, r -> r, (a, b) -> a));
        Map<String, ReferenceInfo> targetRefs = target.getReferenceInfos().stream()
            .collect(Collectors.toMap(this::referenceKey, r -> r, (a, b) -> a));

        List<String> addedKeys = new ArrayList<>(difference(sourceRefs.keySet(), targetRefs.keySet()));
        List<String> removedKeys = new ArrayList<>(difference(targetRefs.keySet(), sourceRefs.keySet()));
        List<String> commonKeys = new ArrayList<>(intersection(sourceRefs.keySet(), targetRefs.keySet()));
        Collections.sort(addedKeys);
        Collections.sort(removedKeys);
        Collections.sort(commonKeys);

        for (String key : addedKeys) {
            ReferenceInfo ref = sourceRefs.get(key);
            if (skipTables.contains(ref.srcQualifiedName())) {
                continue;
            }
            diff.getOperations().add(DbForeignKeyDiffOp.builder()
                .opType(DiffOpType.CREATE)
                .qualifiedName(ref.qualifiedConstraintName())
                .newReference(ref)
                .build());
        }

        for (String key : removedKeys) {
            ReferenceInfo ref = targetRefs.get(key);
            if (skipTables.contains(ref.srcQualifiedName())) {
                continue;
            }
            diff.getOperations().add(DbForeignKeyDiffOp.builder()
                .opType(DiffOpType.DROP)
                .qualifiedName(ref.qualifiedConstraintName())
                .oldReference(ref)
                .build());
        }

        for (String key : commonKeys) {
            ReferenceInfo sourceRef = sourceRefs.get(key);
            ReferenceInfo targetRef = targetRefs.get(key);
            if (skipTables.contains(sourceRef.srcQualifiedName())) {
                continue;
            }
            if (!sameReference(sourceRef, targetRef)) {
                diff.getOperations().add(DbForeignKeyDiffOp.builder()
                    .opType(DiffOpType.MODIFY)
                    .qualifiedName(sourceRef.qualifiedConstraintName())
                    .oldReference(targetRef)
                    .newReference(sourceRef)
                    .build());
            }
        }
    }

    private void collectUniqueConstraintDiffs(DbModelDiff diff, DbModel source, DbModel target, Set<String> skipTables) {
        Map<String, ContraintInfo> sourceConstraints = source.getContraintInfos().stream()
            .collect(Collectors.toMap(this::uniqueConstraintKey, c -> c, (a, b) -> a));
        Map<String, ContraintInfo> targetConstraints = target.getContraintInfos().stream()
            .collect(Collectors.toMap(this::uniqueConstraintKey, c -> c, (a, b) -> a));

        List<String> addedKeys = new ArrayList<>(difference(sourceConstraints.keySet(), targetConstraints.keySet()));
        List<String> removedKeys = new ArrayList<>(difference(targetConstraints.keySet(), sourceConstraints.keySet()));
        List<String> commonKeys = new ArrayList<>(intersection(sourceConstraints.keySet(), targetConstraints.keySet()));
        Collections.sort(addedKeys);
        Collections.sort(removedKeys);
        Collections.sort(commonKeys);

        for (String key : addedKeys) {
            ContraintInfo constraint = sourceConstraints.get(key);
            if (skipTables.contains(constraint.qualifiedTableName())) {
                continue;
            }
            diff.getOperations().add(DbUniqueConstraintDiffOp.builder()
                .opType(DiffOpType.CREATE)
                .qualifiedName(constraint.qualifiedConstraintName())
                .newConstraint(constraint)
                .build());
        }

        for (String key : removedKeys) {
            ContraintInfo constraint = targetConstraints.get(key);
            if (skipTables.contains(constraint.qualifiedTableName())) {
                continue;
            }
            diff.getOperations().add(DbUniqueConstraintDiffOp.builder()
                .opType(DiffOpType.DROP)
                .qualifiedName(constraint.qualifiedConstraintName())
                .oldConstraint(constraint)
                .build());
        }

        for (String key : commonKeys) {
            ContraintInfo sourceConstraint = sourceConstraints.get(key);
            ContraintInfo targetConstraint = targetConstraints.get(key);
            if (skipTables.contains(sourceConstraint.qualifiedTableName())) {
                continue;
            }
            if (!sameUniqueConstraint(sourceConstraint, targetConstraint)) {
                diff.getOperations().add(DbUniqueConstraintDiffOp.builder()
                    .opType(DiffOpType.MODIFY)
                    .qualifiedName(sourceConstraint.qualifiedConstraintName())
                    .oldConstraint(targetConstraint)
                    .newConstraint(sourceConstraint)
                    .build());
            }
        }
    }

    private void collectCheckConstraintDiffs(DbModelDiff diff, DbModel source, DbModel target, Set<String> skipTables) {
        Map<String, CheckConstraint> sourceConstraints = source.getCheckConstraints().stream()
            .collect(Collectors.toMap(this::checkConstraintKey, c -> c, (a, b) -> a));
        Map<String, CheckConstraint> targetConstraints = target.getCheckConstraints().stream()
            .collect(Collectors.toMap(this::checkConstraintKey, c -> c, (a, b) -> a));

        List<String> addedKeys = new ArrayList<>(difference(sourceConstraints.keySet(), targetConstraints.keySet()));
        List<String> removedKeys = new ArrayList<>(difference(targetConstraints.keySet(), sourceConstraints.keySet()));
        List<String> commonKeys = new ArrayList<>(intersection(sourceConstraints.keySet(), targetConstraints.keySet()));
        Collections.sort(addedKeys);
        Collections.sort(removedKeys);
        Collections.sort(commonKeys);

        for (String key : addedKeys) {
            CheckConstraint constraint = sourceConstraints.get(key);
            if (skipTables.contains(constraint.qualifiedTableName())) {
                continue;
            }
            diff.getOperations().add(DbCheckConstraintDiffOp.builder()
                .opType(DiffOpType.CREATE)
                .qualifiedName(constraint.qualifiedConstraintName())
                .newConstraint(constraint)
                .build());
        }

        for (String key : removedKeys) {
            CheckConstraint constraint = targetConstraints.get(key);
            if (skipTables.contains(constraint.qualifiedTableName())) {
                continue;
            }
            diff.getOperations().add(DbCheckConstraintDiffOp.builder()
                .opType(DiffOpType.DROP)
                .qualifiedName(constraint.qualifiedConstraintName())
                .oldConstraint(constraint)
                .build());
        }

        for (String key : commonKeys) {
            CheckConstraint sourceConstraint = sourceConstraints.get(key);
            CheckConstraint targetConstraint = targetConstraints.get(key);
            if (skipTables.contains(sourceConstraint.qualifiedTableName())) {
                continue;
            }
            if (!sameCheckConstraint(sourceConstraint, targetConstraint)) {
                diff.getOperations().add(DbCheckConstraintDiffOp.builder()
                    .opType(DiffOpType.MODIFY)
                    .qualifiedName(sourceConstraint.qualifiedConstraintName())
                    .oldConstraint(targetConstraint)
                    .newConstraint(sourceConstraint)
                    .build());
            }
        }
    }

    private void collectIndexDiffs(DbModelDiff diff, DbModel source, DbModel target, Set<String> skipTables) {
        Map<String, IndexInfo> sourceIndexes = indexMap(source);
        Map<String, IndexInfo> targetIndexes = indexMap(target);

        List<String> addedKeys = new ArrayList<>(difference(sourceIndexes.keySet(), targetIndexes.keySet()));
        List<String> removedKeys = new ArrayList<>(difference(targetIndexes.keySet(), sourceIndexes.keySet()));
        List<String> commonKeys = new ArrayList<>(intersection(sourceIndexes.keySet(), targetIndexes.keySet()));
        Collections.sort(addedKeys);
        Collections.sort(removedKeys);
        Collections.sort(commonKeys);

        for (String key : addedKeys) {
            IndexInfo index = sourceIndexes.get(key);
            String tableName = indexQualifiedTableName(index);
            if (skipTables.contains(tableName)) {
                continue;
            }
            diff.getOperations().add(DbIndexDiffOp.builder()
                .opType(DiffOpType.CREATE)
                .qualifiedName(indexQualifiedName(index))
                .tableQualifiedName(tableName)
                .newIndex(index)
                .build());
        }

        for (String key : removedKeys) {
            IndexInfo index = targetIndexes.get(key);
            String tableName = indexQualifiedTableName(index);
            if (skipTables.contains(tableName)) {
                continue;
            }
            diff.getOperations().add(DbIndexDiffOp.builder()
                .opType(DiffOpType.DROP)
                .qualifiedName(indexQualifiedName(index))
                .tableQualifiedName(tableName)
                .oldIndex(index)
                .build());
        }

        for (String key : commonKeys) {
            IndexInfo sourceIndex = sourceIndexes.get(key);
            IndexInfo targetIndex = targetIndexes.get(key);
            String tableName = indexQualifiedTableName(sourceIndex);
            if (skipTables.contains(tableName)) {
                continue;
            }
            if (!sameIndex(sourceIndex, targetIndex)) {
                diff.getOperations().add(DbIndexDiffOp.builder()
                    .opType(DiffOpType.MODIFY)
                    .qualifiedName(indexQualifiedName(sourceIndex))
                    .tableQualifiedName(tableName)
                    .oldIndex(targetIndex)
                    .newIndex(sourceIndex)
                    .build());
            }
        }
    }

    private void collectSequenceDiffs(DbModelDiff diff, DbModel source, DbModel target) {
        Map<String, DbSequence> sourceSequences = source.getSequences().stream()
            .collect(Collectors.toMap(DbSequence::qualifiedName, s -> s, (a, b) -> a));
        Map<String, DbSequence> targetSequences = target.getSequences().stream()
            .collect(Collectors.toMap(DbSequence::qualifiedName, s -> s, (a, b) -> a));

        List<String> addedKeys = new ArrayList<>(difference(sourceSequences.keySet(), targetSequences.keySet()));
        List<String> removedKeys = new ArrayList<>(difference(targetSequences.keySet(), sourceSequences.keySet()));
        List<String> commonKeys = new ArrayList<>(intersection(sourceSequences.keySet(), targetSequences.keySet()));
        Collections.sort(addedKeys);
        Collections.sort(removedKeys);
        Collections.sort(commonKeys);

        for (String key : addedKeys) {
            DbSequence seq = sourceSequences.get(key);
            diff.getOperations().add(DbSequenceDiffOp.builder()
                .opType(DiffOpType.CREATE)
                .qualifiedName(seq.qualifiedName())
                .newSequence(seq)
                .build());
        }

        for (String key : removedKeys) {
            DbSequence seq = targetSequences.get(key);
            diff.getOperations().add(DbSequenceDiffOp.builder()
                .opType(DiffOpType.DROP)
                .qualifiedName(seq.qualifiedName())
                .oldSequence(seq)
                .build());
        }

        for (String key : commonKeys) {
            DbSequence sourceSeq = sourceSequences.get(key);
            DbSequence targetSeq = targetSequences.get(key);
            if (!sameSequence(sourceSeq, targetSeq)) {
                diff.getOperations().add(DbSequenceDiffOp.builder()
                    .opType(DiffOpType.MODIFY)
                    .qualifiedName(sourceSeq.qualifiedName())
                    .oldSequence(targetSeq)
                    .newSequence(sourceSeq)
                    .build());
            }
        }
    }

    private void collectViewDiffs(DbModelDiff diff, DbModel source, DbModel target) {
        List<DbView> sourceViews = source.getViews() != null ? source.getViews() : List.of();
        List<DbView> targetViews = target.getViews() != null ? target.getViews() : List.of();
        Map<String, DbView> sourceByName = sourceViews.stream()
            .collect(Collectors.toMap(DbView::qualifiedName, v -> v, (a, b) -> a));
        Map<String, DbView> targetByName = targetViews.stream()
            .collect(Collectors.toMap(DbView::qualifiedName, v -> v, (a, b) -> a));

        List<String> addedKeys = new ArrayList<>(difference(sourceByName.keySet(), targetByName.keySet()));
        List<String> removedKeys = new ArrayList<>(difference(targetByName.keySet(), sourceByName.keySet()));
        List<String> commonKeys = new ArrayList<>(intersection(sourceByName.keySet(), targetByName.keySet()));
        Collections.sort(addedKeys);
        Collections.sort(removedKeys);
        Collections.sort(commonKeys);

        for (String key : addedKeys) {
            DbView view = sourceByName.get(key);
            diff.getOperations().add(DbViewDiffOp.builder()
                .opType(DiffOpType.CREATE)
                .qualifiedName(view.qualifiedName())
                .newView(view)
                .build());
        }
        for (String key : removedKeys) {
            DbView view = targetByName.get(key);
            diff.getOperations().add(DbViewDiffOp.builder()
                .opType(DiffOpType.DROP)
                .qualifiedName(view.qualifiedName())
                .oldView(view)
                .build());
        }
        // Always recreate views present in both — underlying tables may have changed
        for (String key : commonKeys) {
            DbView sourceView = sourceByName.get(key);
            DbView targetView = targetByName.get(key);
            diff.getOperations().add(DbViewDiffOp.builder()
                .opType(DiffOpType.MODIFY)
                .qualifiedName(sourceView.qualifiedName())
                .oldView(targetView)
                .newView(sourceView)
                .build());
        }
    }

    private boolean sameColumn(DbColumn left, DbColumn right, String tableQualifiedName, String columnName) {
        boolean same = true;
        List<String> differences = new ArrayList<>();
        
        if (!Objects.equals(left.getName(), right.getName())) {
            same = false;
            differences.add(String.format("name: '%s' -> '%s'", left.getName(), right.getName()));
        }
        if (!Objects.equals(left.getDataType(), right.getDataType())) {
            same = false;
            differences.add(String.format("dataType: '%s' -> '%s'", left.getDataType(), right.getDataType()));
        }
        if (!Objects.equals(left.getMaxLength(), right.getMaxLength())) {
            same = false;
            differences.add(String.format("maxLength: %s -> %s", left.getMaxLength(), right.getMaxLength()));
        }
        if (!Objects.equals(left.getColumnType(), right.getColumnType())) {
            same = false;
            differences.add(String.format("columnType: '%s' -> '%s'", left.getColumnType(), right.getColumnType()));
        }
        if (!Objects.equals(left.getNumPrecision(), right.getNumPrecision())) {
            same = false;
            differences.add(String.format("numPrecision: %s -> %s", left.getNumPrecision(), right.getNumPrecision()));
        }
        if (!Objects.equals(left.getNumScale(), right.getNumScale())) {
            same = false;
            differences.add(String.format("numScale: %s -> %s", left.getNumScale(), right.getNumScale()));
        }
        if (!Objects.equals(left.getColumnKey(), right.getColumnKey())) {
            same = false;
            differences.add(String.format("columnKey: '%s' -> '%s'", left.getColumnKey(), right.getColumnKey()));
        }
        if (!Objects.equals(left.getColumnDefault(), right.getColumnDefault())) {
            same = false;
            differences.add(String.format("columnDefault: '%s' -> '%s'", left.getColumnDefault(), right.getColumnDefault()));
        }
        if (left.isNullable() != right.isNullable()) {
            same = false;
            differences.add(String.format("nullable: %s -> %s", left.isNullable(), right.isNullable()));
        }
        if (left.isIdentity() != right.isIdentity()) {
            same = false;
            differences.add(String.format("identity: %s -> %s", left.isIdentity(), right.isIdentity()));
        }
        
        if (!same) {
            log.info("Column difference detected in {}.{}: {}", 
                tableQualifiedName, columnName, String.join(", ", differences));
        }
        
        return same;
    }

    private boolean sameReference(ReferenceInfo left, ReferenceInfo right) {
        return Objects.equals(left.getConstraintName(), right.getConstraintName())
            && Objects.equals(left.getSrcSchema(), right.getSrcSchema())
            && Objects.equals(left.getSrcTableName(), right.getSrcTableName())
            && Objects.equals(left.getSrcColumnNames(), right.getSrcColumnNames())
            && Objects.equals(left.getRefSchema(), right.getRefSchema())
            && Objects.equals(left.getRefTableName(), right.getRefTableName())
            && Objects.equals(left.getRefColumnNames(), right.getRefColumnNames());
    }

    private boolean sameUniqueConstraint(ContraintInfo left, ContraintInfo right) {
        return Objects.equals(left.getConstraintName(), right.getConstraintName())
            && Objects.equals(left.getSchema(), right.getSchema())
            && Objects.equals(left.getTableName(), right.getTableName())
            && Objects.equals(left.getColumnNames(), right.getColumnNames());
    }

    private boolean sameCheckConstraint(CheckConstraint left, CheckConstraint right) {
        return Objects.equals(left.getSchema(), right.getSchema())
            && Objects.equals(left.getTableName(), right.getTableName())
            && Objects.equals(left.getConstraintName(), right.getConstraintName())
            && Objects.equals(left.getCondef(), right.getCondef());
    }

    private boolean sameIndex(IndexInfo left, IndexInfo right) {
        return Objects.equals(left.getSchemaName(), right.getSchemaName())
            && Objects.equals(left.getTableName(), right.getTableName())
            && Objects.equals(left.getIndexName(), right.getIndexName())
            && left.isUnique() == right.isUnique()
            && Objects.equals(left.getIndexType(), right.getIndexType())
            && Objects.equals(left.getColumns(), right.getColumns())
            && Objects.equals(left.getExtraColumns(), right.getExtraColumns());
    }

    private boolean sameSequence(DbSequence left, DbSequence right) {
        return Objects.equals(left.getSchema(), right.getSchema())
            && Objects.equals(left.getName(), right.getName())
            && Objects.equals(left.getStartValue(), right.getStartValue())
            && Objects.equals(left.getMinValue(), right.getMinValue())
            && Objects.equals(left.getMaxValue(), right.getMaxValue())
            && Objects.equals(left.getIncrementBy(), right.getIncrementBy())
            && left.isCycle() == right.isCycle()
            && Objects.equals(left.getCacheSize(), right.getCacheSize())
            && Objects.equals(left.getLastValue(), right.getLastValue());
    }

    private Map<String, IndexInfo> indexMap(DbModel model) {
        Map<String, IndexInfo> result = new HashMap<>();
        for (Map<String, IndexInfo> tableIndexes : model.getIndexes().values()) {
            for (IndexInfo index : tableIndexes.values()) {
                result.put(indexKey(index), index);
            }
        }
        return result;
    }

    private String referenceKey(ReferenceInfo ref) {
        return ref.srcQualifiedName() + "|" + ref.qualifiedConstraintName();
    }

    private String uniqueConstraintKey(ContraintInfo constraint) {
        return constraint.qualifiedTableName() + "|" + constraint.qualifiedConstraintName();
    }

    private String checkConstraintKey(CheckConstraint constraint) {
        return constraint.qualifiedTableName() + "|" + constraint.qualifiedConstraintName();
    }

    private String indexKey(IndexInfo index) {
        return indexQualifiedTableName(index) + "|" + index.getIndexName();
    }

    private String indexQualifiedName(IndexInfo index) {
        return CommonHelpers.qualifiedName(index.getSchemaName(), index.getIndexName());
    }

    private String indexQualifiedTableName(IndexInfo index) {
        return CommonHelpers.qualifiedName(index.getSchemaName(), index.getTableName());
    }

    private String columnQualifiedName(String tableQualifiedName, String columnName) {
        return tableQualifiedName + "." + columnName;
    }

    private Set<String> difference(Set<String> left, Set<String> right) {
        return left.stream().filter(item -> !right.contains(item)).collect(Collectors.toSet());
    }

    private Set<String> intersection(Set<String> left, Set<String> right) {
        return left.stream().filter(right::contains).collect(Collectors.toSet());
    }
}
