package com.quemsi.model.flow.db.sqlserver;

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
import com.quemsi.model.flow.db.sql.DbView;
import com.quemsi.model.flow.db.sql.diff.DbCheckConstraintDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbColumnDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbForeignKeyDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbIndexDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbModelDiff;
import com.quemsi.model.flow.db.sql.diff.DbSequenceDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbTableDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbUniqueConstraintDiffOp;
import com.quemsi.model.flow.db.sql.diff.DiffOpType;

public class DDLServiceSqlserverTest {
    private DDLServiceSqlserver ddlService;
    private AtomicInteger ordinalSequence = new AtomicInteger(1);
    
    @BeforeEach
    public void setUp() {
        ddlService = new DDLServiceSqlserver();
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
        assertThat(statements.get(0), containsString("CREATE TABLE [test_table]"));
        assertThat(statements.get(0), containsString("[id]"));
        assertThat(statements.get(0), containsString("[name]"));
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
        assertThat(statements.get(0), equalTo("DROP TABLE IF EXISTS [old_table];"));
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
        assertThat(statements.get(0), containsString("ALTER TABLE [test_table] ADD"));
        assertThat(statements.get(0), containsString("[new_col]"));
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
        assertThat(statements.get(0), equalTo("ALTER TABLE [test_table] DROP COLUMN [old_col];"));
    }

    @Test
    public void givenColumnModifyTypeOperation_whenDdlFrom_thenReturnAlterColumnTypeStatement() {
        DbModelDiff diff = new DbModelDiff();
        DbColumn oldColumn = createColumn("col", "varchar", true, 50);
        DbColumn newColumn = createColumn("col", "nvarchar", true, 100);
        
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
        assertThat(statements.get(0), containsString("ALTER TABLE [test_table] ALTER COLUMN [col]"));
        assertThat(statements.get(0), containsString("nvarchar(50)"));
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
        assertThat(statements.get(0), containsString("ALTER TABLE [child] ADD CONSTRAINT [fk_test]"));
        assertThat(statements.get(0), containsString("FOREIGN KEY"));
        assertThat(statements.get(0), containsString("REFERENCES [parent]"));
        assertThat(statements.get(0), containsString("[parent_id]"));
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
        assertThat(statements.get(0), containsString("ALTER TABLE [users] ADD CONSTRAINT [uk_email] UNIQUE"));
        assertThat(statements.get(0), containsString("[email]"));
    }

    @Test
    public void givenCheckConstraintCreateOperation_whenDdlFrom_thenReturnAddCheckConstraintStatement() {
        DbModelDiff diff = new DbModelDiff();
        CheckConstraint constraint = CheckConstraint.builder()
            .schema(null)
            .tableName("products")
            .constraintName("ck_price")
            .condef("(price > 0)")
            .build();
        
        diff.getOperations().add(DbCheckConstraintDiffOp.builder()
            .opType(DiffOpType.CREATE)
            .qualifiedName("ck_price")
            .newConstraint(constraint)
            .build());

        DbModel dbModel = createEmptyDbModel();
        List<String> statements = ddlService.ddlFrom(diff, dbModel);
        
        assertThat(statements, hasSize(1));
        assertThat(statements.get(0), containsString("ALTER TABLE [products] WITH CHECK ADD CONSTRAINT [ck_price]"));
        assertThat(statements.get(0), containsString("CHECK (price > 0)"));
    }

    @Test
    public void givenCheckConstraintWithoutDefinition_whenDdlFrom_thenThrow() {
        DbModelDiff diff = new DbModelDiff();
        CheckConstraint constraint = CheckConstraint.builder()
            .schema("dbo")
            .tableName("authors")
            .constraintName("CK__authors__au_id")
            .condef(null)
            .build();

        diff.getOperations().add(DbCheckConstraintDiffOp.builder()
            .opType(DiffOpType.CREATE)
            .qualifiedName("CK__authors__au_id")
            .newConstraint(constraint)
            .build());

        try {
            ddlService.ddlFrom(diff, createEmptyDbModel());
            throw new AssertionError("expected view-definition-permission-required");
        } catch (com.quemsi.commons.util.BaseRuntimeException e) {
            assertThat(e.getMessageId(), equalTo("view-definition-permission-required"));
            assertThat(e.getExtra().get("requiredPermission"), equalTo("VIEW DEFINITION"));
        }
    }

