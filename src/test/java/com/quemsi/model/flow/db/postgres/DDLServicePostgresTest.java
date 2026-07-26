package com.quemsi.model.flow.db.postgres;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbFunction;
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

public class DDLServicePostgresTest {
    private DDLServicePostgres ddlService;
    private AtomicInteger ordinalSequence = new AtomicInteger(1);
    @BeforeEach
    public void setUp() {
        ddlService = new DDLServicePostgres();
    }

    @Test
    public void givenEmptyDiff_whenDdlFrom_thenReturnEmptyList() {
        DbModelDiff diff = new DbModelDiff();
        DbModel dbModel = createEmptyDbModel();
        List<String> statements = ddlService.ddlFrom(diff, dbModel);
        assertThat(statements, is(empty()));
    }

    @Test
    public void givenNullDiff_whenDdlFrom_thenReturnEmptyList() {
        DbModel dbModel = createEmptyDbModel();
        List<String> statements = ddlService.ddlFrom(null, dbModel);
        assertThat(statements, is(empty()));
    }

    @Test
    public void givenTableCreateOperation_whenDdlFrom_thenReturnCreateTableStatement() {
        DbModelDiff diff = new DbModelDiff();
        DbTable table = createTable("test_table");
        table.addColumn(createColumn("id", "bigint", false));
        table.addColumn(createColumn("name", "varchar", true, 100));
        
        diff.getOperations().add(DbTableDiffOp.builder()
            .opType(DiffOpType.CREATE)
            .qualifiedName("test_table")
            .newTable(table)
            .build());

        DbModel dbModel = createEmptyDbModel();
        List<String> statements = ddlService.ddlFrom(diff, dbModel);
        
        assertThat(statements, hasSize(1));
        assertThat(statements.get(0), containsString("CREATE TABLE IF NOT EXISTS \"test_table\""));
        assertThat(statements.get(0), containsString("\"id\""));
        assertThat(statements.get(0), containsString("\"name\""));
        assertThat(statements.get(0), containsString("varchar(100)"));
    }

    @Test
    public void givenTableDropOperation_whenDdlFrom_thenReturnDropTableStatement() {
        DbModelDiff diff = new DbModelDiff();
        DbTable table = createTable("old_table");
        
        diff.getOperations().add(DbTableDiffOp.builder()
            .opType(DiffOpType.DROP)
            .qualifiedName("old_table")
            .oldTable(table)
            .build());

        DbModel dbModel = createEmptyDbModel();
        List<String> statements = ddlService.ddlFrom(diff, dbModel);
        
        assertThat(statements, hasSize(1));
        assertThat(statements.get(0), equalTo("DROP TABLE IF EXISTS \"old_table\";"));
    }

    @Test
    public void givenColumnCreateOperation_whenDdlFrom_thenReturnAddColumnStatement() {
        DbModelDiff diff = new DbModelDiff();
        DbColumn column = createColumn("new_col", "varchar", false, 50);
        
        diff.getOperations().add(DbColumnDiffOp.builder()
            .opType(DiffOpType.CREATE)
            .qualifiedName("test_table.new_col")
            .tableQualifiedName("test_table")
            .columnName("new_col")
            .newColumn(column)
            .build());

        DbModel dbModel = createDbModelWithTable("test_table");
        List<String> statements = ddlService.ddlFrom(diff, dbModel);
        
        assertThat(statements, hasSize(1));
        assertThat(statements.get(0), containsString("ALTER TABLE \"test_table\" ADD COLUMN"));
        assertThat(statements.get(0), containsString("\"new_col\""));
        assertThat(statements.get(0), containsString("varchar(50)"));
        assertThat(statements.get(0), containsString("NOT NULL"));
    }

    @Test
    public void givenColumnDropOperation_whenDdlFrom_thenReturnDropColumnStatement() {
        DbModelDiff diff = new DbModelDiff();
        
        diff.getOperations().add(DbColumnDiffOp.builder()
            .opType(DiffOpType.DROP)
            .qualifiedName("test_table.old_col")
            .tableQualifiedName("test_table")
            .columnName("old_col")
            .oldColumn(createColumn("old_col", "varchar", true))
            .build());

        DbModel dbModel = createDbModelWithTable("test_table");
        List<String> statements = ddlService.ddlFrom(diff, dbModel);
        
        assertThat(statements, hasSize(1));
        assertThat(statements.get(0), equalTo("ALTER TABLE \"test_table\" DROP COLUMN \"old_col\";"));
    }

