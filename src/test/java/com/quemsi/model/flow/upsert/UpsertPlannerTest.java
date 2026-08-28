package com.quemsi.model.flow.upsert;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.quemsi.commons.util.BaseRuntimeException;
import com.quemsi.model.dto.UpsertConfig;
import com.quemsi.model.dto.UpsertConfig.OnExisting;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.ContraintInfo;
import com.quemsi.model.flow.db.sql.DbModel.ReferenceInfo;
import com.quemsi.model.flow.db.sql.DbTable;

class UpsertPlannerTest {

    private final UpsertPlanner planner = new UpsertPlanner();

    @Test
    void insertUpdateAndSkip() {
        DbModel source = messageModel();
        DbModel target = messageModel();
        Map<String, List<Object[]>> rows = new HashMap<>();
        rows.put("validation_message", List.<Object[]>of(
            new Object[] { "new-key", "New" },
            new Object[] { "existing", "Updated" }
        ));
        FakeLookup lookup = new FakeLookup();
        lookup.existing.put("validation_message|message_key", Set.of("existing"));

        UpsertPlan updatePlan = planner.plan(source, target,
            config(OnExisting.UPDATE, "validation_message"),
            rows::get, lookup);
        assertThat(updatePlan.isUpsertable(), equalTo(true));
        UpsertTablePlan table = updatePlan.getTables().get(0);
        assertThat(table.getInserts(), hasSize(1));
        assertThat(table.getInserts().get(0).getKey(), equalTo("new-key"));
        assertThat(table.getUpdates(), hasSize(1));
        assertThat(table.getUpdates().get(0).getKey(), equalTo("existing"));
        assertThat(table.getSkips(), empty());

        UpsertPlan skipPlan = planner.plan(source, target,
            config(OnExisting.SKIP, "validation_message"),
            rows::get, lookup);
        UpsertTablePlan skipped = skipPlan.getTables().get(0);
        assertThat(skipped.getInserts(), hasSize(1));
        assertThat(skipped.getUpdates(), empty());
        assertThat(skipped.getSkips(), hasSize(1));
        assertThat(skipped.getSkips().get(0).getKey(), equalTo("existing"));
    }

    @Test
    void unchangedExistingRowsAreSkipped() {
        DbModel source = messageModel();
        DbModel target = messageModel();
        Map<String, List<Object[]>> rows = new HashMap<>();
        rows.put("validation_message", List.<Object[]>of(
            new Object[] { "new-key", "New" },
            new Object[] { "same", "Same" },
            new Object[] { "changed", "New value" }
        ));
        FakeLookup lookup = new FakeLookup();
        lookup.rows.put("validation_message|message_key", Map.of(
            "same", new Object[] { "same", "Same" },
            "changed", new Object[] { "changed", "Old value" }
        ));

        UpsertPlan plan = planner.plan(source, target,
            config(OnExisting.UPDATE, "validation_message"),
            rows::get, lookup);
        UpsertTablePlan table = plan.getTables().get(0);
        assertThat(table.getInserts(), hasSize(1));
        assertThat(table.getInserts().get(0).getKey(), equalTo("new-key"));
        assertThat(table.getUpdates(), hasSize(1));
        assertThat(table.getUpdates().get(0).getKey(), equalTo("changed"));
        assertThat(table.getSkips(), hasSize(1));
        assertThat(table.getSkips().get(0).getKey(), equalTo("same"));
    }

    @Test
    void numericCanonicalEqualityIsUnchanged() {
        DbModel source = messageModel();
        source.getTables().get("validation_message").column("message_value").setDataType("int");
        DbModel target = messageModel();
        target.getTables().get("validation_message").column("message_value").setDataType("int");
        Map<String, List<Object[]>> rows = new HashMap<>();
        rows.put("validation_message", List.<Object[]>of(new Object[] { "k", 10 }));
        FakeLookup lookup = new FakeLookup();
        lookup.rows.put("validation_message|message_key", Map.of(
            "k", new Object[] { "k", new java.math.BigDecimal("10.00") }
        ));

        UpsertPlan plan = planner.plan(source, target,
            config(OnExisting.UPDATE, "validation_message"),
            rows::get, lookup);
        assertThat(plan.getTables().get(0).getUpdates(), empty());
        assertThat(plan.getTables().get(0).getSkips(), hasSize(1));
    }

    @Test
    void uniqueNotMatchCollisionFails() {
        DbModel source = countryModel();
        DbModel target = countryModel();
        Map<String, List<Object[]>> rows = new HashMap<>();
        rows.put("country", List.<Object[]>of(new Object[] { 1, "TR", "Turkey" }));
        FakeLookup lookup = new FakeLookup();
        lookup.existing.put("country|code", Set.of());
        lookup.uniqueToMatch.put("country|id", Map.of("1", "US"));

        UpsertPlan plan = planner.plan(source, target, config(OnExisting.UPDATE, "country"), rows::get, lookup);

        assertThat(plan.isUpsertable(), equalTo(false));
        assertThat(plan.getFailures(), hasSize(1));
        assertThat(plan.getFailures().get(0).getKey(), equalTo("TR"));
    }

