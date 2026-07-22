package com.quemsi.model.flow.process;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.MediaType;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.commons.util.LogMessage;
import com.quemsi.model.dto.MaskColumn;
import com.quemsi.model.flow.AbstractStep;
import com.quemsi.model.flow.DataPackageArchiveEntry;
import com.quemsi.model.flow.FlowContext;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.file.BackupArchive;
import com.quemsi.model.flow.file.DirectoryBackupArchive;
import com.quemsi.model.flow.in.TableData;
import com.quemsi.model.flow.in.TableData.DataPage;
import com.quemsi.model.util.CommonConstants;
import com.quemsi.model.util.CommonHelpers;
import com.quemsi.model.util.QuemsiTemp;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * Masks configured columns page-by-page on a staging directory (backup) or by
 * materializing a {@link BackupArchive} to staging (restore).
 */
@Slf4j
public class MaskColumns extends AbstractStep {
    @Setter
    private ObjectMapper objectMapper;
    @Setter
    private MaskColumn config;

    @Override
    public void execute(FlowContext context) {
        try {
            Path staging = ensureStaging(context);
            BackupArchive stagingArchive = DirectoryBackupArchive.open(staging);

            DbModel dbModel;
            try (InputStream in = stagingArchive.open(CommonConstants.DB_MODEL_FILE_NAME)) {
                dbModel = objectMapper.readValue(in, DbModel.class);
            }

            Set<String> tableToMask = new HashSet<>();
            for (MaskColumn.MaskColumnConfig maskColumn : config.getColumns()) {
                String schemaName = maskColumn.getSchema();
                String tableName = maskColumn.getTable();
                String columnName = maskColumn.getColumn();
                DbTable dbTable = dbModel.getTables().get(CommonHelpers.qualifiedName(schemaName, tableName));
                if (dbTable == null) {
                    throw Exceptions.badRequest("maskcol-table-not-found")
                        .withExtra("schema", schemaName)
                        .withExtra("table", tableName)
                        .get();
                }
                boolean isDotPath = columnName != null && columnName.contains(".");
                if (!isDotPath && !dbTable.getColumns().containsKey(columnName)) {
                    throw Exceptions.badRequest("maskcol-column-not-found")
                        .withExtra("schema", schemaName)
                        .withExtra("table", tableName)
                        .withExtra("column", columnName)
                        .get();
                }
                tableToMask.add(dbTable.qualifiedName());
            }

            MaskedStringGenerator maskedStringGenerator = new MaskedStringGenerator();
            maskedStringGenerator.setMaskType(config.getMaskType());
            maskedStringGenerator.setMaskChar(config.getMaskChar());
            maskedStringGenerator.setLength(config.getLength());

            for (String qualifiedName : tableToMask) {
                DbTable table = dbModel.getTables().get(qualifiedName);
                List<MaskColumn.MaskColumnConfig> columnsToMask = config.getColumns().stream()
                    .filter(mc -> CommonHelpers.qualifiedName(mc.getSchema(), mc.getTable()).equals(qualifiedName))
                    .toList();
                List<String> pageEntries = stagingArchive.listPageEntries(qualifiedName);
                context.logStepInfo(context.getCurrentStep(),
                    LogMessage.info("Masking {} pages for {}", pageEntries.size(), qualifiedName));
                for (String pageEntry : pageEntries) {
                    Path pagePath = staging.resolve(pageEntry);
                    DataPage page;
                    try (InputStream in = Files.newInputStream(pagePath)) {
                        page = objectMapper.readValue(in, DataPage.class);
                    }
                    TableData wrapper = new TableData(qualifiedName);
                    wrapper.getDataPages().add(page);
                    if (page.getDocuments() != null) {
                        wrapper.setDataFormat(TableData.FORMAT_DOCUMENT);
                        maskDocumentPages(wrapper, columnsToMask, maskedStringGenerator);
                    } else {
                        maskTabularPages(wrapper, columnsToMask, maskedStringGenerator, table);
                    }
                    objectMapper.writeValue(pagePath.toFile(), page);
                }
            }

            context.setBackupArchive(DirectoryBackupArchive.open(staging));
            context.setDataPackages(List.of(new DataPackageArchiveEntry(
                context.getBackupArchive(),
                CommonConstants.DB_MODEL_FILE_NAME,
                MediaType.APPLICATION_JSON_VALUE
            )));
            context.logStepInfo(context.getCurrentStep(), LogMessage.info("Column masking completed"));
        } catch (Exception e) {
            throw Exceptions.server("exception-in-masking-columns").withCause(e).get();
        }
    }