    @Test
    public void givenColumnModifyTypeOperation_whenDdlFrom_thenReturnAlterColumnTypeStatement() {
        DbModelDiff diff = new DbModelDiff();
        DbColumn oldColumn = createColumn("col", "varchar", true, 50);
        DbColumn newColumn = createColumn("col", "text", true);
        
        diff.getOperations().add(DbColumnDiffOp.builder()
            .opType(DiffOpType.MODIFY)
            .qualifiedName("test_table.col")
            .tableQualifiedName("test_table")
            .columnName("col")
            .oldColumn(oldColumn)
            .newColumn(newColumn)
            .build());

        DbModel dbModel = createDbModelWithTable("test_table");
        List<String> statements = ddlService.ddlFrom(diff, dbModel);
        
        assertThat(statements, hasSize(1));
        assertThat(statements.get(0), containsString("ALTER TABLE \"test_table\" ALTER COLUMN \"col\" TYPE text"));
    }

    @Test
    public void givenColumnModifyNullableOperation_whenDdlFrom_thenReturnAlterColumnNullableStatement() {
        DbModelDiff diff = new DbModelDiff();
        DbColumn oldColumn = createColumn("col", "varchar", true);
        DbColumn newColumn = createColumn("col", "varchar", false);
        
        diff.getOperations().add(DbColumnDiffOp.builder()
            .opType(DiffOpType.MODIFY)
            .qualifiedName("test_table.col")
            .tableQualifiedName("test_table")
            .columnName("col")
            .oldColumn(oldColumn)
            .newColumn(newColumn)
            .build());

        DbModel dbModel = createDbModelWithTable("test_table");
        List<String> statements = ddlService.ddlFrom(diff, dbModel);
        
        assertThat(statements, hasSize(1));
        assertThat(statements.get(0), containsString("ALTER TABLE \"test_table\" ALTER COLUMN \"col\" SET NOT NULL"));
    }

    @Test
    public void givenColumnModifyDefaultOperation_whenDdlFrom_thenReturnAlterColumnDefaultStatement() {
        DbModelDiff diff = new DbModelDiff();
        DbColumn oldColumn = createColumn("col", "varchar", true);
        DbColumn newColumn = createColumn("col", "varchar", true);
        newColumn.setColumnDefault("'default_value'");
        
        diff.getOperations().add(DbColumnDiffOp.builder()
            .opType(DiffOpType.MODIFY)
            .qualifiedName("test_table.col")
            .tableQualifiedName("test_table")
            .columnName("col")
            .oldColumn(oldColumn)
            .newColumn(newColumn)
            .build());

        DbModel dbModel = createDbModelWithTable("test_table");
        List<String> statements = ddlService.ddlFrom(diff, dbModel);
        
        assertThat(statements, hasSize(1));
        assertThat(statements.get(0), containsString("ALTER TABLE \"test_table\" ALTER COLUMN \"col\""));
    }

    @Test
    public void givenForeignKeyCreateOperation_whenDdlFrom_thenReturnAddForeignKeyStatement() {
        DbModelDiff diff = new DbModelDiff();
        ReferenceInfo ref = ReferenceInfo.builder()
            .constraintName("fk_test")
            .srcSchema(null)
            .srcTableName("child")
            .srcColumnName("parent_id")
            .refSchema(null)
            .refTableName("parent")
            .refColumnName("id")
            .build();
        
        diff.getOperations().add(DbForeignKeyDiffOp.builder()
            .opType(DiffOpType.CREATE)
            .qualifiedName("fk_test")
            .newReference(ref)
            .build());

        DbModel dbModel = createEmptyDbModel();
        List<String> statements = ddlService.ddlFrom(diff, dbModel);
        
        assertThat(statements, hasSize(1));
        assertThat(statements.get(0), containsString("ALTER TABLE \"child\" ADD CONSTRAINT \"fk_test\""));
        assertThat(statements.get(0), containsString("FOREIGN KEY"));
        assertThat(statements.get(0), containsString("REFERENCES \"parent\""));
    }

