package com.quemsi.model.flow.db.mongodb;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.InsertManyOptions;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.flow.db.DMLService;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.in.TableData.DataPage;
import com.quemsi.model.flow.in.TableDataPage;
import com.quemsi.model.flow.in.TableDataPage.Request;
import com.quemsi.model.flow.subset.SqlSubsetSupport;
import com.quemsi.model.flow.subset.SubsetBrowseResult;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
@NoArgsConstructor
public class DMLServiceMongo implements DMLService {
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
    public boolean supportsSubset() {
        return true;
    }

    @Override
    public int getTablePageSize(Integer expectedPageSize, DbTable table) {
        return expectedPageSize != null && expectedPageSize > 0 ? expectedPageSize : 1000;
    }

    @Override
    public long countRows(DbTable table) {
        return collection(table).countDocuments();
    }

    @Override
    public long countRows(DbTable table, String whereFragment) {
        try {
            Document filter = MongoSubsetSupport.parseFilter(whereFragment);
            return collection(table).countDocuments(filter);
        } catch (com.quemsi.commons.util.BaseRuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw Exceptions.server("unable-to-count-rows")
                    .withExtra("table", table != null ? table.qualifiedName() : null)
                    .withCause(e)
                    .get();
        }
    }

    @Override
    public Set<String> selectPrimaryKeys(DbTable table, String whereFragment, Integer limit) {
        SqlSubsetSupport.requirePrimaryKey(table);
        try {
            Document filter = MongoSubsetSupport.parseFilter(whereFragment);
            FindIterable<Document> find = collection(table).find(filter)
                    .projection(Projections.include("_id"))
                    .sort(Sorts.ascending("_id"));
            if (limit != null && limit > 0) {
                find = find.limit(limit);
            }
            Set<String> keys = new LinkedHashSet<>();
            for (Document doc : find) {
                keys.add(MongoSubsetSupport.encodeIdKey(doc.get("_id")));
            }
            return keys;
        } catch (com.quemsi.commons.util.BaseRuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw Exceptions.server("unable-to-select-primary-keys")
                    .withExtra("table", table != null ? table.qualifiedName() : null)
                    .withCause(e)
                    .get();
        }
    }

    @Override
    public Set<String> selectParentPrimaryKeys(DbTable child, DbTable parent,
            List<String> childFkColumns, List<String> parentRefColumns, Collection<String> childPkKeys) {
        // Mongo DbModel has no FK references; parent closure is unused until references exist.
        return Set.of();
    }

    @Override
    public TableDataPage getTableDataPage(Request request) {
        try {
            MongoCollection<Document> col = collection(request.getTable());
            List<String> primaryKeys = request.getPrimaryKeys();
            FindIterable<Document> find;
            if (primaryKeys != null && !primaryKeys.isEmpty()) {
                List<Object> ids = new ArrayList<>(primaryKeys.size());
                for (String key : primaryKeys) {
                    Object decoded = MongoSubsetSupport.decodeIdKey(key);
                    if (decoded != null) {
                        ids.add(decoded);
                    }
                }
                Bson filter = ids.isEmpty() ? Filters.eq("_id", null) : Filters.in("_id", ids);
                find = col.find(filter).sort(Sorts.ascending("_id"));
                log.info("subset page for {} keys={}", request.getTable().getName(), primaryKeys.size());
            } else {
                int skip = request.getPageNum() * request.getPageSize();
                find = col.find()
                        .sort(Sorts.ascending("_id"))
                        .skip(skip)
                        .limit(request.getPageSize());
            }

            TableDataPage page = new TableDataPage();
            page.setRequest(request);
            Map<Object, Map<String, Object>> documents = new HashMap<>();
            int count = 0;
            for (Document doc : find) {
                Map<String, Object> jsonDoc = MongoTypeMapper.documentToMap(doc);
                String idKey = MongoSubsetSupport.encodeIdKey(doc.get("_id"));
                documents.put(idKey, jsonDoc);
                count++;
            }
            page.setDocuments(documents);
            page.setHasMorePage(primaryKeys != null && !primaryKeys.isEmpty()
                    ? false
                    : count >= request.getPageSize());
            log.info("{} page for {} created ({} docs)", request.getPageNum(), request.getTable().getName(), count);
            return page;
        } catch (com.quemsi.commons.util.BaseRuntimeException e) {
            throw e;
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
    public SubsetBrowseResult browseRows(DbTable table, String whereFragment, Integer pageSize, Integer page) {
        try {
            int size = SqlSubsetSupport.normalizeBrowseLimit(pageSize);
            int pageNum = SqlSubsetSupport.normalizeBrowsePage(page);
            Document filter = MongoSubsetSupport.parseFilter(whereFragment);
            MongoCollection<Document> col = collection(table);
            long total = col.countDocuments(filter);
            FindIterable<Document> find = col.find(filter)
                    .sort(Sorts.ascending("_id"))
                    .skip(pageNum * size)
                    .limit(size);

            List<Document> docs = new ArrayList<>();
            for (Document doc : find) {
                docs.add(doc);
            }

            List<String> displayCols = buildBrowseColumns(table, docs);
            List<SubsetBrowseResult.BrowseRow> rows = new ArrayList<>(docs.size());
            for (Document doc : docs) {
                String pkKey = MongoSubsetSupport.encodeIdKey(doc.get("_id"));
                List<String> values = new ArrayList<>(displayCols.size());
                for (String colName : displayCols) {
                    values.add(displayCell(doc.get(colName)));
                }
                rows.add(SubsetBrowseResult.BrowseRow.builder().pkKey(pkKey).values(values).build());
            }
            return SubsetBrowseResult.builder()
                    .columns(displayCols)
                    .rows(rows)
                    .totalCount(total)
                    .page(pageNum)
                    .pageSize(size)
                    .build();
        } catch (com.quemsi.commons.util.BaseRuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw Exceptions.server("unable-to-browse-rows")
                    .withExtra("table", table != null ? table.qualifiedName() : null)
                    .withCause(e)
                    .get();
        }
    }

    private static List<String> buildBrowseColumns(DbTable table, List<Document> docs) {
        LinkedHashSet<String> cols = new LinkedHashSet<>();
        cols.add("_id");
        if (table != null) {
            for (DbColumn col : table.orderedColumns()) {
                if (col != null && col.getName() != null && !"_id".equals(col.getName())) {
                    cols.add(col.getName());
                }
                if (cols.size() >= 1 + SqlSubsetSupport.BROWSE_EXTRA_COLUMNS) {
                    break;
                }
            }
        }
        if (cols.size() < 1 + SqlSubsetSupport.BROWSE_EXTRA_COLUMNS) {
            for (Document doc : docs) {
                if (doc == null) {
                    continue;
                }
                for (String key : doc.keySet()) {
                    if (key != null && !key.isBlank()) {
                        cols.add(key);
                    }
                    if (cols.size() >= 1 + SqlSubsetSupport.BROWSE_EXTRA_COLUMNS) {
                        break;
                    }
                }
                if (cols.size() >= 1 + SqlSubsetSupport.BROWSE_EXTRA_COLUMNS) {
                    break;
                }
            }
        }
        return new ArrayList<>(cols);
    }

    private static String displayCell(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof org.bson.types.ObjectId oid) {
            return oid.toHexString();
        }
        if (value instanceof Document doc) {
            return doc.toJson();
        }
        Object jsonish = MongoTypeMapper.toJsonValue(value);
        return jsonish == null ? "" : String.valueOf(jsonish);
    }

    @Override
    public void close() throws Exception {
        // MongoClient owned by factory
    }
}
