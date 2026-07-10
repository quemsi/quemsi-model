package com.quemsi.model.flow.db.mongodb;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bson.Document;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ValidationOptions;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.flow.db.DDLService;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.IndexInfo;
import com.quemsi.model.flow.db.sql.DbModel.ReferenceInfo;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.db.sql.diff.DbIndexDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbModelDiff;
import com.quemsi.model.flow.db.sql.diff.DbModelDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbTableDiffOp;
import com.quemsi.model.flow.db.sql.diff.DiffEntityType;
import com.quemsi.model.flow.db.sql.diff.DiffOpType;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
@NoArgsConstructor
public class DDLServiceMongo implements DDLService {
    private DatasourceFactoryMongo factory;

    private MongoDatabase database() {
        return factory.getDatabase();
    }

    private String collectionName(String qualifiedOrSimpleName) {
        if (qualifiedOrSimpleName == null) {
            return null;
        }
        int idx = qualifiedOrSimpleName.lastIndexOf('.');
        if (idx >= 0 && idx < qualifiedOrSimpleName.length() - 1) {
            return qualifiedOrSimpleName.substring(idx + 1);
        }
        return qualifiedOrSimpleName;
    }

    @Override
    public boolean dropTables(String... tableNames) {
        try {
            MongoDatabase db = database();
            for (String tableName : tableNames) {
                String name = collectionName(tableName);
                db.getCollection(name).drop();
                log.info("dropped collection {}", name);
            }
            return true;
        } catch (Exception e) {
            throw Exceptions.server("failed-to-drop-collections").withCause(e).get();
        }
    }

    @Override
    public boolean dropSequences(String... sequenceNames) {
        return true;
    }

    @Override
    public void disableConstraints(Set<ReferenceInfo> constraints) {
        // no-op for MongoDB
    }

    @Override
    public void enableContraints(Set<ReferenceInfo> constraints) {
        // no-op for MongoDB
    }

    @Override
    public void createTables(DbModel dbModel) {
        MongoDatabase db = database();
        Set<String> existing = new HashSet<>();
        db.listCollectionNames().into(existing);

        for (DbTable table : dbModel.orderedTables()) {
            String name = table.getName();
            if (!existing.contains(name)) {
                CreateCollectionOptions options = buildCreateOptions(table.getCollectionOptions());
                if (options != null) {
                    db.createCollection(name, options);
                } else {
                    db.createCollection(name);
                }
                log.info("created collection {}", name);
            }
            createIndexes(db.getCollection(name), dbModel, table);
        }
    }

    private CreateCollectionOptions buildCreateOptions(Map<String, Object> collectionOptions) {
        if (collectionOptions == null || collectionOptions.isEmpty()) {
            return null;
        }
        CreateCollectionOptions options = new CreateCollectionOptions();
        boolean used = false;
        if (collectionOptions.containsKey("validator") || collectionOptions.containsKey("validationLevel")
                || collectionOptions.containsKey("validationAction")) {
            ValidationOptions validation = new ValidationOptions();
            Object validator = collectionOptions.get("validator");
            if (validator != null) {
                validation.validator(toDocument(validator));
                used = true;
            }
            if (collectionOptions.get("validationLevel") != null) {
                validation.validationLevel(com.mongodb.client.model.ValidationLevel.fromString(
                        String.valueOf(collectionOptions.get("validationLevel"))));
                used = true;
            }
            if (collectionOptions.get("validationAction") != null) {
                validation.validationAction(com.mongodb.client.model.ValidationAction.fromString(
                        String.valueOf(collectionOptions.get("validationAction"))));
                used = true;
            }
            options.validationOptions(validation);
        }
        if (Boolean.TRUE.equals(collectionOptions.get("capped"))) {
            options.capped(true);
            used = true;
            Object size = collectionOptions.get("size");
            if (size instanceof Number number) {
                options.sizeInBytes(number.longValue());
            }
            Object max = collectionOptions.get("max");
            if (max instanceof Number number) {
                options.maxDocuments(number.longValue());
            }
        }
        return used ? options : null;
    }