    @Test
    public void givenForeignKeyDropOperation_whenDdlFrom_thenReturnDropForeignKeyStatement() {
        DbModelDiff diff = new DbModelDiff();
        ReferenceInfo ref = ReferenceInfo.builder()
            .constraintName("fk_test")
            .srcSchema(null)
            .srcTableName("child")
            .srcColumnName("parent_id")
            .refSchema(null)
            .refTableName("parent")
            .refColumnName("id")
            .build();
        
        diff.getOperations().add(DbForeignKeyDiffOp.builder()
            .opType(DiffOpType.DROP)
            .qualifiedName("fk_test")
            .oldReference(ref)
            .build());

        DbModel dbModel = createEmptyDbModel();
        List<String> statements = ddlService.ddlFrom(diff, dbModel);
        
        assertThat(statements, hasSize(1));
        assertThat(statements.get(0), equalTo("ALTER TABLE \"child\" DROP CONSTRAINT \"fk_test\";"));
    }

    @Test
    public void givenUniqueConstraintCreateOperation_whenDdlFrom_thenReturnAddUniqueConstraintStatement() {
        DbModelDiff diff = new DbModelDiff();
        ContraintInfo constraint = ContraintInfo.builder()
            .constraintName("uk_email")
            .schema(null)
            .tableName("users")
            .columnName("email")
            .build();
        
        diff.getOperations().add(DbUniqueConstraintDiffOp.builder()
            .opType(DiffOpType.CREATE)
            .qualifiedName("uk_email")
            .newConstraint(constraint)
            .build());

        DbModel dbModel = createEmptyDbModel();
        List<String> statements = ddlService.ddlFrom(diff, dbModel);
        
        assertThat(statements, hasSize(1));
        assertThat(statements.get(0), containsString("ALTER TABLE ONLY \"users\" ADD CONSTRAINT \"uk_email\" UNIQUE"));
        assertThat(statements.get(0), containsString("\"email\""));
    }

    @Test
    public void givenUniqueConstraintDropOperation_whenDdlFrom_thenReturnDropUniqueConstraintStatement() {
        DbModelDiff diff = new DbModelDiff();
        ContraintInfo constraint = ContraintInfo.builder()
            .constraintName("uk_email")
            .schema(null)
            .tableName("users")
            .columnName("email")
            .build();
        
        diff.getOperations().add(DbUniqueConstraintDiffOp.builder()
            .opType(DiffOpType.DROP)
            .qualifiedName("uk_email")
            .oldConstraint(constraint)
            .build());

        DbModel dbModel = createEmptyDbModel();
        List<String> statements = ddlService.ddlFrom(diff, dbModel);
        
        assertThat(statements, hasSize(1));
        assertThat(statements.get(0), equalTo("ALTER TABLE ONLY \"users\" DROP CONSTRAINT \"uk_email\";"));
    }

    @Test
    public void givenCheckConstraintCreateOperation_whenDdlFrom_thenReturnAddCheckConstraintStatement() {
        DbModelDiff diff = new DbModelDiff();
        CheckConstraint constraint = CheckConstraint.builder()
            .schema(null)
            .tableName("products")
            .constraintName("ck_price")
            .condef("CHECK (price > 0)")
            .build();
        
        diff.getOperations().add(DbCheckConstraintDiffOp.builder()
            .opType(DiffOpType.CREATE)
            .qualifiedName("ck_price")
            .newConstraint(constraint)
            .build());

        DbModel dbModel = createEmptyDbModel();
        List<String> statements = ddlService.ddlFrom(diff, dbModel);
        
        assertThat(statements, hasSize(1));
        assertThat(statements.get(0), containsString("ALTER TABLE \"products\" ADD CONSTRAINT \"ck_price\" CHECK (price > 0);"));
    }

    @Test
    public void givenCheckConstraintDropOperation_whenDdlFrom_thenReturnDropCheckConstraintStatement() {
        DbModelDiff diff = new DbModelDiff();
        CheckConstraint constraint = CheckConstraint.builder()
            .schema(null)
            .tableName("products")
            .constraintName("ck_price")
            .condef("CHECK (price > 0)")
            .build();
        
        diff.getOperations().add(DbCheckConstraintDiffOp.builder()
            .opType(DiffOpType.DROP)
            .qualifiedName("ck_price")
            .oldConstraint(constraint)
            .build());

        DbModel dbModel = createEmptyDbModel();
        List<String> statements = ddlService.ddlFrom(diff, dbModel);
        
        assertThat(statements, hasSize(1));
        assertThat(statements.get(0), equalTo("ALTER TABLE \"products\" DROP CONSTRAINT \"ck_price\";"));
    }

