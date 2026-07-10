package com.quemsi.model.flow.db.mongodb;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.annotation.PreDestroy;
import javax.sql.DataSource;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.commons.util.StringUtils;
import com.quemsi.model.dto.DatasourceType;
import com.quemsi.model.flow.db.DDLService;
import com.quemsi.model.flow.db.DMLService;
import com.quemsi.model.flow.db.DataSourceFactory;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.IndexInfo;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.util.CommonHelpers;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Data
public class DatasourceFactoryMongo implements DataSourceFactory {
    private static final int SAMPLE_SIZE = 100;

    private String name;
    private String dbName;
    private Set<String> schemas;
    private String url;
    private String username;
    private String password;
    private boolean readOnly;
    private volatile MongoClient mongoClient;

    @Override
    public DatasourceType type() {
        return DatasourceType.MONGODB;
    }

    public synchronized MongoClient getMongoClient() {
        if (mongoClient == null) {
            String connectionUrl = buildConnectionUrl();
            ConnectionString connectionString = new ConnectionString(connectionUrl);
            resolveDbNameFromConnectionString(connectionString);
            mongoClient = MongoClients.create(MongoClientSettings.builder()
                    .applyConnectionString(connectionString)
                    .build());
        }
        return mongoClient;
    }

    String buildConnectionUrl() {
        if (StringUtils.isEmptyOrNull(url)) {
            throw Exceptions.badRequest("mongodb-url-required").withExtra("name", name).get();
        }
        if (url.contains("@") || StringUtils.isEmptyOrNull(username) || StringUtils.isEmptyOrNull(password)) {
            return url;
        }
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            return url;
        }
        return url.substring(0, schemeEnd + 3)
                + encodeUserInfo(username) + ":" + encodeUserInfo(password) + "@"
                + url.substring(schemeEnd + 3);
    }

    private static String encodeUserInfo(String value) {
        StringBuilder encoded = new StringBuilder(value.length() * 3);
        for (char c : value.toCharArray()) {
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                encoded.append(c);
            } else {
                encoded.append(String.format("%%%02X", (int) c));
            }
        }
        return encoded.toString();
    }

    private void resolveDbNameFromConnectionString(ConnectionString connectionString) {
        if (!StringUtils.isEmptyOrNull(dbName)) {
            return;
        }
        String databaseFromUri = connectionString.getDatabase();
        if (!StringUtils.isEmptyOrNull(databaseFromUri)) {
            dbName = databaseFromUri;
        }
    }

    private String resolvedDbName() {
        if (!StringUtils.isEmptyOrNull(url)) {
            String databaseFromUri = new ConnectionString(url).getDatabase();
            if (!StringUtils.isEmptyOrNull(databaseFromUri)) {
                return databaseFromUri;
            }
        }
        if (!StringUtils.isEmptyOrNull(dbName)) {
            return dbName;
        }
        return null;
    }

    public MongoDatabase getDatabase() {
        String databaseName = resolvedDbName();
        if (StringUtils.isEmptyOrNull(databaseName)) {
            throw Exceptions.badRequest("mongodb-dbname-required").withExtra("name", name).get();
        }
        return getMongoClient().getDatabase(databaseName);
    }

    @Override
    public DataSource getDataSource() {
        throw new UnsupportedOperationException("MongoDB datasources do not expose a JDBC DataSource");
    }

    @Override
    public boolean healthCheck() throws Exception {
        String databaseName = resolvedDbName();
        getMongoClient().getDatabase(StringUtils.isEmptyOrNull(databaseName) ? "admin" : databaseName)
                .runCommand(new Document("ping", 1));
        return true;
    }

    @Override
    public DDLService ddlService() throws SQLException {
        return new DDLServiceMongo(this);
    }

    @Override
    public DMLService dmlService() throws SQLException {
        return new DMLServiceMongo(this);
    }

    @Override
    public DbModel getDbModel() {
        DbModel dbModel = new DbModel();
        dbModel.setSourceType(DatasourceType.MONGODB.name());
        dbModel.setFormat("json");
        Set<String> schemaSet = new HashSet<>();
        schemaSet.add(dbName);
        dbModel.setSchemas(schemaSet);

        MongoDatabase database = getDatabase();
        for (String collectionName : database.listCollectionNames()) {
            DbTable table = dbModel.addTable(collectionName, dbName);
            table.getPkColumnNames().add("_id");
            table.setPkConstraintName("_id_");

            Map<String, Object> options = readCollectionOptions(database, collectionName);
            if (options != null && !options.isEmpty()) {
                table.setCollectionOptions(options);
            }

            inferColumnsFromSample(database.getCollection(collectionName), table);
            readIndexes(database.getCollection(collectionName), dbModel, collectionName);
        }
        return dbModel;
    }

    private Map<String, Object> readCollectionOptions(MongoDatabase database, String collectionName) {
        Bson filter = Filters.eq("name", collectionName);
        Document info = database.listCollections().filter(filter).first();
        if (info == null) {
            return null;
        }
        Object optionsObj = info.get("options");
        if (optionsObj instanceof Document optionsDoc) {
            return MongoTypeMapper.documentToMap(optionsDoc);
        }
        return null;
    }

    private void inferColumnsFromSample(MongoCollection<Document> collection, DbTable table) {
        Map<String, String> fieldTypes = new LinkedHashMap<>();
        fieldTypes.put("_id", "objectId");
        for (Document doc : collection.find().limit(SAMPLE_SIZE)) {
            for (Map.Entry<String, Object> entry : doc.entrySet()) {
                fieldTypes.putIfAbsent(entry.getKey(), MongoTypeMapper.inferBsonTypeName(entry.getValue()));
            }
        }
        int ordinal = 1;
        for (Map.Entry<String, String> field : fieldTypes.entrySet()) {
            table.addColumn(DbColumn.builder()
                    .name(field.getKey())
                    .dataType(field.getValue())
                    .columnType(field.getValue())
                    .ordinalPosition(ordinal++)
                    .nullable(!"_id".equals(field.getKey()))
                    .build());
        }
    }

    private void readIndexes(MongoCollection<Document> collection, DbModel dbModel, String collectionName) {
        String qualifiedTable = CommonHelpers.qualifiedName(dbName, collectionName);
        Map<String, IndexInfo> tableIndexes = dbModel.getIndexes().computeIfAbsent(qualifiedTable, k -> new HashMap<>());
        for (Document indexDoc : collection.listIndexes()) {
            String indexName = indexDoc.getString("name");
            if (indexName == null) {
                continue;
            }
            Document key = indexDoc.get("key", Document.class);
            Document weights = indexDoc.get("weights", Document.class);
            boolean unique = Boolean.TRUE.equals(indexDoc.getBoolean("unique"));
            IndexInfo indexInfo = new IndexInfo(dbName, collectionName, indexName, unique, "BTREE");
            if (weights != null && !weights.isEmpty()) {
                for (String field : weights.keySet()) {
                    indexInfo.getColumns().add(field);
                    indexInfo.getExtraColumns().add("text");
                }
            } else if (key != null) {
                for (String field : key.keySet()) {
                    indexInfo.getColumns().add(field);
                    Object direction = key.get(field);
                    if (direction != null) {
                        indexInfo.getExtraColumns().add(String.valueOf(direction));
                    }
                }
            }
            // Preserve additional index options for recreate (partialFilterExpression, sparse, expireAfterSeconds, etc.)
            Map<String, Object> extras = new LinkedHashMap<>();
            for (String opt : new String[]{"sparse", "expireAfterSeconds", "partialFilterExpression", "collation", "weights", "default_language", "language_override", "textIndexVersion", "2dsphereIndexVersion", "bits", "min", "max", "bucketSize"}) {
                if (indexDoc.containsKey(opt)) {
                    extras.put(opt, MongoTypeMapper.toJsonValue(indexDoc.get(opt)));
                }
            }
            if (!extras.isEmpty()) {
                // reuse indexType field to encode serialized extras when non-standard; store in extraColumns after keys
                indexInfo.getExtraColumns().add("$options:" + new Document(extras).toJson());
            }
            tableIndexes.put(indexName, indexInfo);
        }
    }

    @PreDestroy
    public void destroy() {
        if (mongoClient != null) {
            try {
                mongoClient.close();
            } catch (Exception e) {
                log.warn("Error closing MongoClient for {}", name, e);
            }
            mongoClient = null;
        }
    }
}
