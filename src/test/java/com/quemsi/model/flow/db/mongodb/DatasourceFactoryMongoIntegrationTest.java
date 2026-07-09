package com.quemsi.model.flow.db.mongodb;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.notNullValue;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.in.TableData;
import com.quemsi.model.flow.in.TableDataPage;
import com.quemsi.model.flow.in.TableDataPage.Request;
import com.quemsi.model.service.TableDataPersister;

@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
public class DatasourceFactoryMongoIntegrationTest {

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }
    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:7.0"));

    private DatasourceFactoryMongo factory;

    @BeforeEach
    public void setUp() {
        factory = new DatasourceFactoryMongo();
        factory.setName("test-mongo");
        factory.setDbName("testdb");
        factory.setUrl(mongo.getConnectionString());
        factory.setUsername(null);
        factory.setPassword(null);

        factory.getDatabase().getCollection("users").drop();
        factory.getDatabase().createCollection("users");
        factory.getDatabase().getCollection("users").insertOne(new Document("name", "alice")
                .append("email", "alice@example.com")
                .append("address", new Document("city", "Istanbul").append("zip", "34000")));
        factory.getDatabase().getCollection("users").createIndex(new Document("email", 1));
    }

    @AfterEach
    public void tearDown() {
        factory.destroy();
    }

    @Test
    public void healthCheckSucceeds() throws Exception {
        assertThat(factory.healthCheck(), equalTo(true));
    }

    @Test
    public void getDbModelReadsCollectionsIndexesAndSampledFields() {
        DbModel model = factory.getDbModel();
        assertThat(model.getSourceType(), equalTo("MONGODB"));
        assertThat(model.getTables(), hasKey("testdb.users"));
        DbTable users = model.getTables().get("testdb.users");
        assertThat(users.getPkColumnNames(), contains("_id"));
        assertThat(users.getColumns(), hasKey("email"));
        assertThat(users.getColumns(), hasKey("address"));
        assertThat(model.getIndexes().get("testdb.users"), notNullValue());
        assertThat(model.getIndexes().get("testdb.users").containsKey("email_1")
                || model.getIndexes().get("testdb.users").size() >= 1, equalTo(true));
    }

    @Test
    public void dmlRoundTripExportAndImport() throws Exception {
        try (DMLServiceMongo dml = new DMLServiceMongo(factory);
             DDLServiceMongo ddl = new DDLServiceMongo(factory)) {
            DbModel model = factory.getDbModel();
            DbTable users = model.getTables().get("testdb.users");

            Request request = Request.builder().pageNum(0).pageSize(100).table(users).build();
            TableDataPage page = dml.getTableDataPage(request);
            assertThat(page.getDocuments().size(), equalTo(1));

            TableDataPersister persister = new TableDataPersister();
            persister.setObjectMapper(new ObjectMapper());
            persister.persist(page);
            TableData tableData = persister.tableDataMap.get("testdb.users");
            assertThat(tableData.isDocumentFormat(), equalTo(true));

            factory.getDatabase().getCollection("users").drop();
            ddl.createTables(model);
            dml.writePageData(users, tableData.getDataPages().get(0));
            assertThat(factory.getDatabase().getCollection("users").countDocuments(), equalTo(1L));
        }
    }

    @Test
    public void clearTablesDeletesDocuments() throws Exception {
        try (DMLServiceMongo dml = new DMLServiceMongo(factory)) {
            assertThat(dml.clearTables("testdb.users"), equalTo(true));
            assertThat(factory.getDatabase().getCollection("users").countDocuments(), equalTo(0L));
        }
    }
}
