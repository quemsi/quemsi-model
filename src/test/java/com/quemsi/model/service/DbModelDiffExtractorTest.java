package com.quemsi.model.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import java.util.HashMap;
import java.util.Objects;

import org.junit.jupiter.api.Test;

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
import com.quemsi.model.flow.db.sql.diff.DbModelDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbSequenceDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbTableDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbUniqueConstraintDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbViewDiffOp;
import com.quemsi.model.flow.db.sql.diff.DiffOpType;
import com.quemsi.model.util.CommonHelpers;

public class DbModelDiffExtractorTest {
    private final DbModelDiffExtractor extractor = new DbModelDiffExtractor();

    @Test
    public void givenAddedAndRemovedTables_whenExtract_thenReturnTableOperations() {
        DbModel source = new DbModel();
        source.addTable("new_table");

        DbModel target = new DbModel();
        target.addTable("old_table");

        DbModelDiff diff = extractor.extract(source, target);

        assertThat(findOp(diff, DbTableDiffOp.class, DiffOpType.CREATE, "new_table"), notNullValue());
        assertThat(findOp(diff, DbTableDiffOp.class, DiffOpType.DROP, "old_table"), notNullValue());
    }

    @Test
    public void givenColumnAddsRemovesAndChanges_whenExtract_thenReturnColumnOperations() {
        DbModel source = new DbModel();
        DbTable sourceTable = source.addTable("accounts");
        sourceTable.addColumn(column("name", "varchar", true));
        sourceTable.addColumn(column("age", "int", false));
        sourceTable.addColumn(column("status", "varchar", false));

        DbModel target = new DbModel();
        DbTable targetTable = target.addTable("accounts");
        targetTable.addColumn(column("name", "text", true));
        targetTable.addColumn(column("legacy", "varchar", true));
        targetTable.addColumn(column("status", "varchar", true));

        DbModelDiff diff = extractor.extract(source, target);

        assertThat(findOp(diff, DbColumnDiffOp.class, DiffOpType.CREATE, "accounts.age"), notNullValue());
        assertThat(findOp(diff, DbColumnDiffOp.class, DiffOpType.DROP, "accounts.legacy"), notNullValue());
        DbColumnDiffOp change = findOp(diff, DbColumnDiffOp.class, DiffOpType.MODIFY, "accounts.name");
        assertThat(change, notNullValue());
        assertThat(change.getOldColumn().isNullable(), equalTo(true));
        assertThat(change.getNewColumn().getColumnType(), equalTo("varchar"));
        DbColumnDiffOp nullableChange = findOp(diff, DbColumnDiffOp.class, DiffOpType.MODIFY, "accounts.status");
        assertThat(nullableChange, notNullValue());
        assertThat(nullableChange.getOldColumn().isNullable(), equalTo(true));
        assertThat(nullableChange.getNewColumn().isNullable(), equalTo(false));
    }

    @Test
    public void givenAddedForeignKeyColumn_whenExtract_thenReturnColumnAndFkOperations() {
        DbModel source = new DbModel();
        DbTable parent = source.addTable("parent");
        DbColumn parentId = parent.addColumn(column("id", "bigint", false));
        DbTable child = source.addTable("child");
        child.addColumn(column("id", "bigint", false));
        DbColumn childParentId = child.addColumn(column("parent_id", "bigint", false));
        source.getReferenceInfos().add(ReferenceInfo.builder()
            .constraintName("fk_child_parent")
            .srcTableName(child.getName())
            .srcColumnName(childParentId.getName())
            .refTableName(parent.getName())
            .refColumnName(parentId.getName())
            .build());

        DbModel target = new DbModel();
        DbTable targetParent = target.addTable("parent");
        targetParent.addColumn(column("id", "bigint", false));
        DbTable targetChild = target.addTable("child");
        targetChild.addColumn(column("id", "bigint", false));

        DbModelDiff diff = extractor.extract(source, target);

        assertThat(findOp(diff, DbColumnDiffOp.class, DiffOpType.CREATE, "child.parent_id"), notNullValue());
        assertThat(findOp(diff, DbForeignKeyDiffOp.class, DiffOpType.CREATE, "fk_child_parent"), notNullValue());
    }