    @Test
    public void givenCharAndBinaryColumns_whenDdlFrom_thenIncludeLengths() {
        DbModelDiff diff = new DbModelDiff();
        DbTable table = createTable("dbo", "authors");
        table.addColumn(createColumn("phone", "char", false, 12));
        table.addColumn(createColumn("logo", "varbinary", true, 50));
        table.addColumn(createColumn("name", "nchar", true, 20));

        diff.getOperations().add(DbTableDiffOp.builder()
            .opType(DiffOpType.CREATE)
            .qualifiedName("dbo.authors")
            .newTable(table)
            .build());

        List<String> statements = ddlService.ddlFrom(diff, createEmptyDbModel());
        assertThat(statements, hasSize(1));
        assertThat(statements.get(0), containsString("[phone] char(12)"));
        assertThat(statements.get(0), containsString("[logo] varbinary(50)"));
        assertThat(statements.get(0), containsString("[name] nchar(10)"));
    }

    @Test
    public void givenTableWithoutPrimaryKey_whenDdlFrom_thenNoTrailingComma() {
        DbModelDiff diff = new DbModelDiff();
        DbTable table = createTable("dbo", "discounts");
        table.addColumn(createColumn("discounttype", "varchar", false, 40));
        table.addColumn(createColumn("discount", "decimal", false));
        table.findColumn("discount").ifPresent(c -> {
            c.setNumPrecision(4);
            c.setNumScale(2);
        });

        diff.getOperations().add(DbTableDiffOp.builder()
            .opType(DiffOpType.CREATE)
            .qualifiedName("dbo.discounts")
            .newTable(table)
            .build());

        List<String> statements = ddlService.ddlFrom(diff, createEmptyDbModel());
        assertThat(statements, hasSize(1));
        assertThat(statements.get(0), containsString("decimal(4,2)"));
        assertThat(statements.get(0).replaceAll("\\s+", " "), containsString("NOT NULL )"));
    }

    @Test
    public void givenIndexCreateOperation_whenDdlFrom_thenReturnCreateIndexStatement() {
        DbModelDiff diff = new DbModelDiff();
        IndexInfo index = new IndexInfo(null, "users", "idx_email", false, "NONCLUSTERED");
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
        assertThat(statements.get(0), containsString("CREATE NONCLUSTERED INDEX idx_email"));
        assertThat(statements.get(0), containsString("ON [users]"));
        assertThat(statements.get(0), containsString("[email]"));
    }

    @Test
    public void givenSequenceCreateOperation_whenDdlFrom_thenReturnCreateSequenceStatement() {
        DbModelDiff diff = new DbModelDiff();
        DbSequence seq = DbSequence.builder()
            .schema("dbo")
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
            .qualifiedName("dbo.seq_test")
            .newSequence(seq)
            .build());

        DbModel dbModel = createEmptyDbModel();
        List<String> statements = ddlService.ddlFrom(diff, dbModel);
        
        assertThat(statements, hasSize(1));
        assertThat(statements.get(0), containsString("CREATE SEQUENCE dbo.seq_test"));
        assertThat(statements.get(0), containsString("INCREMENT BY 1"));
        assertThat(statements.get(0), containsString("MINVALUE 1"));
        assertThat(statements.get(0), containsString("MAXVALUE 1000"));
        assertThat(statements.get(0), containsString("NO CYCLE"));
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
        assertThat(statements.get(1), containsString("ADD"));
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

    @Test
    public void givenViewModify_whenDdlFrom_thenDropThenCreate() {
        DbModelDiff diff = new DbModelDiff();
        DbView view = DbView.builder()
            .schema("dbo")
            .name("v_demo")
            .definition("SELECT 1 AS n")
            .build();
        diff.getOperations().add(com.quemsi.model.flow.db.sql.diff.DbViewDiffOp.builder()
            .opType(DiffOpType.MODIFY)
            .qualifiedName("dbo.v_demo")
            .oldView(view)
            .newView(view)
            .build());

        List<String> statements = ddlService.ddlFrom(diff, createEmptyDbModel());
        assertThat(statements, hasSize(2));
        assertThat(statements.get(0), containsString("DROP VIEW IF EXISTS dbo.v_demo"));
        assertThat(statements.get(1), containsString("CREATE VIEW dbo.v_demo AS SELECT 1 AS n"));
    }

    @Test
    public void givenCreateViewModuleDefinition_whenStrip_thenReturnSelectBody() {
        String stripped = DatasourceFactorySqlserver.stripCreateViewWrapper(
            "CREATE VIEW [dbo].[v_demo] AS SELECT 1 AS n");
        assertThat(stripped, equalTo("SELECT 1 AS n"));
    }
}

