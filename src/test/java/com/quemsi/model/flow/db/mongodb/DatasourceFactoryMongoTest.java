package com.quemsi.model.flow.db.mongodb;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import org.junit.jupiter.api.Test;

import com.mongodb.ConnectionString;

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

    @Test
    public void injectsCredentialsAndHonorsAuthSourceQueryParam() {
        DatasourceFactoryMongo factory = new DatasourceFactoryMongo();
        factory.setUrl("mongodb://localhost:27017/appdb?authSource=admin");
        factory.setUsername("global_user");
        factory.setPassword("s3cret");

        ConnectionString connectionString = new ConnectionString(factory.buildConnectionUrl());

        assertThat(connectionString.getUsername(), equalTo("global_user"));
        assertThat(new String(connectionString.getPassword()), equalTo("s3cret"));
        assertThat(connectionString.getCredential().getSource(), equalTo("admin"));
        assertThat(connectionString.getDatabase(), equalTo("appdb"));
    }

    @Test
    public void injectsCredentialsUsingDatabaseFromUriAsAuthSourceWhenMissing() {
        DatasourceFactoryMongo factory = new DatasourceFactoryMongo();
        factory.setUrl("mongodb://localhost:27017/got_db");
        factory.setUsername("jon_snow");
        factory.setPassword("ygritte");

        ConnectionString connectionString = new ConnectionString(factory.buildConnectionUrl());

        assertThat(connectionString.getCredential().getSource(), equalTo("got_db"));
    }

    @Test
    public void leavesUrlUnchangedWhenCredentialsAlreadyPresent() {
        DatasourceFactoryMongo factory = new DatasourceFactoryMongo();
        factory.setUrl("mongodb://existing:pass@localhost:27017/appdb?authSource=admin");
        factory.setUsername("ignored");
        factory.setPassword("ignored");

        assertThat(factory.buildConnectionUrl(), equalTo(factory.getUrl()));
    }

    @Test
    public void leavesUrlUnchangedWhenUsernameOrPasswordMissing() {
        DatasourceFactoryMongo factory = new DatasourceFactoryMongo();
        factory.setUrl("mongodb://localhost:27017/appdb?authSource=admin");
        factory.setUsername("global_user");

        assertThat(factory.buildConnectionUrl(), equalTo(factory.getUrl()));
        factory.setUsername(null);
        factory.setPassword("s3cret");
        assertThat(factory.buildConnectionUrl(), equalTo(factory.getUrl()));
    }

    @Test
    public void percentEncodesSpecialCharactersInCredentials() {
        DatasourceFactoryMongo factory = new DatasourceFactoryMongo();
        factory.setUrl("mongodb://localhost:27017/appdb?authSource=admin");
        factory.setUsername("user@host");
        factory.setPassword("p@ss:word");

        assertThat(factory.buildConnectionUrl(),
                equalTo("mongodb://user%40host:p%40ss%3Aword@localhost:27017/appdb?authSource=admin"));

        ConnectionString connectionString = new ConnectionString(factory.buildConnectionUrl());
        assertThat(connectionString.getUsername(), equalTo("user@host"));
        assertThat(new String(connectionString.getPassword()), equalTo("p@ss:word"));
        assertThat(connectionString.getCredential().getSource(), equalTo("admin"));
    }

    @Test
    public void supportsSrvConnectionStrings() {
        DatasourceFactoryMongo factory = new DatasourceFactoryMongo();
        factory.setUrl("mongodb+srv://cluster.example.net/appdb?authSource=admin");
        factory.setUsername("global_user");
        factory.setPassword("s3cret");

        ConnectionString connectionString = new ConnectionString(factory.buildConnectionUrl());

        assertThat(connectionString.isSrvProtocol(), equalTo(true));
        assertThat(connectionString.getUsername(), equalTo("global_user"));
        assertThat(connectionString.getCredential().getSource(), equalTo("admin"));
        assertThat(connectionString.getDatabase(), equalTo("appdb"));
    }

    @Test
    public void buildConnectionUrlReturnsNullCredentialWhenCredentialsNotProvided() {
        DatasourceFactoryMongo factory = new DatasourceFactoryMongo();
        factory.setUrl("mongodb://localhost:27017/appdb?authSource=admin");

        ConnectionString connectionString = new ConnectionString(factory.buildConnectionUrl());

        assertThat(connectionString.getCredential(), nullValue());
    }
}