    @Test
    public void givenCheckConstraintChanges_whenExtract_thenReturnCheckConstraintOperations() {
        DbModel source = new DbModel();
        DbTable table = source.addTable("checks");
        table.addColumn(column("amount", "int", false));
        source.getCheckConstraints().add(CheckConstraint.builder()
            .schema(null)
            .tableName(table.getName())
            .constraintName("ck_amount")
            .condef("CHECK (amount > 0)")
            .build());

        DbModel target = new DbModel();
        DbTable targetTable = target.addTable("checks");
        targetTable.addColumn(column("amount", "int", false));

        DbModelDiff diff = extractor.extract(source, target);

        assertThat(findOp(diff, DbCheckConstraintDiffOp.class, DiffOpType.CREATE, "ck_amount"), notNullValue());

        DbModelDiff reverse = extractor.extract(target, source);
        assertThat(findOp(reverse, DbCheckConstraintDiffOp.class, DiffOpType.DROP, "ck_amount"), notNullValue());
    }

    @Test
    public void givenIndexesAddedAndRemoved_whenExtract_thenReturnIndexOperations() {
        DbModel source = new DbModel();
        DbTable table = source.addTable("idx_table");
        table.addColumn(column("code", "varchar", false));
        addIndex(source, table, "idx_code", "btree", "code");

        DbModel target = new DbModel();
        DbTable targetTable = target.addTable("idx_table");
        targetTable.addColumn(column("code", "varchar", false));

        DbModelDiff diff = extractor.extract(source, target);
        assertThat(findOp(diff, DbIndexDiffOp.class, DiffOpType.CREATE, "idx_code"), notNullValue());

        DbModelDiff reverse = extractor.extract(target, source);
        assertThat(findOp(reverse, DbIndexDiffOp.class, DiffOpType.DROP, "idx_code"), notNullValue());
    }

    @Test
    public void givenSequencesAddedAndRemoved_whenExtract_thenReturnSequenceOperations() {
        DbModel source = new DbModel();
        source.getSequences().add(DbSequence.builder()
            .schema(null)
            .name("seq_demo")
            .incrementBy(1L)
            .build());

        DbModel target = new DbModel();

        DbModelDiff diff = extractor.extract(source, target);
        assertThat(findOp(diff, DbSequenceDiffOp.class, DiffOpType.CREATE, "seq_demo"), notNullValue());

        DbModelDiff reverse = extractor.extract(target, source);
        assertThat(findOp(reverse, DbSequenceDiffOp.class, DiffOpType.DROP, "seq_demo"), notNullValue());
    }

    @Test
    public void givenSequencesDifferOnlyByLastValue_whenExtract_thenNoModify() {
        DbModel source = new DbModel();
        source.getSequences().add(DbSequence.builder()
            .schema("bookings")
            .name("flights_flight_id_seq")
            .startValue(1L)
            .incrementBy(1L)
            .lastValue(1L)
            .build());

        DbModel target = new DbModel();
        target.getSequences().add(DbSequence.builder()
            .schema("bookings")
            .name("flights_flight_id_seq")
            .startValue(1L)
            .incrementBy(1L)
            .lastValue(42L)
            .build());

        DbModelDiff diff = extractor.extract(source, target);
        assertThat(findOp(diff, DbSequenceDiffOp.class, DiffOpType.MODIFY, "bookings.flights_flight_id_seq"), equalTo(null));
    }

    @Test
    public void givenSequencesDifferOnlyByStartValue_whenExtract_thenNoModify() {
        DbModel source = new DbModel();
        source.getSequences().add(DbSequence.builder()
            .schema("bookings")
            .name("flights_flight_id_seq")
            .startValue(1L)
            .incrementBy(1L)
            .build());

        DbModel target = new DbModel();
        target.getSequences().add(DbSequence.builder()
            .schema("bookings")
            .name("flights_flight_id_seq")
            .startValue(100L)
            .incrementBy(1L)
            .build());

        DbModelDiff diff = extractor.extract(source, target);
        assertThat(findOp(diff, DbSequenceDiffOp.class, DiffOpType.MODIFY, "bookings.flights_flight_id_seq"), equalTo(null));
    }

