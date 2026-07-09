package com.quemsi.model.flow.db.mongodb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bson.Document;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.InsertManyOptions;
import com.mongodb.client.model.Sorts;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.flow.db.DMLService;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.in.TableData.DataPage;
import com.quemsi.model.flow.in.TableDataPage;
import com.quemsi.model.flow.in.TableDataPage.Request;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
@NoArgsConstructor
public class DMLServiceMongo implements DMLService {
    private static final int MAX_PAGES = 10;
    private static final int MAX_DOCS_PER_PAGE = 100000;

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

    private MongoCollection<Document> collection(DbTable table) {
        return database().getCollection(table.getName());
    }

    private MongoCollection<Document> collection(String qualifiedOrSimpleName) {
        return database().getCollection(collectionName(qualifiedOrSimpleName));
    }

    @Override
    public int getTablePageSize(Integer expectedPageSize, DbTable table) {
        long total = collection(table).estimatedDocumentCount();
        int calculated = (int) Math.ceil((double) total / MAX_PAGES);
        int expected = expectedPageSize != null ? expectedPageSize : 1000;
        return Math.min(MAX_DOCS_PER_PAGE, Math.max(expected, calculated));
    }

    @Override
    public TableDataPage getTableDataPage(Request request) {
        try {
            MongoCollection<Document> col = collection(request.getTable());
            int skip = request.getPageNum() * request.getPageSize();
            FindIterable<Document> find = col.find()
                    .sort(Sorts.ascending("_id"))
                    .skip(skip)
                    .limit(request.getPageSize());

            TableDataPage page = new TableDataPage();
            page.setRequest(request);
            Map<Object, Map<String, Object>> documents = new HashMap<>();
            int count = 0;
            for (Document doc : find) {
                Map<String, Object> jsonDoc = MongoTypeMapper.documentToMap(doc);
                Object idKey = MongoTypeMapper.idKey(doc.get("_id"));
                documents.put(idKey, jsonDoc);
                count++;
            }
            page.setDocuments(documents);
            page.setHasMorePage(count >= request.getPageSize());
            log.info("{} page for {} created ({} docs)", request.getPageNum(), request.getTable().getName(), count);
            return page;
        } catch (Exception e) {
            throw Exceptions.server("unable-to-read-data").withExtra("request", request).withCause(e).get();
        }
    }

    @Override
    public int writePageData(DbTable table, DataPage dataPage) {
        try {
            MongoCollection<Document> col = collection(table);
            List<Document> docs = new ArrayList<>();
            if (dataPage.getDocuments() != null && !dataPage.getDocuments().isEmpty()) {
                for (Map<String, Object> map : dataPage.getDocuments().values()) {
                    docs.add(MongoTypeMapper.mapToDocument(map));
                }
            } else if (dataPage.getData() != null && !dataPage.getData().isEmpty()) {
                throw Exceptions.badRequest("mongodb-requires-document-format")
                        .withExtra("table", table.getName())
                        .withExtra("pageNum", dataPage.getPageNum())
                        .get();
            }
            if (!docs.isEmpty()) {
                col.insertMany(docs, new InsertManyOptions().ordered(false));
            }
            log.info("for {} page {} inserted {} docs", table.getName(), dataPage.getPageNum(), docs.size());
            return docs.size();
        } catch (Exception e) {
            throw Exceptions.server("unable-to-write-data")
                    .withExtra("table", table.getName())
                    .withExtra("pageNum", dataPage.getPageNum())
                    .withCause(e)
                    .get();
        }
    }

    @Override
    public boolean clearTables(String... tableNames) {
        try {
            for (String tableName : tableNames) {
                collection(tableName).deleteMany(new Document());
            }
            return true;
        } catch (Exception e) {
            throw Exceptions.server("failed-to-clear-tables").withCause(e).get();
        }
    }

    @Override
    public void updateSequence(String qualifiedSequenceName, Long newValue) {
        // MongoDB has no sequences
    }

    @Override
    public Long getMaxColumnValue(String tableName, String columnName) {
        return null;
    }

    @Override
    public void close() throws Exception {
        // MongoClient owned by factory
    }
}
