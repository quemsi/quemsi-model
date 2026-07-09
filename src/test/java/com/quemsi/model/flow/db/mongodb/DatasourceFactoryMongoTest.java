package com.quemsi.model.flow.db.mongodb;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.Test;

public class DatasourceFactoryMongoTest {

    @Test
    public void resolvesDbNameFromConnectionStringWhenFieldMissing() {
        DatasourceFactoryMongo factory = new DatasourceFactoryMongo();
        factory.setUrl("mongodb://localhost:27017/got_db");
        factory.setUsername("jon_snow");
        factory.setPassword("ygritte");

        factory.getMongoClient();

        assertThat(factory.getDbName(), equalTo("got_db"));
    }
}