    @Test
    public void givenIndexCreateOperation_whenDdlFrom_thenReturnCreateIndexStatement() {
        DbModelDiff diff = new DbModelDiff();
        IndexInfo index = new IndexInfo(null, "users", "idx_email", false, "btree");
        index.getColumns().add("email");
        
        diff.getOperations().add(DbIndexDiffOp.builder()
            .opType(DiffOpType.CREATE)
            .qualifiedName("idx_email")
            .tableQualifiedName("users")
            .newIndex(index)
            .build());

        DbModel dbModel = createEmptyDbModel();
        List<String> statements = ddlService.ddlFrom(diff, dbModel);
        
        assertThat(statements, hasSize(1));
        assertThat(statements.get(0), containsString("CREATE INDEX IF NOT EXISTS \"idx_email\""));
        assertThat(statements.get(0), containsString("ON \"users\""));
        assertThat(statements.get(0), containsString("USING btree"));
        assertThat(statements.get(0), containsString("\"email\""));
    }

    @Test
    public void givenUniqueIndexCreateOperation_whenDdlFrom_thenReturnCreateUniqueIndexStatement() {
        DbModelDiff diff = new DbModelDiff();
        IndexInfo index = new IndexInfo(null, "users", "idx_email", true, "btree");
        index.getColumns().add("email");
        
        diff.getOperations().add(DbIndexDiffOp.builder()
            .opType(DiffOpType.CREATE)
            .qualifiedName("idx_email")
            .tableQualifiedName("users")
            .newIndex(index)
            .build());

        DbModel dbModel = createEmptyDbModel();
        List<String> statements = ddlService.ddlFrom(diff, dbModel);
        
        assertThat(statements, hasSize(1));
        assertThat(statements.get(0), containsString("CREATE UNIQUE INDEX IF NOT EXISTS \"idx_email\""));
    }

    @Test
    public void givenIndexDropOperation_whenDdlFrom_thenReturnDropIndexStatement() {
        DbModelDiff diff = new DbModelDiff();
        IndexInfo index = new IndexInfo(null, "users", "idx_email", false, "btree");
        index.getColumns().add("email");
        
        diff.getOperations().add(DbIndexDiffOp.builder()
            .opType(DiffOpType.DROP)
            .qualifiedName("idx_email")
            .tableQualifiedName("users")
            .oldIndex(index)
            .build());

        DbModel dbModel = createEmptyDbModel();
        List<String> statements = ddlService.ddlFrom(diff, dbModel);
        
        assertThat(statements, hasSize(1));
        assertThat(statements.get(0), equalTo("DROP INDEX \"idx_email\";"));
    }

    @Test
    public void givenSequenceCreateOperation_whenDdlFrom_thenReturnCreateSequenceStatement() {
        DbModelDiff diff = new DbModelDiff();
        DbSequence seq = DbSequence.builder()
            .schema(null)
            .name("seq_test")
            .incrementBy(1L)
            .minValue(1L)
            .maxValue(1000L)
            .startValue(1L)
            .cycle(false)
            .cacheSize(1L)
            .build();
        
        diff.getOperations().add(DbSequenceDiffOp.builder()
            .opType(DiffOpType.CREATE)
            .qualifiedName("seq_test")
            .newSequence(seq)
            .build());

        DbModel dbModel = createEmptyDbModel();
        List<String> statements = ddlService.ddlFrom(diff, dbModel);
        
        assertThat(statements, hasSize(1));
        assertThat(statements.get(0), containsString("CREATE SEQUENCE IF NOT EXISTS \"seq_test\""));
        assertThat(statements.get(0), containsString("INCREMENT BY 1"));
        assertThat(statements.get(0), containsString("MINVALUE 1"));
        assertThat(statements.get(0), containsString("MAXVALUE 1000"));
        assertThat(statements.get(0), containsString("START WITH 1"));
        assertThat(statements.get(0), containsString("CACHE 1"));
        assertThat(statements.get(0), containsString("NO CYCLE"));
    }