    private Path ensureStaging(FlowContext context) throws IOException {
        if (context.getStagingDir() != null && Files.isDirectory(context.getStagingDir())) {
            return context.getStagingDir();
        }
        BackupArchive archive = context.getBackupArchive();
        if (archive == null) {
            throw Exceptions.notFound("unable-to-find-backup-data")
                .withExtra("hint", "MaskColumns requires stagingDir (backup) or backupArchive (restore)")
                .get();
        }
        Path staging = QuemsiTemp.createStagingDir("mask");
        context.logStepInfo(context.getCurrentStep(),
            LogMessage.info("Materializing archive to staging for masking: {}", staging));
        for (String entry : archive.list("")) {
            Path target = staging.resolve(entry);
            Files.createDirectories(target.getParent());
            try (InputStream in = archive.open(entry); OutputStream out = Files.newOutputStream(target)) {
                in.transferTo(out);
            }
        }
        context.closeBackupArchiveQuietly();
        context.setStagingDir(staging);
        return staging;
    }

    private void maskTabularPages(TableData tableData, List<MaskColumn.MaskColumnConfig> columnsToMask,
            MaskedStringGenerator maskedStringGenerator, DbTable table) {
        com.quemsi.model.flow.db.sql.DbColumn[] orderedColumns = table.orderedColumns();
        Map<String, Integer> columnIndexMap = new HashMap<>();
        Map<Integer, Integer> maxLengthMap = new HashMap<>();
        for (int i = 0; i < orderedColumns.length; i++) {
            columnIndexMap.put(orderedColumns[i].getName(), i);
            maxLengthMap.put(i, orderedColumns[i].getMaxLength());
        }
        Set<Integer> columnIndicesToMask = new HashSet<>();
        for (MaskColumn.MaskColumnConfig mc : columnsToMask) {
            Integer index = columnIndexMap.get(mc.getColumn());
            if (index != null) {
                columnIndicesToMask.add(index);
            }
        }
        for (DataPage dataPage : tableData.getDataPages()) {
            if (dataPage.getData() == null) {
                continue;
            }
            for (Object[] row : dataPage.getData().values()) {
                for (Integer columnIndex : columnIndicesToMask) {
                    if (columnIndex < row.length && row[columnIndex] != null) {
                        String originalValue = row[columnIndex].toString();
                        row[columnIndex] = maskedStringGenerator.generate(originalValue, maxLengthMap.get(columnIndex));
                    }
                }
            }
        }
    }

    private void maskDocumentPages(TableData tableData, List<MaskColumn.MaskColumnConfig> columnsToMask,
            MaskedStringGenerator maskedStringGenerator) {
        List<String> paths = columnsToMask.stream().map(MaskColumn.MaskColumnConfig::getColumn).toList();
        for (DataPage dataPage : tableData.getDataPages()) {
            if (dataPage.getDocuments() == null) {
                continue;
            }
            for (Map<String, Object> document : dataPage.getDocuments().values()) {
                for (String path : paths) {
                    maskDocumentPath(document, path, maskedStringGenerator);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void maskDocumentPath(Map<String, Object> document, String path, MaskedStringGenerator generator) {
        if (document == null || path == null || path.isBlank()) {
            return;
        }
        String[] parts = path.split("\\.");
        Object current = document;
        for (int i = 0; i < parts.length - 1; i++) {
            if (!(current instanceof Map<?, ?> map)) {
                return;
            }
            current = map.get(parts[i]);
        }
        if (!(current instanceof Map<?, ?> parentMap)) {
            return;
        }
        Map<String, Object> writable = (Map<String, Object>) parentMap;
        String leaf = parts[parts.length - 1];
        Object value = writable.get(leaf);
        if (value == null) {
            return;
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            writable.put(leaf, generator.generate(String.valueOf(value), Integer.MAX_VALUE));
        }
    }

    @Override
    public void fillDetails(List<Map<String, Object>> steps) {
        Map<String, Object> props = new HashMap<>();
        props.put("type", MaskColumns.class.getSimpleName());
        props.put("config", objectMapper.convertValue(config, new TypeReference<Map<String, Object>>() {}));
        steps.add(props);
    }
}
