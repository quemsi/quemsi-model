package com.quemsi.model.flow.db.mongodb;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import java.util.LinkedList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.IndexInfo;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.db.sql.diff.DbIndexDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbModelDiff;
import com.quemsi.model.flow.db.sql.diff.DbTableDiffOp;
import com.quemsi.model.flow.db.sql.diff.DiffOpType;

public class DDLServiceMongoTest {
    private DDLServiceMongo ddlService;

    @BeforeEach
    public void setUp() {
        ddlService = new DDLServiceMongo();
    }

    @Test
    public void givenEmptyDiff_whenDdlFrom_thenEmpty() {
        assertThat(ddlService.ddlFrom(new DbModelDiff(), new DbModel()), is(empty()));
        assertThat(ddlService.ddlFrom(null, new DbModel()), is(empty()));
    }

    @Test
    public void givenCreateCollection_whenDdlFrom_thenCreateCommand() {
        DbTable table = new DbTable("appdb", "users");
        DbModelDiff diff = new DbModelDiff();
        diff.getOperations().add(DbTableDiffOp.builder()
                .opType(DiffOpType.CREATE)
                .qualifiedName("appdb.users")
                .newTable(table)
                .build());

        List<String> cmds = ddlService.ddlFrom(diff, new DbModel());
        assertThat(cmds, hasSize(1));
        assertThat(cmds.get(0), containsString("\"create\""));
        assertThat(cmds.get(0), containsString("users"));
    }

    @Test
    public void givenCreateIndex_whenDdlFrom_thenCreateIndexesCommand() {
        IndexInfo index = new IndexInfo("appdb", "users", "email_1", true, "BTREE");
        index.setColumns(new LinkedList<>(List.of("email")));
        DbModelDiff diff = new DbModelDiff();
        diff.getOperations().add(DbIndexDiffOp.builder()
                .opType(DiffOpType.CREATE)
                .qualifiedName("appdb.users.email_1")
                .newIndex(index)
                .build());

        List<String> cmds = ddlService.ddlFrom(diff, new DbModel());
        assertThat(cmds, hasSize(1));
        assertThat(cmds.get(0), containsString("createIndexes"));
        assertThat(cmds.get(0), containsString("email_1"));
    }
}
