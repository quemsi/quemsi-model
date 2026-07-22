package com.quemsi.model.flow.file;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.flow.in.TableData;
import com.quemsi.model.flow.in.TableData.DataPage;
import com.quemsi.model.flow.in.TableDataMeta;
import com.quemsi.model.flow.in.TableDataPage;
import com.quemsi.model.util.CommonConstants;
import com.quemsi.model.util.CommonHelpers;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * Writes backup artifacts to a staging directory as pages arrive.
 * Layout matches the zip archive layout (db-model.json + tables/.../pages/...).
 */
@Slf4j
public class StagingBackupWriter {
    @Setter
    private ObjectMapper objectMapper;

    @Getter
    private final Path stagingRoot;

    private final Map<String, TableStats> statsByTable = new ConcurrentHashMap<>();

    public StagingBackupWriter(Path stagingRoot) {
        this.stagingRoot = stagingRoot;
    }

    public void writeDbModel(String json) {
        try {
            Files.createDirectories(stagingRoot);
            Path target = stagingRoot.resolve(CommonConstants.DB_MODEL_FILE_NAME);
            Files.writeString(target, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw Exceptions.server("unable-to-write-db-model-staging").withCause(e).get();
        }
    }

    public void persist(TableDataPage tableDataPage) {
        String qualifiedName = tableDataPage.getRequest().getTable().qualifiedName();
        int pageNum = tableDataPage.getRequest().getPageNum();
        DataPage page = new DataPage();
        page.setPageNum(pageNum);
        if (tableDataPage.getDocuments() != null) {
            page.setDocuments(tableDataPage.getDocuments());
        } else {
            page.setData(tableDataPage.getTableData());
        }

        TableStats stats = statsByTable.computeIfAbsent(qualifiedName, k -> {
            TableStats s = new TableStats();
            s.pageSize = tableDataPage.getRequest().getPageSize();
            s.dataFormat = tableDataPage.getDocuments() != null
                ? TableData.FORMAT_DOCUMENT
                : TableData.FORMAT_TABULAR;
            return s;
        });
        if (tableDataPage.getDocuments() != null) {
            stats.dataFormat = TableData.FORMAT_DOCUMENT;
        }

        try {
            Path pagePath = stagingRoot.resolve(CommonHelpers.tablePageEntryName(qualifiedName, pageNum));
            Files.createDirectories(pagePath.getParent());
            objectMapper.writeValue(pagePath.toFile(), page);
            stats.totalPages.incrementAndGet();
            stats.totalRecords.addAndGet(page.getSize());
            log.debug("Wrote staging page {} for {} ({} rows)", pageNum, qualifiedName, page.getSize());
        } catch (IOException e) {
            throw Exceptions.server("unable-to-write-page-staging")
                .withExtra("table", qualifiedName)
                .withExtra("pageNum", pageNum)
                .withCause(e)
                .get();
        }
    }

    public void finishTable(String qualifiedName) {
        finishTable(qualifiedName, null);
    }

    /**
     * Writes meta.json. When no pages were persisted (empty table), {@code pageSizeHint}
     * supplies the planned page size so meta is still accurate (totalPages=0).
     */
    public void finishTable(String qualifiedName, Integer pageSizeHint) {
        TableStats stats = statsByTable.get(qualifiedName);
        if (stats == null) {
            stats = new TableStats();
            stats.dataFormat = TableData.FORMAT_TABULAR;
            if (pageSizeHint != null) {
                stats.pageSize = pageSizeHint;
            }
        } else if (pageSizeHint != null && stats.pageSize <= 0) {
            stats.pageSize = pageSizeHint;
        }
        TableDataMeta meta = TableDataMeta.builder()
            .tableName(qualifiedName)
            .pageSize(stats.pageSize)
            .totalPages(stats.totalPages.get())
            .totalRecords(stats.totalRecords.get())
            .dataFormat(stats.dataFormat)
            .build();
        try {
            Path metaPath = stagingRoot.resolve(CommonHelpers.tableMetaEntryName(qualifiedName));
            Files.createDirectories(metaPath.getParent());
            objectMapper.writeValue(metaPath.toFile(), meta);
        } catch (IOException e) {
            throw Exceptions.server("unable-to-write-table-meta-staging")
                .withExtra("table", qualifiedName)
                .withCause(e)
                .get();
        }
    }

    public void finishAllTables() {
        statsByTable.keySet().forEach(this::finishTable);
    }

    private static final class TableStats {
        int pageSize;
        String dataFormat = TableData.FORMAT_TABULAR;
        final AtomicInteger totalPages = new AtomicInteger();
        final AtomicInteger totalRecords = new AtomicInteger();
    }
}