    @SuppressWarnings("unchecked")
    private Document toDocument(Object value) {
        if (value instanceof Document doc) {
            return doc;
        }
        if (value instanceof Map<?, ?> map) {
            return MongoTypeMapper.mapToDocument((Map<String, Object>) map);
        }
        return Document.parse(String.valueOf(value));
    }

    private void createIndexes(MongoCollection<Document> collection, DbModel dbModel, DbTable table) {
        Map<String, IndexInfo> indexes = dbModel.getIndexes() != null
                ? dbModel.getIndexes().get(table.qualifiedName())
                : null;
        if (indexes == null || indexes.isEmpty()) {
            return;
        }
        Set<String> existing = new HashSet<>();
        for (Document existingIndex : collection.listIndexes()) {
            String n = existingIndex.getString("name");
            if (n != null) {
                existing.add(n);
            }
        }
        for (IndexInfo index : indexes.values()) {
            if ("_id_".equals(index.getIndexName()) || existing.contains(index.getIndexName())) {
                continue;
            }
            LinkedListDirections dirs = parseDirections(index);
            Document optionsExtras = parseIndexOptions(dirs.optionsJson);
            Document keys = buildIndexKeys(index, dirs, optionsExtras);
            if (keys == null || keys.isEmpty()) {
                log.warn("Skipping index {} on {} — could not derive index keys", index.getIndexName(), table.getName());
                continue;
            }
            IndexOptions options = new IndexOptions().name(index.getIndexName()).unique(index.isUnique());
            applyExtraIndexOptions(options, optionsExtras);
            collection.createIndex(keys, options);
            log.info("created index {} on {}", index.getIndexName(), table.getName());
        }
    }

    Document buildIndexKeys(IndexInfo index, LinkedListDirections dirs, Document optionsExtras) {
        Document weights = optionsExtras.get("weights", Document.class);
        if (weights != null && !weights.isEmpty()) {
            Document keys = new Document();
            for (String field : weights.keySet()) {
                keys.append(field, "text");
            }
            return keys;
        }
        if (index.getColumns().contains("_fts")) {
            return null;
        }
        Document keys = new Document();
        int i = 0;
        for (String col : index.getColumns()) {
            Object dir = dirs.directions.size() > i ? dirs.directions.get(i) : 1;
            keys.append(col, coerceDirection(dir));
            i++;
        }
        return keys;
    }

    Document parseIndexOptions(String optionsJson) {
        if (optionsJson == null || optionsJson.isBlank()) {
            return new Document();
        }
        return Document.parse(optionsJson);
    }

