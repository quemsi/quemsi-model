package com.quemsi.model.flow.upsert;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.quemsi.commons.util.BaseRuntimeException;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.ContraintInfo;
import com.quemsi.model.flow.db.sql.DbModel.IndexInfo;
import com.quemsi.model.flow.db.sql.DbTable;

class UpsertMatchKeyResolverTest {

    private final UpsertMatchKeyResolver resolver = new UpsertMatchKeyResolver();

    @Test
    void pkOnlyUsesPrimaryKey() {
        DbModel model = new DbModel();
        DbTable table = table(model, "validation_message", col("message_key", 1, false), col("message_value", 2, true));
        table.getPkColumnNames().add("message_key");

        UpsertMatchKey key = resolver.resolve(model, table);

        assertThat(key.getColumns(), contains("message_key"));
        assertThat(key.isPrimaryKey(), equalTo(true));
        assertThat(key.getSource(), equalTo("PRIMARY KEY"));
    }

    @Test
    void pkPlusOneUniquePrefersUnique() {
        DbModel model = new DbModel();
        DbTable table = table(model, "country", col("id", 1, false), col("code", 2, false), col("name", 3, true));
        table.getPkColumnNames().add("id");
        model.getContraintInfos().add(new ContraintInfo("uk_country_code", null, "country", java.util.List.of("code")));

        UpsertMatchKey key = resolver.resolve(model, table);

        assertThat(key.getColumns(), contains("code"));
        assertThat(key.isPrimaryKey(), equalTo(false));
        assertThat(key.getSource(), equalTo("uk_country_code"));
    }

    @Test
    void noUniqueIdentityFails() {
        DbModel model = new DbModel();
        DbTable table = table(model, "notes", col("body", 1, true));

        BaseRuntimeException ex = assertThrows(BaseRuntimeException.class, () -> resolver.resolve(model, table));
        assertThat(ex.getMessageId(), equalTo("upsert-no-match-key"));
    }

    @Test
    void twoNonPkUniquesFail() {
        DbModel model = new DbModel();
        DbTable table = table(model, "item",
            col("id", 1, false), col("sku", 2, false), col("barcode", 3, false));
        table.getPkColumnNames().add("id");
        model.getContraintInfos().add(new ContraintInfo("uk_sku", null, "item", java.util.List.of("sku")));
        model.getContraintInfos().add(new ContraintInfo("uk_barcode", null, "item", java.util.List.of("barcode")));

        BaseRuntimeException ex = assertThrows(BaseRuntimeException.class, () -> resolver.resolve(model, table));
        assertThat(ex.getMessageId(), equalTo("upsert-ambiguous-match-key"));
    }

    @Test
    void compositeUniqueIsOneIdentity() {
        DbModel model = new DbModel();
        DbTable table = table(model, "locale_msg",
            col("locale", 1, false), col("code", 2, false), col("text", 3, true));
        model.getContraintInfos().add(new ContraintInfo("uk_locale_code", null, "locale_msg",
            java.util.List.of("locale", "code")));

        UpsertMatchKey key = resolver.resolve(model, table);

        assertThat(key.getColumns(), contains("locale", "code"));
        assertThat(key.isPrimaryKey(), equalTo(false));
    }

    @Test
    void primaryIndexIsNotDoubleCounted() {
        DbModel model = new DbModel();
        DbTable table = table(model, "t", col("id", 1, false), col("name", 2, true));
        table.getPkColumnNames().add("id");
        IndexInfo primary = new IndexInfo(null, "t", "PRIMARY", true, "BTREE");
        primary.getColumns().add("id");
        Map<String, IndexInfo> byName = new LinkedHashMap<>();
        byName.put("PRIMARY", primary);
        model.getIndexes().put("t", byName);

        UpsertMatchKey key = resolver.resolve(model, table);

        assertThat(key.getColumns(), contains("id"));
        assertThat(key.isPrimaryKey(), equalTo(true));
    }

    @Test
    void uniqueIndexOnPkColumnsIsDeduped() {
        DbModel model = new DbModel();
        DbTable table = table(model, "t", col("id", 1, false));
        table.getPkColumnNames().add("id");
        IndexInfo uk = new IndexInfo(null, "t", "uk_id", true, "BTREE");
        uk.getColumns().add("id");
        Map<String, IndexInfo> byName = new LinkedHashMap<>();
        byName.put("uk_id", uk);
        model.getIndexes().put("t", byName);

        UpsertMatchKey key = resolver.resolve(model, table);

        assertThat(key.isPrimaryKey(), equalTo(true));
        assertThat(key.getColumns(), contains("id"));
    }

    @Test
    void nullableMatchColumnFails() {
        DbModel model = new DbModel();
        DbTable table = table(model, "country", col("code", 1, true));
        model.getContraintInfos().add(new ContraintInfo("uk_code", null, "country", java.util.List.of("code")));

        BaseRuntimeException ex = assertThrows(BaseRuntimeException.class, () -> resolver.resolve(model, table));
        assertThat(ex.getMessageId(), equalTo("upsert-match-key-nullable"));
    }

    @Test
    void prefixUniqueIndexIsIgnored() {
        DbModel model = new DbModel();
        DbTable table = table(model, "t", col("id", 1, false), col("name", 2, false));
        table.getPkColumnNames().add("id");
        IndexInfo prefix = new IndexInfo(null, "t", "uk_name_prefix", true, "BTREE");
        prefix.getColumns().add("name");
        prefix.setColumnPrefixLengths(new LinkedList<>(java.util.List.of(10)));
        Map<String, IndexInfo> byName = new LinkedHashMap<>();
        byName.put("uk_name_prefix", prefix);
        model.getIndexes().put("t", byName);

        UpsertMatchKey key = resolver.resolve(model, table);

        assertThat(key.isPrimaryKey(), equalTo(true));
        assertThat(key.getColumns(), contains("id"));
    }

    private static DbTable table(DbModel model, String name, DbColumn... columns) {
        DbTable table = model.addTable(name);
        for (DbColumn column : columns) {
            table.addColumn(column);
        }
        return table;
    }

    private static DbColumn col(String name, int ordinal, boolean nullable) {
        return DbColumn.builder()
            .name(name)
            .dataType("varchar")
            .columnType("varchar(50)")
            .ordinalPosition(ordinal)
            .nullable(nullable)
            .build();
    }
}