    @Test
    void childFkToRemappedSurrogateParentFails() {
        DbModel source = countryCityModel();
        DbModel target = countryCityModel();
        Map<String, List<Object[]>> rows = new HashMap<>();
        rows.put("country", List.<Object[]>of(new Object[] { 1, "TR", "Turkey" }));
        rows.put("city", List.<Object[]>of(new Object[] { 10, 1, "Ankara" }));
        FakeLookup lookup = new FakeLookup();
        lookup.existing.put("country|code", Set.of());
        lookup.existing.put("city|id", Set.of());
        lookup.uniqueToMatch.put("country|id", Map.of());

        UpsertPlan plan = planner.plan(source, target, config(OnExisting.UPDATE, "country", "city"),
            rows::get, lookup);

        assertThat(plan.isUpsertable(), equalTo(false));
        assertThat(plan.getFailures().stream().anyMatch(f -> f.getReason().contains("not the primary key")),
            equalTo(true));
    }

    @Test
    void maxRowsFails() {
        DbModel source = messageModel();
        DbModel target = messageModel();
        Map<String, List<Object[]>> rows = new HashMap<>();
        rows.put("validation_message", List.<Object[]>of(
            new Object[] { "a", "A" },
            new Object[] { "b", "B" }
        ));
        UpsertConfig cfg = config(OnExisting.UPDATE, "validation_message");
        cfg.setMaxRows(1);

        BaseRuntimeException ex = assertThrows(BaseRuntimeException.class,
            () -> planner.plan(source, target, cfg, rows::get, new FakeLookup()));
        assertThat(ex.getMessageId(), equalTo("upsert-max-rows-exceeded"));
    }

    private static UpsertConfig config(OnExisting onExisting, String... tables) {
        return UpsertConfig.builder()
            .dryRun(true)
            .onExisting(onExisting)
            .tables(List.of(tables))
            .maxRows(100)
            .build();
    }

    private static DbModel messageModel() {
        DbModel model = new DbModel();
        DbTable table = model.addTable("validation_message");
        table.addColumn(col("message_key", 1, "varchar", false));
        table.addColumn(col("message_value", 2, "varchar", true));
        table.getPkColumnNames().add("message_key");
        return model;
    }

    private static DbModel countryModel() {
        DbModel model = new DbModel();
        DbTable table = model.addTable("country");
        table.addColumn(col("id", 1, "int", false));
        table.addColumn(col("code", 2, "varchar", false));
        table.addColumn(col("name", 3, "varchar", true));
        table.getPkColumnNames().add("id");
        model.getContraintInfos().add(new ContraintInfo("uk_code", null, "country", List.of("code")));
        return model;
    }

    private static DbModel countryCityModel() {
        DbModel model = countryModel();
        DbTable city = model.addTable("city");
        city.addColumn(col("id", 1, "int", false));
        city.addColumn(col("country_id", 2, "int", false));
        city.addColumn(col("name", 3, "varchar", true));
        city.getPkColumnNames().add("id");
        model.getReferenceInfos().add(ReferenceInfo.builder()
            .constraintName("fk_city_country")
            .srcTableName("city")
            .srcColumnName("country_id")
            .refTableName("country")
            .refColumnName("id")
            .build());
        model.build();
        return model;
    }

    private static DbColumn col(String name, int ordinal, String type, boolean nullable) {
        return DbColumn.builder()
            .name(name)
            .dataType(type)
            .columnType(type)
            .ordinalPosition(ordinal)
            .nullable(nullable)
            .build();
    }

    private static final class FakeLookup implements UpsertTargetLookup {
        private final Map<String, Set<String>> existing = new HashMap<>();
        private final Map<String, Map<String, Object[]>> rows = new HashMap<>();
        private final Map<String, Map<String, String>> uniqueToMatch = new HashMap<>();

        @Override
        public Set<String> existingKeys(DbTable table, List<String> keyColumns, Collection<String> candidateKeys) {
            return existing.getOrDefault(table.qualifiedName() + "|" + String.join(",", keyColumns), Set.of())
                .stream()
                .filter(candidateKeys::contains)
                .collect(Collectors.toCollection(HashSet::new));
        }

        @Override
        public Map<String, String> uniqueToMatchKey(DbTable table, List<String> uniqueColumns,
                List<String> matchColumns, Collection<String> uniqueKeys) {
            Map<String, String> mapped = uniqueToMatch.getOrDefault(
                table.qualifiedName() + "|" + String.join(",", uniqueColumns), Map.of());
            Map<String, String> result = new HashMap<>();
            for (String key : uniqueKeys) {
                if (mapped.containsKey(key)) {
                    result.put(key, mapped.get(key));
                }
            }
            return result;
        }

        @Override
        public Map<String, Object[]> existingRows(DbTable table, List<String> keyColumns,
                Collection<String> candidateKeys) {
            Map<String, Object[]> stored = rows.getOrDefault(
                table.qualifiedName() + "|" + String.join(",", keyColumns), Map.of());
            Set<String> keys = existing.getOrDefault(
                table.qualifiedName() + "|" + String.join(",", keyColumns), Set.of());
            Map<String, Object[]> result = new HashMap<>();
            for (String candidate : candidateKeys) {
                if (stored.containsKey(candidate)) {
                    result.put(candidate, stored.get(candidate));
                } else if (keys.contains(candidate)) {
                    result.put(candidate, null);
                }
            }
            return result;
        }
    }
}
