package com.quemsi.model.flow.process;


import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.commons.util.FileResource;
import com.quemsi.model.dto.MaskColumn;
import com.quemsi.model.flow.AbstractStep;
import com.quemsi.model.flow.DataPackage;
import com.quemsi.model.flow.DataPackageFileResource;
import com.quemsi.model.flow.FlowContext;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.in.TableData;
import com.quemsi.model.util.CommonConstants;
import com.quemsi.model.util.CommonHelpers;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MaskColumns extends AbstractStep {
    @Setter
    private ObjectMapper objectMapper;
    @Setter
    private MaskColumn config;
    @Setter
    private int parallelism;
    private Map<String, CompletableFuture<Object>> taskRegistry = new HashMap<>();
    private AtomicBoolean globalCancellationFlag = new AtomicBoolean(false);
    private AtomicReference<Exception> firstFailure = new AtomicReference<>();

    @Override
    public void execute(FlowContext context) {
        List<DataPackage> dataPackages = context.getDataPackages();
        if(!dataPackages.isEmpty()){
            /* Reset global state */
            globalCancellationFlag.set(false);
            firstFailure.set(null);
            taskRegistry.clear();
            
            Map<String, DataPackage> namedPackages = dataPackages.stream().collect(Collectors.toMap(dp -> dp.getName(), dp -> dp));
            if(!namedPackages.containsKey(CommonConstants.DB_MODEL_FILE_NAME)){
                throw Exceptions.notFound("unable-to-find-db-model").get();
            }
            if(!"application/json".equals(namedPackages.get(CommonConstants.DB_MODEL_FILE_NAME).getContentType())){
                throw Exceptions.badRequest("unsupported-content-type-for-db-model").withExtra("contentType", namedPackages.get(CommonConstants.DB_MODEL_FILE_NAME).getContentType())
                    .withExtra("supported", "application/json").get();
            }
            try(
                ForkJoinPool pool = new ForkJoinPool(parallelism);
                ){
                String dbModelJsonStr = IOUtils.toString(namedPackages.get(CommonConstants.DB_MODEL_FILE_NAME).getInputStream(), Charset.forName("UTF-8"));
                DbModel dbModel = objectMapper.readValue(dbModelJsonStr, DbModel.class);
                Set<String> tableToMask = new HashSet<>();
                // Validate that each MaskColumn refers to an existing table and column in dbModel
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
                    // Dot paths are allowed for document backups (MongoDB nested fields).
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

                // Create MaskedStringGenerator from config
                MaskedStringGenerator maskedStringGenerator = new MaskedStringGenerator();
                maskedStringGenerator.setMaskType(config.getMaskType());
                maskedStringGenerator.setMaskChar(config.getMaskChar());
                maskedStringGenerator.setLength(config.getLength());
                
                List<ForkJoinTask<DataPackage>> taskList = dbModel.orderedTables().stream()
                .map(table -> new MaskColumnTask(table, namedPackages.get(CommonHelpers.dataFileName(table.qualifiedName())), maskedStringGenerator, config, tableToMask.contains(table.qualifiedName())))
                .map(t -> {
                    taskRegistry.put(t.getTable().qualifiedName(), new CompletableFuture<>());
                    ForkJoinTask<DataPackage> task = pool.submit(t);
                    return task;
                }).toList();
                
                if(!globalCancellationFlag.get()){
                    List<DataPackage> result = taskList.stream().map(Exceptions.wrapFunction(t -> t.get())).toList();
                    List<DataPackage> maskedResult = new ArrayList<>();
                    maskedResult.add(namedPackages.get(CommonConstants.DB_MODEL_FILE_NAME));
                    maskedResult.addAll(result);
                    context.setDataPackages(maskedResult);
                    log.info("all data is masked successfully for {} packages", result.size());
                    return;
                } else {
                    Exception failure = firstFailure.get();
                    String errorMessage = failure != null ? 
                        "Masking failed due to: " + failure.getMessage() : 
                        "Masking failed - one or more tasks failed";
                    log.error(errorMessage);
                    throw Exceptions.server("restore-failed").withCause(failure).get();
                }
            } catch(IOException e){
                throw Exceptions.server("io-exception-in-masking-columns").withCause(e).get();
            } catch(Exception e){
                throw Exceptions.server("exception-in-masking-columns").withCause(e).get();
            }
        }
    }

    @Override
    public void fillDetails(List<Map<String, Object>> steps) {
        Map<String, Object> props = new HashMap<>();
        props.put("type", MaskColumns.class.getSimpleName());
        props.put("config", objectMapper.convertValue(config, new TypeReference<Map<String, Object>>() {}));
        steps.add(props);
    }

    public class MaskColumnTask implements Callable<DataPackage>{
        @Getter
        private DbTable table;
        private DataPackage dataPackage;
        private MaskedStringGenerator maskedStringGenerator;
        private MaskColumn config;
        private boolean maskNeeded;
        
        public MaskColumnTask(DbTable table, DataPackage dataPackage, MaskedStringGenerator maskedStringGenerator, MaskColumn config, boolean maskNeeded){
            this.table = table;
            this.dataPackage = dataPackage;
            this.maskedStringGenerator = maskedStringGenerator;
            this.config = config;
            this.maskNeeded = maskNeeded;
        }

        @Override
        public DataPackage call() throws Exception {
            if(!maskNeeded){
                return dataPackage;
            }
            try{
                /* Check for global cancellation before starting */
                if (globalCancellationFlag.get()) {
                    log.info("Mask column task for table {} cancelled before execution", table.getName());
                    return null;
                }
                
                // Read dataPackage and convert to TableData
                String fileName = CommonHelpers.dataFileName(table.qualifiedName());
                if(dataPackage == null){
                    throw Exceptions.notFound("unable-to-find-data-file").withExtra("fileName", fileName).withExtra("table", table.qualifiedName()).get();
                }
                
                String tableDataStr = IOUtils.toString(dataPackage.getInputStream(), Charset.forName("UTF-8"));
                TableData tableData = objectMapper.readValue(tableDataStr, TableData.class);
                log.info("{} pages for {}", tableData.getDataPages().size(), tableData.getTableName());
                
                /* Check for global cancellation after reading data */
                if (globalCancellationFlag.get()) {
                    log.info("Mask column task for table {} cancelled after reading data", table.getName());
                    return null;
                }
                
                // Find which columns to mask from the config for this table
                List<MaskColumn.MaskColumnConfig> columnsToMask = config.getColumns().stream()
                    .filter(mc -> {
                        String tableQualifiedName = CommonHelpers.qualifiedName(mc.getSchema(), mc.getTable());
                        return tableQualifiedName.equals(table.qualifiedName());
                    })
                    .toList();
                
                if(columnsToMask.isEmpty()){
                    log.info("no columns to mask for table {}", table.qualifiedName());
                } else {
                    log.info("masking {} columns for table {}", columnsToMask.size(), table.qualifiedName());
                    if(tableData.isDocumentFormat()){
                        maskDocumentPages(tableData, columnsToMask, maskedStringGenerator);
                    } else {
                        maskTabularPages(tableData, columnsToMask, maskedStringGenerator, table);
                    }
                }
                
                /* Check for global cancellation after masking */
                if (globalCancellationFlag.get()) {
                    log.info("Mask column task for table {} cancelled after masking", table.getName());
                    return null;
                }
                
                // Serialize table data and create a datapackage
                String tableDataJson = objectMapper.writeValueAsString(tableData);
                byte[] dataPagesJsonBytes = tableDataJson.getBytes(Charset.forName("UTF-8"));
                FileResource tData = FileResource.builder()
                    .name(fileName)
                    .originalFilename(fileName)
                    .contentType(MediaType.APPLICATION_JSON_VALUE)
                    .empty(false)
                    .size(dataPagesJsonBytes.length)
                    .data(dataPagesJsonBytes)
                    .build();
                DataPackageFileResource tDataResource = new DataPackageFileResource(tData.getName(), tData);
                return tDataResource;
            }catch(Exception e){
                log.error("failed to process " + table.getName(), e);
                firstFailure.compareAndSet(null, e);
                globalCancellationFlag.set(true);
                return null;
            }
        }

        private void maskTabularPages(TableData tableData, List<MaskColumn.MaskColumnConfig> columnsToMask,
                MaskedStringGenerator maskedStringGenerator, DbTable table) {
            com.quemsi.model.flow.db.sql.DbColumn[] orderedColumns = table.orderedColumns();
            Map<String, Integer> columnIndexMap = new HashMap<>();
            Map<Integer, Integer> maxLengthMap = new HashMap<>();
            for(int i = 0; i < orderedColumns.length; i++){
                columnIndexMap.put(orderedColumns[i].getName(), i);
                maxLengthMap.put(i, orderedColumns[i].getMaxLength());
            }

            Set<Integer> columnIndicesToMask = new HashSet<>();
            for(MaskColumn.MaskColumnConfig mc : columnsToMask){
                Integer index = columnIndexMap.get(mc.getColumn());
                if(index != null){
                    columnIndicesToMask.add(index);
                    log.info("will mask column {} at index {} for table {}", mc.getColumn(), index, table.qualifiedName());
                } else {
                    log.warn("column {} not found in table {}", mc.getColumn(), table.qualifiedName());
                }
            }

            for(TableData.DataPage dataPage : tableData.getDataPages()){
                if (globalCancellationFlag.get()) {
                    return;
                }
                if(dataPage.getData() == null){
                    continue;
                }
                for(Map.Entry<Object, Object[]> entry : dataPage.getData().entrySet()){
                    Object[] row = entry.getValue();
                    for(Integer columnIndex : columnIndicesToMask){
                        if(columnIndex < row.length && row[columnIndex] != null){
                            String originalValue = row[columnIndex].toString();
                            String maskedValue = maskedStringGenerator.generate(originalValue, maxLengthMap.get(columnIndex));
                            row[columnIndex] = maskedValue;
                        }
                    }
                }
            }
        }

        private void maskDocumentPages(TableData tableData, List<MaskColumn.MaskColumnConfig> columnsToMask,
                MaskedStringGenerator maskedStringGenerator) {
            List<String> paths = columnsToMask.stream().map(MaskColumn.MaskColumnConfig::getColumn).toList();
            for(TableData.DataPage dataPage : tableData.getDataPages()){
                if (globalCancellationFlag.get()) {
                    return;
                }
                if(dataPage.getDocuments() == null){
                    continue;
                }
                for(Map<String, Object> document : dataPage.getDocuments().values()){
                    for(String path : paths){
                        maskDocumentPath(document, path, maskedStringGenerator);
                    }
                }
            }
        }

        @SuppressWarnings("unchecked")
        private void maskDocumentPath(Map<String, Object> document, String path, MaskedStringGenerator generator) {
            if(document == null || path == null || path.isBlank()){
                return;
            }
            String[] parts = path.split("\\.");
            Object current = document;
            for(int i = 0; i < parts.length - 1; i++){
                if(!(current instanceof Map<?, ?> map)){
                    return;
                }
                current = map.get(parts[i]);
            }
            if(!(current instanceof Map<?, ?> parentMap)){
                return;
            }
            Map<String, Object> writable = (Map<String, Object>) parentMap;
            String leaf = parts[parts.length - 1];
            Object value = writable.get(leaf);
            if(value == null){
                return;
            }
            if(value instanceof String || value instanceof Number || value instanceof Boolean){
                writable.put(leaf, generator.generate(String.valueOf(value), Integer.MAX_VALUE));
            }
        }
    }
}
