package com.quemsi.model.flow.subset;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.quemsi.commons.util.BaseRuntimeException;
import com.quemsi.model.flow.db.DMLService;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.ReferenceInfo;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.in.TableData.DataPage;
import com.quemsi.model.flow.in.TableDataPage;

class SubsetPlannerTest {

    private DbModel dbModel;
    private FakeDml dml;

    private static DbColumn col(String name, int ordinal) {
        DbColumn c = new DbColumn();
        c.setName(name);
        c.setOrdinalPosition(ordinal);
        return c;
    }

    @BeforeEach
    void setUp() {
        dbModel = new DbModel();
        DbTable a = dbModel.addTable("A");
        a.getPkColumnNames().add("id");
        a.addColumn(col("id", 1));
        a.addColumn(col("b_id", 2));
        a.addColumn(col("status", 3));
        DbTable b = dbModel.addTable("B");
        b.getPkColumnNames().add("id");
        b.addColumn(col("id", 1));
        b.addColumn(col("code", 2));
        ReferenceInfo aToB = ReferenceInfo.builder()
            .constraintName("fk_a_b")
            .srcTableName("A")
            .srcColumnName("b_id")
            .refTableName("B")
            .refColumnName("id")
            .build();
        dbModel.getReferenceInfos().add(aToB);
        dbModel.build();

        dml = new FakeDml();
        // A rows: 1->BR1, 2->BR2, 3->BR1
        dml.seedKeys.put("where-failed", Set.of("1", "2"));
        dml.seedKeys.put("where-b-filter", Set.of("BR2", "BR9"));
        dml.parentByChild.put("A|B", Map.of(
            "1", Set.of("BR1"),
            "2", Set.of("BR2"),
            "3", Set.of("BR1")
        ));
    }

    @Test
    void parentClosureIncludesReferencedRows() {
        SubsetConfig config = SubsetConfig.builder()
            .enabled(true)
            .drivers(List.of(SubsetDriver.builder().table("A").where("t.status = 'FAILED'").build()))
            .build();
        dml.whereToSeedKey.put("t.status = 'FAILED'", "where-failed");

        SubsetPlan plan = new SubsetPlanner().plan(dbModel, dml, config);

        assertThat(plan.keysFor("A"), containsInAnyOrder("1", "2"));
        assertThat(plan.keysFor("B"), containsInAnyOrder("BR1", "BR2"));
        assertThat(plan.getProvenanceByTable().get("B").getRequiredByTables(), hasItem("A"));
    }

    @Test
    void unionKeepsRequiredParentRowsOutsideDriverFilter() {
        SubsetConfig config = SubsetConfig.builder()
            .enabled(true)
            .drivers(List.of(
                SubsetDriver.builder().table("A").where("t.status = 'FAILED'").build(),
                SubsetDriver.builder().table("B").where("t.code = 'X'").build()
            ))
            .build();
        dml.whereToSeedKey.put("t.status = 'FAILED'", "where-failed");
        dml.whereToSeedKey.put("t.code = 'X'", "where-b-filter");

        SubsetPlan plan = new SubsetPlanner().plan(dbModel, dml, config);

        assertThat(plan.keysFor("B"), containsInAnyOrder("BR1", "BR2", "BR9"));
        assertThat(plan.getProvenanceByTable().get("B").getDriverCount(), equalTo(2L)); // BR2, BR9
        assertThat(plan.getProvenanceByTable().get("B").getRequiredByFkCount(), equalTo(1L)); // BR1
    }

    @Test
    void rejectsInvalidPredicate() {
        SubsetConfig config = SubsetConfig.builder()
            .enabled(true)
            .drivers(List.of(SubsetDriver.builder().table("A").where("1=1; DROP TABLE x").build()))
            .build();
        assertThrows(BaseRuntimeException.class, () -> new SubsetPlanner().plan(dbModel, dml, config));
    }

    @Test
    void acceptsExistsSubquery() {
        SubsetPredicateValidator.validate(
            "t.id IN (SELECT b.id FROM B b WHERE b.active = 1)");
        SubsetPredicateValidator.validate(
            "EXISTS (SELECT 1 FROM B b JOIN C c ON c.b_id = b.id WHERE b.id = t.b_id)");
    }

    static class FakeDml implements DMLService {
        Map<String, String> whereToSeedKey = new HashMap<>();
        Map<String, Set<String>> seedKeys = new HashMap<>();
        /** child|parent → childPk → parentPks */
        Map<String, Map<String, Set<String>>> parentByChild = new HashMap<>();

        @Override
        public boolean supportsSubset() {
            return true;
        }

        @Override
        public Set<String> selectPrimaryKeys(DbTable table, String whereFragment, Integer limit) {
            if (whereFragment == null) {
                return Set.of();
            }
            String key = whereToSeedKey.get(whereFragment.trim());
            Set<String> keys = seedKeys.getOrDefault(key, Set.of());
            if (limit != null && limit > 0 && keys.size() > limit) {
                return new LinkedHashSet<>(keys.stream().limit(limit).toList());
            }
            return new LinkedHashSet<>(keys);
        }

        @Override
        public Set<String> selectParentPrimaryKeys(DbTable child, DbTable parent,
                List<String> childFkColumns, List<String> parentRefColumns, Collection<String> childPkKeys) {
            Map<String, Set<String>> byChildPk = parentByChild.getOrDefault(
                child.getName() + "|" + parent.getName(), Map.of());
            Set<String> result = new LinkedHashSet<>();
            for (String childPk : childPkKeys) {
                result.addAll(byChildPk.getOrDefault(childPk, Set.of()));
            }
            return result;
        }

        @Override public int getTablePageSize(Integer expectedPageSize, DbTable table) { return 1000; }
        @Override public long countRows(DbTable table) { return 0; }
        @Override public TableDataPage getTableDataPage(TableDataPage.Request request) { return null; }
        @Override public int writePageData(DbTable table, DataPage dataPage) { return 0; }
        @Override public boolean clearTables(String... tableNames) { return true; }
        @Override public void updateSequence(String qualifiedSequenceName, Long newValue) {}
        @Override public Long getMaxColumnValue(String tableName, String columnName) { return null; }
        @Override public void close() {}
    }
}
