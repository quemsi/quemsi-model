package com.quemsi.model.flow.db.mongodb;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.util.LinkedList;
import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.mongodb.client.model.IndexOptions;
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
    public void givenTextIndexWithInternalKeys_whenBuildIndexKeys_thenUsesWeightsFields() {
        IndexInfo index = new IndexInfo("sample_mflix", "movies", "cast_text_fullplot_text_genres_text_title_text", false, "BTREE");
        index.setColumns(new LinkedList<>(List.of("_fts", "_ftsx")));
        index.setExtraColumns(new LinkedList<>(List.of(
                "text",
                "1",
                "$options:{\"weights\": {\"cast\": 1, \"fullplot\": 1, \"genres\": 1, \"title\": 1}, \"default_language\": \"english\", \"language_override\": \"language\", \"textIndexVersion\": 3}")));

        Document keys = ddlService.keysForIndex(index);

        assertThat(keys.getString("cast"), is("text"));
        assertThat(keys.getString("fullplot"), is("text"));
        assertThat(keys.getString("genres"), is("text"));
        assertThat(keys.getString("title"), is("text"));
        assertThat(keys.get("_fts"), is(nullValue()));
    }

    @Test
    public void givenTextIndexOptions_whenApplyExtraIndexOptions_thenSetsTextMetadata() {
        Document options = Document.parse(
                "{\"weights\": {\"cast\": 1, \"title\": 2}, \"default_language\": \"english\", \"language_override\": \"language\", \"textIndexVersion\": 3}");
        IndexOptions indexOptions = new IndexOptions().name("text_idx");

        ddlService.applyExtraIndexOptions(indexOptions, options);

        assertThat(indexOptions.getDefaultLanguage(), is("english"));
        assertThat(indexOptions.getLanguageOverride(), is("language"));
        assertThat(indexOptions.getTextVersion(), is(3));
        assertThat(indexOptions.getWeights().toString(), containsString("cast"));
    }

    @Test
    public void givenGeoIndex_whenBuildIndexKeys_thenUsesStoredFieldDirection() {
        IndexInfo index = new IndexInfo("sample_mflix", "theaters", "geo index", false, "BTREE");
        index.setColumns(new LinkedList<>(List.of("location.geo")));
        index.setExtraColumns(new LinkedList<>(List.of(
                "2dsphere",
                "$options:{\"2dsphereIndexVersion\": 3}")));

        Document keys = ddlService.keysForIndex(index);

        assertThat(keys.getString("location.geo"), is("2dsphere"));
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