    @Test
    public void givenSequencesDifferByIncrement_whenExtract_thenReturnModify() {
        DbModel source = new DbModel();
        source.getSequences().add(DbSequence.builder()
            .schema("bookings")
            .name("flights_flight_id_seq")
            .incrementBy(2L)
            .lastValue(1L)
            .build());

        DbModel target = new DbModel();
        target.getSequences().add(DbSequence.builder()
            .schema("bookings")
            .name("flights_flight_id_seq")
            .incrementBy(1L)
            .lastValue(99L)
            .build());

        DbModelDiff diff = extractor.extract(source, target);
        assertThat(findOp(diff, DbSequenceDiffOp.class, DiffOpType.MODIFY, "bookings.flights_flight_id_seq"), notNullValue());
    }

    @Test
    public void givenUniqueConstraintAdded_whenExtract_thenReturnUniqueConstraintOperation() {
        DbModel source = new DbModel();
        DbTable table = source.addTable("uniq_table");
        table.addColumn(column("email", "varchar", false));
        source.getContraintInfos().add(ContraintInfo.builder()
            .constraintName("uk_email")
            .tableName(table.getName())
            .columnName("email")
            .build());

        DbModel target = new DbModel();
        DbTable targetTable = target.addTable("uniq_table");
        targetTable.addColumn(column("email", "varchar", false));

        DbModelDiff diff = extractor.extract(source, target);

        assertThat(findOp(diff, DbUniqueConstraintDiffOp.class, DiffOpType.CREATE, "uk_email"), notNullValue());
    }

    @Test
    public void givenViewsAddedRemovedAndCommon_whenExtract_thenReturnViewOperations() {
        DbModel source = new DbModel();
        source.getViews().add(DbView.builder()
            .schema("public")
            .name("v_new")
            .definition("SELECT 1")
            .build());
        source.getViews().add(DbView.builder()
            .schema("public")
            .name("v_shared")
            .definition("SELECT id FROM t")
            .build());
        source.getViews().add(DbView.builder()
            .schema("public")
            .name("v_changed")
            .definition("SELECT id, name FROM t")
            .build());

        DbModel target = new DbModel();
        target.getViews().add(DbView.builder()
            .schema("public")
            .name("v_old")
            .definition("SELECT 2")
            .build());
        target.getViews().add(DbView.builder()
            .schema("public")
            .name("v_shared")
            .definition("SELECT id FROM t;")
            .build());
        target.getViews().add(DbView.builder()
            .schema("public")
            .name("v_changed")
            .definition("SELECT id FROM t")
            .build());

        DbModelDiff diff = extractor.extract(source, target);

        assertThat(findOp(diff, DbViewDiffOp.class, DiffOpType.CREATE, "public.v_new"), notNullValue());
        assertThat(findOp(diff, DbViewDiffOp.class, DiffOpType.DROP, "public.v_old"), notNullValue());
        // Same definition (ignoring trailing semicolon) skips MODIFY
        assertThat(findOp(diff, DbViewDiffOp.class, DiffOpType.MODIFY, "public.v_shared"), equalTo(null));
        // Different definition yields MODIFY
        assertThat(findOp(diff, DbViewDiffOp.class, DiffOpType.MODIFY, "public.v_changed"), notNullValue());
    }

    private DbColumn column(String name, String type, boolean nullable) {
        return DbColumn.builder()
            .name(name)
            .dataType(type)
            .columnType(type)
            .nullable(nullable)
            .build();
    }

    private void addIndex(DbModel model, DbTable table, String indexName, String indexType, String column) {
        IndexInfo index = new IndexInfo(table.getSchema(), table.getName(), indexName, false, indexType);
        index.getColumns().add(column);
        String qualifiedTableName = CommonHelpers.qualifiedName(table.getSchema(), table.getName());
        model.getIndexes().computeIfAbsent(qualifiedTableName, key -> new HashMap<>()).put(indexName, index);
    }

    private <T extends DbModelDiffOp> T findOp(DbModelDiff diff, Class<T> type, DiffOpType opType, String qualifiedName) {
        return diff.getOperations().stream()
            .filter(type::isInstance)
            .filter(op -> op.getOpType() == opType)
            .filter(op -> Objects.equals(op.getQualifiedName(), qualifiedName))
            .map(type::cast)
            .findFirst()
            .orElse(null);
    }
}

