package com.quemsi.model.flow.subset;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.quemsi.commons.util.Exceptions;
import com.quemsi.commons.util.StringUtils;
import com.quemsi.model.flow.db.DMLService;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.ReferenceInfo;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.subset.SubsetPlan.SubsetTableProvenance;

/**
 * Builds a referentially consistent subset plan: driver seeds + recursive outbound FK parent closure.
 */
public class SubsetPlanner {

    public SubsetPlan plan(DbModel dbModel, DMLService dml, SubsetConfig config) {
        if (config == null || !config.isActive()) {
            throw Exceptions.badRequest("subset-not-enabled").get();
        }
        if (dml == null || !dml.supportsSubset()) {
            throw Exceptions.badRequest("subset-not-supported-for-datasource").get();
        }
        int maxRows = config.getMaxRowsPerTable() > 0
            ? config.getMaxRowsPerTable()
            : SubsetConfig.DEFAULT_MAX_ROWS_PER_TABLE;

        Map<String, Set<String>> keysByTable = new LinkedHashMap<>();
        Map<String, SubsetTableProvenance> provenance = new LinkedHashMap<>();
        Deque<String> worklist = new ArrayDeque<>();

        for (SubsetDriver driver : config.getDrivers()) {
            if (driver == null || StringUtils.isEmptyOrNull(driver.getTable())) {
                throw Exceptions.badRequest("subset-driver-table-required").get();
            }
            DbTable table = resolveTable(dbModel, driver.getTable());
            String qname = table.qualifiedName();
            SqlSubsetSupport.requirePrimaryKey(table);

            String where = driver.isEntireTable() ? null : driver.getWhere();
            if (!driver.isEntireTable()) {
                if (StringUtils.isEmptyOrNull(where)) {
                    throw Exceptions.badRequest("subset-driver-where-or-entire-table-required")
                        .withExtra("table", driver.getTable())
                        .get();
                }
                SubsetPredicateValidator.validate(where);
            }

            Set<String> seedKeys = dml.selectPrimaryKeys(table, where, driver.getLimit());
            ensureUnderCap(qname, seedKeys.size(), maxRows);
            boolean grew = addKeys(keysByTable, provenance, qname, seedKeys, true, null);
            if (grew) {
                worklist.add(qname);
            }
        }

        while (!worklist.isEmpty()) {
            String childQName = worklist.poll();
            DbTable child = resolveTable(dbModel, childQName);
            Set<String> childKeys = keysByTable.get(childQName);
            if (childKeys == null || childKeys.isEmpty()) {
                continue;
            }
            if (child.getReferences() == null || child.getReferences().isEmpty()) {
                continue;
            }
            for (ReferenceInfo ref : child.getReferences()) {
                // Follow all outbound FKs for parent closure. circularIgnore is for
                // load/DDL ordering only (e.g. EMP_DEPT_FK vs DEPT_MGR_FK cycle) and must
                // not omit required parents from the subset.
                String parentQName = ref.refQualifiedName();
                Optional<DbTable> parentOpt = dbModel.findTable(parentQName);
                if (parentOpt.isEmpty()) {
                    parentOpt = findTableLoose(dbModel, parentQName);
                }
                if (parentOpt.isEmpty()) {
                    throw Exceptions.badRequest("subset-parent-table-not-found")
                        .withExtra("parent", parentQName)
                        .withExtra("child", childQName)
                        .get();
                }
                DbTable parent = parentOpt.get();
                SqlSubsetSupport.requirePrimaryKey(parent);
                List<String> childFkCols = new ArrayList<>(ref.getSrcColumnNames());
                List<String> parentRefCols = new ArrayList<>(ref.getRefColumnNames());
                Set<String> parentKeys = dml.selectParentPrimaryKeys(child, parent, childFkCols, parentRefCols, childKeys);
                boolean grew = addKeys(keysByTable, provenance, parent.qualifiedName(), parentKeys, false, childQName);
                ensureUnderCap(parent.qualifiedName(),
                    keysByTable.get(parent.qualifiedName()).size(), maxRows);
                if (grew) {
                    worklist.add(parent.qualifiedName());
                }
            }
        }

        return SubsetPlan.builder()
            .primaryKeysByTable(keysByTable)
            .provenanceByTable(provenance)
            .build();
    }

    /** @return true if at least one new key was added */
    private static boolean addKeys(Map<String, Set<String>> keysByTable,
            Map<String, SubsetTableProvenance> provenance,
            String table, Set<String> keys, boolean fromDriver, String requiredBy) {
        if (keys == null || keys.isEmpty()) {
            return false;
        }
        Set<String> existing = keysByTable.computeIfAbsent(table, t -> new LinkedHashSet<>());
        long newlyFromDriver = 0;
        long newlyRequired = 0;
        boolean grew = false;
        for (String key : keys) {
            if (existing.add(key)) {
                grew = true;
                if (fromDriver) {
                    newlyFromDriver++;
                } else {
                    newlyRequired++;
                }
            }
        }
        SubsetTableProvenance prov = provenance.computeIfAbsent(table, t -> SubsetTableProvenance.builder()
            .requiredByTables(new LinkedHashSet<>())
            .build());
        prov.setDriverCount(prov.getDriverCount() + newlyFromDriver);
        prov.setRequiredByFkCount(prov.getRequiredByFkCount() + newlyRequired);
        if (requiredBy != null) {
            prov.getRequiredByTables().add(requiredBy);
        }
        return grew;
    }

    private static void ensureUnderCap(String table, long count, int maxRows) {
        if (count > maxRows) {
            throw Exceptions.badRequest("subset-max-rows-exceeded")
                .withExtra("table", table)
                .withExtra("count", count)
                .withExtra("maxRowsPerTable", maxRows)
                .get();
        }
    }

    public static DbTable resolveTable(DbModel dbModel, String userTableName) {
        if (dbModel == null || dbModel.getTables() == null) {
            throw Exceptions.badRequest("subset-db-model-empty").get();
        }
        Optional<DbTable> direct = dbModel.findTable(userTableName);
        if (direct.isPresent()) {
            return direct.get();
        }
        Optional<DbTable> loose = findTableLoose(dbModel, userTableName);
        if (loose.isPresent()) {
            return loose.get();
        }
        throw Exceptions.badRequest("subset-table-not-found")
            .withExtra("table", userTableName)
            .get();
    }

    private static Optional<DbTable> findTableLoose(DbModel dbModel, String name) {
        if (name == null) {
            return Optional.empty();
        }
        for (DbTable table : dbModel.getTables().values()) {
            if (name.equalsIgnoreCase(table.qualifiedName()) || name.equalsIgnoreCase(table.getName())) {
                return Optional.of(table);
            }
        }
        return Optional.empty();
    }
}