    private Object coerceDirection(Object dir) {
        if (dir instanceof Number number) {
            return number.intValue();
        }
        String s = String.valueOf(dir);
        if ("text".equalsIgnoreCase(s) || "2dsphere".equalsIgnoreCase(s) || "hashed".equalsIgnoreCase(s)) {
            return s;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    Document keysForIndex(IndexInfo index) {
        LinkedListDirections dirs = parseDirections(index);
        return buildIndexKeys(index, dirs, parseIndexOptions(dirs.optionsJson));
    }

    static class LinkedListDirections {
        private final List<Object> directions = new ArrayList<>();
        private String optionsJson;
    }

    LinkedListDirections parseDirections(IndexInfo index) {
        LinkedListDirections result = new LinkedListDirections();
        if (index.getExtraColumns() == null) {
            return result;
        }
        for (String extra : index.getExtraColumns()) {
            if (extra != null && extra.startsWith("$options:")) {
                result.optionsJson = extra.substring("$options:".length());
            } else {
                result.directions.add(extra);
            }
        }
        return result;
    }

    void applyExtraIndexOptions(IndexOptions options, Document extras) {
        if (extras == null || extras.isEmpty()) {
            return;
        }
        if (Boolean.TRUE.equals(extras.getBoolean("sparse"))) {
            options.sparse(true);
        }
        if (extras.get("expireAfterSeconds") instanceof Number number) {
            options.expireAfter(number.longValue(), java.util.concurrent.TimeUnit.SECONDS);
        }
        if (extras.get("partialFilterExpression") != null) {
            options.partialFilterExpression(toDocument(extras.get("partialFilterExpression")));
        }
        if (extras.get("weights") != null) {
            options.weights(toDocument(extras.get("weights")));
        }
        if (extras.get("default_language") != null) {
            options.defaultLanguage(String.valueOf(extras.get("default_language")));
        }
        if (extras.get("language_override") != null) {
            options.languageOverride(String.valueOf(extras.get("language_override")));
        }
        if (extras.get("textIndexVersion") instanceof Number number) {
            options.textVersion(number.intValue());
        }
        if (extras.get("2dsphereIndexVersion") instanceof Number number) {
            options.sphereVersion(number.intValue());
        }
        if (extras.get("bits") instanceof Number number) {
            options.bits(number.intValue());
        }
        if (extras.get("min") instanceof Number number) {
            options.min(number.doubleValue());
        }
        if (extras.get("max") instanceof Number number) {
            options.max(number.doubleValue());
        }
    }

    @Override
    public boolean checkSchema(String schema) throws SQLException {
        // MongoDB creates DB lazily; existence means either listed or we can use it
        for (String name : factory.getMongoClient().listDatabaseNames()) {
            if (name.equalsIgnoreCase(schema)) {
                return true;
            }
        }
        return schema != null && schema.equals(factory.getDbName());
    }

    @Override
    public List<String> ddlFrom(DbModelDiff diff, DbModel dbModel) {
        List<String> commands = new ArrayList<>();
        if (diff == null || diff.getOperations() == null) {
            return commands;
        }
        for (DbModelDiffOp op : diff.getOperations()) {
            if (op.getEntityType() == DiffEntityType.TABLE && op instanceof DbTableDiffOp tableOp) {
                if (op.getOpType() == DiffOpType.CREATE && tableOp.getNewTable() != null) {
                    Document cmd = new Document("create", tableOp.getNewTable().getName());
                    Map<String, Object> opts = tableOp.getNewTable().getCollectionOptions();
                    if (opts != null && opts.containsKey("validator")) {
                        cmd.put("validator", opts.get("validator"));
                    }
                    commands.add(cmd.toJson());
                } else if (op.getOpType() == DiffOpType.DROP) {
                    String name = collectionName(tableOp.getQualifiedName());
                    commands.add(new Document("drop", name).toJson());
                }
            } else if (op.getEntityType() == DiffEntityType.INDEX && op instanceof DbIndexDiffOp indexOp) {
                IndexInfo index = indexOp.getOpType() == DiffOpType.CREATE ? indexOp.getNewIndex() : indexOp.getOldIndex();
                if (index == null) {
                    continue;
                }
                String collection = index.getTableName();
                if (op.getOpType() == DiffOpType.CREATE) {
                    Document keys = new Document();
                    for (String col : index.getColumns()) {
                        keys.append(col, 1);
                    }
                    Document cmd = new Document("createIndexes", collection)
                            .append("indexes", List.of(new Document("key", keys)
                                    .append("name", index.getIndexName())
                                    .append("unique", index.isUnique())));
                    commands.add(cmd.toJson());
                } else if (op.getOpType() == DiffOpType.DROP) {
                    commands.add(new Document("dropIndexes", collection)
                            .append("index", index.getIndexName())
                            .toJson());
                }
            }
        }
        return commands;
    }

    @Override
    public void executeSql(String sql) throws SQLException {
        try {
            Document command = Document.parse(sql);
            database().runCommand(command);
        } catch (Exception e) {
            throw new SQLException("Failed to execute MongoDB command: " + sql, e);
        }
    }

    @Override
    public void close() throws Exception {
        // MongoClient owned by factory
    }
}