    @Test
    public void givenSequenceDropOperation_whenDdlFrom_thenReturnDropSequenceStatement() {
        DbModelDiff diff = new DbModelDiff();
        DbSequence seq = DbSequence.builder()
            .schema(null)
            .name("seq_test")
            .build();
        
        diff.getOperations().add(DbSequenceDiffOp.builder()
            .opType(DiffOpType.DROP)
            .qualifiedName("seq_test")
            .oldSequence(seq)
            .build());

        DbModel dbModel = createEmptyDbModel();
        List<String> statements = ddlService.ddlFrom(diff, dbModel);
        
        assertThat(statements, hasSize(1));
        assertThat(statements.get(0), equalTo("DROP SEQUENCE IF EXISTS \"seq_test\";"));
    }

    @Test
    public void givenSequenceModifyOperation_whenDdlFrom_thenReturnAlterSequenceStatement() {
        DbModelDiff diff = new DbModelDiff();
        DbSequence oldSeq = DbSequence.builder()
            .schema(null)
            .name("seq_test")
            .incrementBy(1L)
            .minValue(1L)
            .maxValue(1000L)
            .build();
        
        DbSequence newSeq = DbSequence.builder()
            .schema(null)
            .name("seq_test")
            .incrementBy(2L)
            .minValue(10L)
            .maxValue(2000L)
            .build();
        
        diff.getOperations().add(DbSequenceDiffOp.builder()
            .opType(DiffOpType.MODIFY)
            .qualifiedName("seq_test")
            .oldSequence(oldSeq)
            .newSequence(newSeq)
            .build());

        DbModel dbModel = createEmptyDbModel();
        List<String> statements = ddlService.ddlFrom(diff, dbModel);
        
        assertThat(statements, hasSize(1));
        assertThat(statements.get(0), containsString("ALTER SEQUENCE \"seq_test\""));
        assertThat(statements.get(0), containsString("INCREMENT BY 2"));
        assertThat(statements.get(0), containsString("MINVALUE 10"));
        assertThat(statements.get(0), containsString("MAXVALUE 2000"));
    }

    @Test
    public void givenSequenceModifyOnlyLastValue_whenDdlFrom_thenNoRestartStatement() {
        DbModelDiff diff = new DbModelDiff();
        DbSequence oldSeq = DbSequence.builder()
            .schema("bookings")
            .name("flights_flight_id_seq")
            .startValue(1L)
            .incrementBy(1L)
            .lastValue(42L)
            .build();

        DbSequence newSeq = DbSequence.builder()
            .schema("bookings")
            .name("flights_flight_id_seq")
            .startValue(1L)
            .incrementBy(1L)
            .lastValue(1L)
            .build();

        diff.getOperations().add(DbSequenceDiffOp.builder()
            .opType(DiffOpType.MODIFY)
            .qualifiedName("bookings.flights_flight_id_seq")
            .oldSequence(oldSeq)
            .newSequence(newSeq)
            .build());

        DbModel dbModel = createEmptyDbModel();
        List<String> statements = ddlService.ddlFrom(diff, dbModel);

        assertThat(statements, hasSize(0));
    }

    @Test
    public void givenSequenceModifyOnlyStartValue_whenDdlFrom_thenNoAlterStatement() {
        DbModelDiff diff = new DbModelDiff();
        DbSequence oldSeq = DbSequence.builder()
            .schema(null)
            .name("seq_test")
            .startValue(1L)
            .build();

        DbSequence newSeq = DbSequence.builder()
            .schema(null)
            .name("seq_test")
            .startValue(100L)
            .build();

        diff.getOperations().add(DbSequenceDiffOp.builder()
            .opType(DiffOpType.MODIFY)
            .qualifiedName("seq_test")
            .oldSequence(oldSeq)
            .newSequence(newSeq)
            .build());

        DbModel dbModel = createEmptyDbModel();
        List<String> statements = ddlService.ddlFrom(diff, dbModel);

        assertThat(statements, hasSize(0));
    }

