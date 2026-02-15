package com.quemsi.model.flow.db.mysql;

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
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.CheckConstraint;
import com.quemsi.model.flow.db.sql.DbModel.ContraintInfo;
import com.quemsi.model.flow.db.sql.DbModel.IndexInfo;
import com.quemsi.model.flow.db.sql.DbModel.ReferenceInfo;
import com.quemsi.model.flow.db.sql.DbSequence;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.db.sql.diff.DbCheckConstraintDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbColumnDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbForeignKeyDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbIndexDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbModelDiff;
import com.quemsi.model.flow.db.sql.diff.DbSequenceDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbTableDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbUniqueConstraintDiffOp;
import com.quemsi.model.flow.db.sql.diff.DiffOpType;

public class DDLServiceMysqlTest {
    private DDLServiceMysql ddlService;
    private AtomicInteger ordinalSequence = new AtomicInteger(1);
    
    @BeforeEach
    public void setUp() {
        ddlService = new DDLServiceMysql();
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
        assertThat(statements.get(0), containsString("CREATE TABLE IF NOT EXISTS test_table"));
        assertThat(statements.get(0), containsString("`id`"));
        assertThat(statements.get(0), containsString("`name`"));
        assertThat(statements.get(0), containsString("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"));
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
        assertThat(statements.get(0), equalTo("DROP TABLE IF EXISTS old_table;"));
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
        assertThat(statements.get(0), containsString("ALTER TABLE test_table ADD COLUMN"));
        assertThat(statements.get(0), containsString("`new_col`"));
        assertThat(statements.get(0), containsString("varchar"));
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
        assertThat(statements.get(0), equalTo("ALTER TABLE test_table DROP COLUMN `old_col`;"));
    }

    @Test
    public void givenColumnModifyOperation_whenDdlFrom_thenReturnModifyColumnStatement() {
        DbModelDiff diff = new DbModelDiff();
        DbColumn oldColumn = createColumn("col", "varchar", true, 50);
        DbColumn newColumn = createColumn("col", "text", false);
        
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
        assertThat(statements.get(0), containsString("ALTER TABLE test_table MODIFY COLUMN `col`"));
        assertThat(statements.get(0), containsString("text"));
        assertThat(statements.get(0), containsString("NOT NULL"));
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
        assertThat(statements.get(0), containsString("ALTER TABLE child ADD CONSTRAINT fk_test"));
        assertThat(statements.get(0), containsString("FOREIGN KEY"));
        assertThat(statements.get(0), containsString("REFERENCES parent"));
        assertThat(statements.get(0), containsString("`parent_id`"));
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
        assertThat(statements.get(0), containsString("ALTER TABLE users ADD CONSTRAINT uk_email UNIQUE"));
        assertThat(statements.get(0), containsString("`email`"));
    }

    @Test
    public void givenCheckConstraintCreateOperation_whenDdlFrom_thenReturnAddCheckConstraintStatement() {
        DbModelDiff diff = new DbModelDiff();
        CheckConstraint constraint = CheckConstraint.builder()
            .schema(null)
            .tableName("products")
            .constraintName("ck_price")
            .condef("price > 0")
            .build();
        
        diff.getOperations().add(DbCheckConstraintDiffOp.builder()
            .opType(DiffOpType.CREATE)
            .qualifiedName("ck_price")
            .newConstraint(constraint)
            .build());

        DbModel dbModel = createEmptyDbModel();
        List<String> statements = ddlService.ddlFrom(diff, dbModel);
        
        assertThat(statements, hasSize(1));
        assertThat(statements.get(0), containsString("ALTER TABLE products ADD CONSTRAINT ck_price"));
        assertThat(statements.get(0), containsString("CHECK"));
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
        assertThat(statements.get(0), containsString("CREATE INDEX idx_email"));
        assertThat(statements.get(0), containsString("ON users"));
        assertThat(statements.get(0), containsString("`email`"));
    }

    @Test
    public void givenSequenceOperation_whenDdlFrom_thenIgnoreSequence() {
        DbModelDiff diff = new DbModelDiff();
        DbSequence seq = DbSequence.builder()
            .schema(null)
            .name("seq_test")
            .incrementBy(1L)
            .build();
        
        diff.getOperations().add(DbSequenceDiffOp.builder()
            .opType(DiffOpType.CREATE)
            .qualifiedName("seq_test")
            .newSequence(seq)
            .build());

        DbModel dbModel = createEmptyDbModel();
        List<String> statements = ddlService.ddlFrom(diff, dbModel);
        
        // MySQL doesn't support sequences, so it should be ignored
        assertThat(statements, is(empty()));
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

        DbModel dbModel = createDbModelWithTable("test_table");
        List<String> statements = ddlService.ddlFrom(diff, dbModel);
        
        assertThat(statements, hasSize(2));
        assertThat(statements.get(0), containsString("DROP TABLE"));
        assertThat(statements.get(1), containsString("ADD COLUMN"));
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

