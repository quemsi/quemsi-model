package com.quemsi.model.flow.upsert;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.quemsi.commons.util.Exceptions;
import com.quemsi.commons.util.StringUtils;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.ContraintInfo;
import com.quemsi.model.flow.db.sql.DbModel.IndexInfo;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.util.CommonHelpers;

/**
 * Picks a single unique identity to match source rows to target rows.
 * PK only, one unique, or PK plus exactly one other unique (use the non-PK unique).
 */
public class UpsertMatchKeyResolver {

    public UpsertMatchKey resolve(DbModel model, DbTable table) {
        List<UniqueIdentity> identities = collectIdentities(model, table);
        UniqueIdentity chosen;
        if (identities.isEmpty()) {
            throw Exceptions.badRequest("upsert-no-match-key")
                .withExtra("table", table.qualifiedName())
                .get();
        } else if (identities.size() == 1) {
            chosen = identities.get(0);
        } else {
            UniqueIdentity pk = identities.stream().filter(UniqueIdentity::primaryKey).findFirst().orElse(null);
            List<UniqueIdentity> nonPk = identities.stream().filter(i -> !i.primaryKey()).toList();
            if (pk != null && nonPk.size() == 1) {
                chosen = nonPk.get(0);
            } else {
                throw Exceptions.badRequest("upsert-ambiguous-match-key")
                    .withExtra("table", table.qualifiedName())
                    .withExtra("identities", identities.stream().map(UniqueIdentity::label).toList())
                    .get();
            }
        }
        for (String columnName : chosen.columns()) {
            DbColumn column = table.column(columnName);
            if (column == null) {
                throw Exceptions.badRequest("upsert-match-column-missing")
                    .withExtra("table", table.qualifiedName())
                    .withExtra("column", columnName)
                    .get();
            }
            if (column.isNullable()) {
                throw Exceptions.badRequest("upsert-match-key-nullable")
                    .withExtra("table", table.qualifiedName())
                    .withExtra("column", columnName)
                    .get();
            }
        }
        return UpsertMatchKey.builder()
            .columns(List.copyOf(chosen.columns()))
            .primaryKey(chosen.primaryKey())
            .source(chosen.label())
            .build();
    }

    List<UniqueIdentity> collectIdentities(DbModel model, DbTable table) {
        List<UniqueIdentity> identities = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (!CommonHelpers.isEmptyOrNull(table.getPkColumnNames())) {
            UniqueIdentity pk = UniqueIdentity.pk(table.getPkColumnNames());
            identities.add(pk);
            seen.add(pk.signature());
        }
        if (model.getContraintInfos() != null) {
            for (ContraintInfo constraint : model.getContraintInfos()) {
                if (!sameTable(table, constraint.getSchema(), constraint.getTableName())) {
                    continue;
                }
                if (constraint.getColumnNames() == null || constraint.getColumnNames().isEmpty()) {
                    continue;
                }
                UniqueIdentity identity = UniqueIdentity.unique(
                    constraint.getConstraintName(), new ArrayList<>(constraint.getColumnNames()));
                if (seen.add(identity.signature())) {
                    identities.add(identity);
                }
            }
        }
        Map<String, IndexInfo> indexes = model.indexesForTable(table.qualifiedName());
        for (IndexInfo index : indexes.values()) {
            if (index == null || !index.isUnique()) {
                continue;
            }
            if (index.isXmlIndex()) {
                continue;
            }
            if (isPrefixIndex(index)) {
                continue;
            }
            if (index.getColumns() == null || index.getColumns().isEmpty()) {
                continue;
            }
            if (isPrimaryIndexName(index.getIndexName())) {
                continue;
            }
            UniqueIdentity identity = UniqueIdentity.unique(index.getIndexName(), new ArrayList<>(index.getColumns()));
            if (seen.add(identity.signature())) {
                identities.add(identity);
            }
        }
        return identities;
    }

    private static boolean isPrefixIndex(IndexInfo index) {
        if (index.getColumnPrefixLengths() == null) {
            return false;
        }
        for (Integer length : index.getColumnPrefixLengths()) {
            if (length != null && length > 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPrimaryIndexName(String indexName) {
        if (StringUtils.isEmptyOrNull(indexName)) {
            return false;
        }
        String normalized = indexName.trim().toUpperCase(Locale.ROOT);
        return "PRIMARY".equals(normalized) || "PRIMARY KEY".equals(normalized);
    }

    private static boolean sameTable(DbTable table, String schema, String tableName) {
        if (tableName == null) {
            return false;
        }
        String qualified = CommonHelpers.qualifiedName(schema, tableName);
        return table.qualifiedName().equals(qualified) || table.getName().equals(tableName);
    }

    record UniqueIdentity(List<String> columns, boolean primaryKey, String name) {
        static UniqueIdentity pk(List<String> columns) {
            return new UniqueIdentity(List.copyOf(columns), true, "PRIMARY KEY");
        }

        static UniqueIdentity unique(String name, List<String> columns) {
            return new UniqueIdentity(List.copyOf(columns), false, name != null ? name : "UNIQUE");
        }

        String signature() {
            return String.join("\0", columns);
        }

        String label() {
            return primaryKey ? "PRIMARY KEY" : name;
        }
    }
}