    @Test
    public void givenMultipleOperations_whenDdlFrom_thenReturnAllStatements() {
        DbModelDiff diff = new DbModelDiff();
        
        // Add table drop
        DbTable table = createTable("old_table");
        diff.getOperations().add(DbTableDiffOp.builder()
            .opType(DiffOpType.DROP)
            .qualifiedName("old_table")
            .oldTable(table)
            .build());
        
        // Add column create
        DbColumn column = createColumn("new_col", "varchar", true);
        diff.getOperations().add(DbColumnDiffOp.builder()
            .opType(DiffOpType.CREATE)
            .qualifiedName("test_table.new_col")
            .tableQualifiedName("test_table")
            .columnName("new_col")
            .newColumn(column)
            .build());
        
        // Add index create
        IndexInfo index = new IndexInfo(null, "test_table", "idx_new_col", false, "btree");
        index.getColumns().add("new_col");
        diff.getOperations().add(DbIndexDiffOp.builder()
            .opType(DiffOpType.CREATE)
            .qualifiedName("idx_new_col")
            .tableQualifiedName("test_table")
            .newIndex(index)
            .build());

        DbModel dbModel = createDbModelWithTable("test_table");
        List<String> statements = ddlService.ddlFrom(diff, dbModel);
        
        assertThat(statements, hasSize(3));
        assertThat(statements.get(0), containsString("DROP TABLE"));
        assertThat(statements.get(1), containsString("ADD COLUMN"));
        assertThat(statements.get(2), containsString("CREATE INDEX"));
    }

    @Test
    public void givenTableWithPrimaryKey_whenDdlFrom_thenReturnCreateTableWithPrimaryKey() {
        DbModelDiff diff = new DbModelDiff();
        DbTable table = createTable("test_table");
        DbColumn idColumn = createColumn("id", "bigint", false);
        table.addColumn(idColumn);
        table.getPkColumnNames().add("id");
        table.setPkConstraintName("pk_test_table");
        
        diff.getOperations().add(DbTableDiffOp.builder()
            .opType(DiffOpType.CREATE)
            .qualifiedName("test_table")
            .newTable(table)
            .build());

        DbModel dbModel = createEmptyDbModel();
        List<String> statements = ddlService.ddlFrom(diff, dbModel);
        
        assertThat(statements, hasSize(1));
        assertThat(statements.get(0), containsString("CONSTRAINT \"pk_test_table\" PRIMARY KEY"));
        assertThat(statements.get(0), containsString("\"id\""));
    }

    @Test
    public void givenForeignKeyModifyOperation_whenDdlFrom_thenReturnDropAndCreateStatements() {
        DbModelDiff diff = new DbModelDiff();
        ReferenceInfo oldRef = ReferenceInfo.builder()
            .constraintName("fk_test")
            .srcSchema(null)
            .srcTableName("child")
            .srcColumnName("parent_id")
            .refSchema(null)
            .refTableName("parent")
            .refColumnName("id")
            .build();
        
        ReferenceInfo newRef = ReferenceInfo.builder()
            .constraintName("fk_test")
            .srcSchema(null)
            .srcTableName("child")
            .srcColumnName("parent_id")
            .refSchema(null)
            .refTableName("new_parent")
            .refColumnName("id")
            .build();
        
        diff.getOperations().add(DbForeignKeyDiffOp.builder()
            .opType(DiffOpType.MODIFY)
            .qualifiedName("fk_test")
            .oldReference(oldRef)
            .newReference(newRef)
            .build());

        DbModel dbModel = createEmptyDbModel();
        List<String> statements = ddlService.ddlFrom(diff, dbModel);
        
        assertThat(statements, hasSize(2));
        assertThat(statements.get(0), containsString("DROP CONSTRAINT"));
        assertThat(statements.get(1), containsString("ADD CONSTRAINT"));
        assertThat(statements.get(1), containsString("REFERENCES \"new_parent\""));
    }

    @Test
    public void givenSchemaQualifiedNames_whenDdlFrom_thenReturnStatementsWithSchema() {
        DbModelDiff diff = new DbModelDiff();
        DbTable table = createTable("public", "test_table");
        table.addColumn(createColumn("id", "bigint", false));
        
        diff.getOperations().add(DbTableDiffOp.builder()
            .opType(DiffOpType.CREATE)
            .qualifiedName("public.test_table")
            .newTable(table)
            .build());

        DbModel dbModel = createEmptyDbModel();
        List<String> statements = ddlService.ddlFrom(diff, dbModel);
        
        assertThat(statements, hasSize(1));
        assertThat(statements.get(0), containsString("\"public\".\"test_table\""));
    }

