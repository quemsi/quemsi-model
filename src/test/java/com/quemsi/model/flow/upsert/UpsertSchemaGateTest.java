package com.quemsi.model.flow.upsert;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.quemsi.commons.util.BaseRuntimeException;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbTable;

class UpsertSchemaGateTest {

    private final UpsertSchemaGate gate = new UpsertSchemaGate();

    @Test
    void extraTargetColumnIsCompatible() {
        DbModel source = model("t", col("id", 1, "int"), col("name", 2, "varchar"));
        source.findTable("t").get().getPkColumnNames().add("id");
        DbModel target = model("t", col("id", 1, "int"), col("name", 2, "varchar"), col("audit", 3, "varchar"));
        target.findTable("t").get().getPkColumnNames().add("id");

        gate.assertCompatible(source, target, List.of("t"));
    }

    @Test
    void missingSourceColumnFails() {
        DbModel source = model("t", col("id", 1, "int"), col("name", 2, "varchar"));
        source.findTable("t").get().getPkColumnNames().add("id");
        DbModel target = model("t", col("id", 1, "int"));
        target.findTable("t").get().getPkColumnNames().add("id");

        BaseRuntimeException ex = assertThrows(BaseRuntimeException.class,
            () -> gate.assertCompatible(source, target, List.of("t")));
        assertThat(ex.getMessageId(), equalTo("upsert-schema-incompatible"));
    }

    @Test
    void missingTargetTableFails() {
        DbModel source = model("t", col("id", 1, "int"));
        DbModel target = new DbModel();

        BaseRuntimeException ex = assertThrows(BaseRuntimeException.class,
            () -> gate.assertCompatible(source, target, List.of("t")));
        assertThat(ex.getMessageId(), equalTo("upsert-schema-incompatible"));
    }

    @Test
    void pkMismatchFails() {
        DbModel source = model("t", col("id", 1, "int"), col("code", 2, "varchar"));
        source.findTable("t").get().getPkColumnNames().add("id");
        DbModel target = model("t", col("id", 1, "int"), col("code", 2, "varchar"));
        target.findTable("t").get().getPkColumnNames().add("code");

        BaseRuntimeException ex = assertThrows(BaseRuntimeException.class,
            () -> gate.assertCompatible(source, target, List.of("t")));
        assertThat(ex.getMessageId(), equalTo("upsert-schema-incompatible"));
    }

    @Test
    void varcharLengthDifferenceIsCompatible() {
        DbModel source = new DbModel();
        DbTable s = source.addTable("t");
        s.addColumn(col("id", 1, "varchar"));
        s.getPkColumnNames().add("id");
        s.column("id").setColumnType("varchar(20)");
        DbModel target = new DbModel();
        DbTable t = target.addTable("t");
        t.addColumn(col("id", 1, "varchar"));
        t.getPkColumnNames().add("id");
        t.column("id").setColumnType("varchar(100)");

        gate.assertCompatible(source, target, List.of("t"));
    }

    private static DbModel model(String tableName, DbColumn... columns) {
        DbModel model = new DbModel();
        DbTable table = model.addTable(tableName);
        for (DbColumn column : columns) {
            table.addColumn(column);
        }
        return model;
    }

    private static DbColumn col(String name, int ordinal, String dataType) {
        return DbColumn.builder()
            .name(name)
            .dataType(dataType)
            .columnType(dataType)
            .ordinalPosition(ordinal)
            .nullable(false)
            .build();
    }
}
