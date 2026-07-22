package com.quemsi.model.flow.file;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.in.TableData;
import com.quemsi.model.flow.in.TableData.DataPage;
import com.quemsi.model.flow.in.TableDataMeta;
import com.quemsi.model.flow.in.TableDataPage;
import com.quemsi.model.flow.in.TableDataPage.Request;
import com.quemsi.model.util.CommonConstants;
import com.quemsi.model.util.CommonHelpers;

public class StagingAndArchiveTest {

    @TempDir
    Path tempDir;

    @Test
    public void namingHelpers_followArchiveLayout() {
        assertThat(CommonHelpers.tableMetaEntryName("dbo.Taxi"), equalTo("tables/dbo.Taxi/meta.json"));
        assertThat(CommonHelpers.tablePageEntryName("dbo.Taxi", 3), equalTo("tables/dbo.Taxi/pages/3.json"));
        assertThat(CommonHelpers.tablePagesPrefix("public.film"), equalTo("tables/public.film/pages/"));
        assertThat(CommonHelpers.pageNumFromEntryName("tables/public.film/pages/12.json"), equalTo(12));
    }

    @Test
    public void stagingWriter_writesPagesAndMeta() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        StagingBackupWriter writer = new StagingBackupWriter(tempDir);
        writer.setObjectMapper(mapper);
        writer.writeDbModel("{\"tables\":{}}");

        DbTable table = new DbTable("public", "film");

        Request request = Request.builder().table(table).pageNum(0).pageSize(100).build();
        TableDataPage page0 = new TableDataPage();
        page0.setRequest(request);
        Map<Object, Object[]> rows = new HashMap<>();
        rows.put(1, new Object[] { 1, "A" });
        page0.setTableData(rows);
        writer.persist(page0);

        Request request1 = Request.builder().table(table).pageNum(1).pageSize(100).build();
        TableDataPage page1 = new TableDataPage();
        page1.setRequest(request1);
        Map<Object, Object[]> rows1 = new HashMap<>();
        rows1.put(2, new Object[] { 2, "B" });
        page1.setTableData(rows1);
        writer.persist(page1);
        writer.finishTable(table.qualifiedName());

        assertThat(Files.exists(tempDir.resolve(CommonConstants.DB_MODEL_FILE_NAME)), equalTo(true));
        assertThat(Files.exists(tempDir.resolve(CommonHelpers.tablePageEntryName("public.film", 0))), equalTo(true));
        assertThat(Files.exists(tempDir.resolve(CommonHelpers.tablePageEntryName("public.film", 1))), equalTo(true));

        TableDataMeta meta = mapper.readValue(
            tempDir.resolve(CommonHelpers.tableMetaEntryName("public.film")).toFile(),
            TableDataMeta.class);
        assertThat(meta.getTotalPages(), equalTo(2));
        assertThat(meta.getTotalRecords(), equalTo(2));
        assertThat(meta.getDataFormat(), equalTo(TableData.FORMAT_TABULAR));

        DataPage loaded = mapper.readValue(
            tempDir.resolve(CommonHelpers.tablePageEntryName("public.film", 0)).toFile(),
            DataPage.class);
        assertThat(loaded.getPageNum(), equalTo(0));
        assertThat(loaded.getSize(), equalTo(1));
    }

    @Test
    public void zipAndBackupArchive_roundTripPageEntries() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path staging = tempDir.resolve("staging");
        Files.createDirectories(staging);
        StagingBackupWriter writer = new StagingBackupWriter(staging);
        writer.setObjectMapper(mapper);
        writer.writeDbModel("{\"sourceType\":\"POSTGRES\"}");

        DbTable table = new DbTable("public", "t");
        Request request = Request.builder().table(table).pageNum(0).pageSize(10).build();
        TableDataPage page = new TableDataPage();
        page.setRequest(request);
        page.setTableData(Map.of(1, new Object[] { 1 }));
        writer.persist(page);
        writer.finishTable("public.t");

        Path zipPath = tempDir.resolve("backup.zip");
        try (var fos = Files.newOutputStream(zipPath);
             var archive = new org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(fos)) {
            try (var walk = Files.walk(staging)) {
                for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                    String entry = staging.relativize(file).toString().replace('\\', '/');
                    archive.putArchiveEntry(new org.apache.commons.compress.archivers.zip.ZipArchiveEntry(entry));
                    Files.copy(file, archive);
                    archive.closeArchiveEntry();
                }
            }
            archive.finish();
        }

        try (ZipBackupArchive zip = ZipBackupArchive.open(zipPath.toFile())) {
            assertThat(zip.exists(CommonConstants.DB_MODEL_FILE_NAME), equalTo(true));
            List<String> pages = zip.listPageEntries("public.t");
            assertThat(pages, hasSize(1));
            assertThat(pages, contains("tables/public.t/pages/0.json"));
            try (var in = zip.open(pages.get(0))) {
                DataPage loaded = mapper.readValue(in, DataPage.class);
                assertThat(loaded.getPageNum(), equalTo(0));
            }
        }
    }
}