    @Test
    public void givenViewModify_whenDdlFrom_thenDropBeforeCreateAndBeforeTableDrop() {
        DbModelDiff diff = new DbModelDiff();
        DbView view = DbView.builder()
            .schema("public")
            .name("v_aircrafts")
            .definition("SELECT id FROM aircrafts_data")
            .build();
        diff.getOperations().add(DbViewDiffOp.builder()
            .opType(DiffOpType.MODIFY)
            .qualifiedName("public.v_aircrafts")
            .oldView(view)
            .newView(view)
            .build());
        diff.getOperations().add(DbTableDiffOp.builder()
            .opType(DiffOpType.DROP)
            .qualifiedName("public.aircrafts_data")
            .oldTable(createTable("public", "aircrafts_data"))
            .build());
        diff.getOperations().add(DbViewDiffOp.builder()
            .opType(DiffOpType.CREATE)
            .qualifiedName("public.v_new")
            .newView(DbView.builder().schema("public").name("v_new").definition("SELECT 1").build())
            .build());

        List<String> statements = ddlService.ddlFrom(diff, createEmptyDbModel());

        assertThat(statements, hasSize(4));
        assertThat(statements.get(0), containsString("DROP VIEW IF EXISTS \"public\".\"v_aircrafts\""));
        assertThat(statements.get(1), containsString("DROP TABLE IF EXISTS \"public\".\"aircrafts_data\""));
        assertThat(statements.get(2), containsString("CREATE VIEW \"public\".\"v_aircrafts\" AS"));
        assertThat(statements.get(3), containsString("CREATE VIEW \"public\".\"v_new\" AS"));
    }

    @Test
    public void givenViewHelpers_whenCreateAndDropSql_thenFormatCorrectly() {
        DbView view = DbView.builder()
            .schema("bookings")
            .name("aircrafts")
            .definition("SELECT 1;")
            .build();
        assertThat(DDLServicePostgres.dropViewSql(view.qualifiedName()),
            equalTo("DROP VIEW IF EXISTS \"bookings\".\"aircrafts\";"));
        assertThat(DDLServicePostgres.createViewSql(view),
            equalTo("CREATE VIEW \"bookings\".\"aircrafts\" AS SELECT 1;"));
    }

    @Test
    public void givenFunctionDefinition_whenCreateFunctionSql_thenUseOrReplace() {
        DbFunction function = DbFunction.builder()
            .schema("bookings")
            .name("lang")
            .definition("CREATE FUNCTION bookings.lang() RETURNS text LANGUAGE sql AS $$ SELECT 'en'::text $$")
            .build();
        String sql = DDLServicePostgres.createFunctionSql(function);
        assertThat(sql, containsString("CREATE OR REPLACE FUNCTION"));
        assertThat(sql, containsString("bookings.lang()"));
    }

    @Test
    public void givenAggregateDefinition_whenCreateFunctionSql_thenUseOrReplace() {
        String def = "CREATE AGGREGATE public.group_concat(text) (SFUNC = public._group_concat, STYPE = text)";
        DbFunction aggregate = DbFunction.builder()
            .schema("public")
            .name("group_concat")
            .routineType(DbFunction.TYPE_AGGREGATE)
            .definition(def)
            .build();
        assertThat(DDLServicePostgres.createFunctionSql(aggregate),
            equalTo("CREATE OR REPLACE AGGREGATE public.group_concat(text) (SFUNC = public._group_concat, STYPE = text)"));
    }

    // Helper methods
    private DbModel createEmptyDbModel() {
        return new DbModel();
    }

    private DbModel createDbModelWithTable(String tableName) {
        DbModel dbModel = new DbModel();
        DbTable table = createTable(tableName);
        // Add table to model using qualified name
        String qualifiedName = table.qualifiedName();
        dbModel.getTables().put(qualifiedName, table);
        return dbModel;
    }

    private DbTable createTable(String name) {
        return createTable(null, name);
    }

    private DbTable createTable(String schema, String name) {
        return new DbTable(schema, name);
    }

    private DbColumn createColumn(String name, String type, boolean nullable) {
        return createColumn(name, type, nullable, null);
    }

    private DbColumn createColumn(String name, String type, boolean nullable, Integer maxLength) {
        DbColumn column = DbColumn.builder()
            .name(name)
            .dataType(type)
            .columnType(type)
            .nullable(nullable)
            .maxLength(maxLength)
            .ordinalPosition(ordinalSequence.getAndIncrement())
            .build();
        return column;
    }
}

