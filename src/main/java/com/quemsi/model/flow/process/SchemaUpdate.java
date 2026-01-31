package com.quemsi.model.flow.process;

import java.io.IOException;
import java.nio.charset.Charset;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.dto.SchemaUpdateConfig;
import com.quemsi.model.flow.AbstractStep;
import com.quemsi.model.flow.DataPackage;
import com.quemsi.model.flow.FlowContext;
import com.quemsi.model.flow.db.DDLService;
import com.quemsi.model.flow.db.DataSourceFactory;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.diff.DbModelDiff;
import com.quemsi.model.flow.db.sql.diff.DiffEntityType;
import com.quemsi.model.service.DbModelDiffExtractor;
import com.quemsi.model.util.CommonConstants;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SchemaUpdate extends AbstractStep {
    @Setter
    private ObjectMapper objectMapper;
    @Setter
    private SchemaUpdateConfig config;
    @Setter
    private DataSourceFactory datasourceFactory;

    @Override
    public void execute(FlowContext context) {
        try {
            // Get DbModel from data file
            DbModel sourceModel = getDbModelFromDataFile(context);
            
            // Get DbModel from datasource
            DbModel targetModel = datasourceFactory.getDbModel();
            
            String targetModelJson = objectMapper.writeValueAsString(targetModel);
            log.info("Target model: {}", targetModelJson);

            log.info("Calculating schema differences between source model and target database");
            
            // Calculate difference
            DbModelDiffExtractor extractor = new DbModelDiffExtractor();
            DbModelDiff diff = extractor.extract(sourceModel, targetModel);
            
            // Filter out sequence operations if skipSequences is true
            if (config != null && Boolean.TRUE.equals(config.getSkipSequences())) {
                diff.getOperations().removeIf(op -> op.getEntityType() == DiffEntityType.SEQUENCE);
                log.info("Skipping sequence operations as per configuration");
            }
            
            int operationCount = diff.getOperations().size();
            log.info("Found {} schema differences to apply", operationCount);
            
            if (operationCount == 0) {
                log.info("No schema changes detected, nothing to update");
                return;
            }
            
            // Generate migration DDL
            try (DDLService ddlService = datasourceFactory.ddlService()) {
                List<String> sqlStatements = ddlService.ddlFrom(diff, sourceModel);
                
                log.info("Generated {} SQL statements for schema migration", sqlStatements.size());
                if(sqlStatements.isEmpty()) {
                    context.logStepInfo(context.getCurrentStep(), "No schema changes detected, nothing to update");
                    return;
                }
                String label = "";
                if(config != null && Boolean.TRUE.equals(config.getDryRun())) {
                    label = "DRY RUN: ";
                }
                context.logStepInfo(context.getCurrentStep(), label + "created but did not execute " + sqlStatements.size() + " SQL statements");
                context.logStepInfo(context.getCurrentStep(), label + "SQL statements: " + sqlStatements.stream().collect(Collectors.joining(System.lineSeparator())));
                if(config == null || !Boolean.TRUE.equals(config.getDryRun())){
                    // Execute DDL statements
                    executeStatements(ddlService, sqlStatements, context);
                }
            }
            
            log.info("Schema update completed successfully");
        } catch (Exception e) {
            throw Exceptions.server("exception-in-schema-update").withCause(e).get();
        }
    }
    
    private DbModel getDbModelFromDataFile(FlowContext context) {
        List<DataPackage> dataPackages = context.getDataPackages();
        if (dataPackages == null || dataPackages.isEmpty()) {
            throw Exceptions.notFound("unable-to-find-data-packages").get();
        }
        
        Map<String, DataPackage> namedPackages = dataPackages.stream()
            .collect(Collectors.toMap(DataPackage::getName, dp -> dp));
        
        if (!namedPackages.containsKey(CommonConstants.DB_MODEL_FILE_NAME)) {
            throw Exceptions.notFound("unable-to-find-db-model").get();
        }
        
        DataPackage dbModelPackage = namedPackages.get(CommonConstants.DB_MODEL_FILE_NAME);
        if (!"application/json".equals(dbModelPackage.getContentType())) {
            throw Exceptions.badRequest("unsupported-content-type-for-db-model")
                .withExtra("contentType", dbModelPackage.getContentType())
                .withExtra("supported", "application/json")
                .get();
        }
        
        try {
            String dbModelJsonStr = IOUtils.toString(
                dbModelPackage.getInputStream(), 
                Charset.forName("UTF-8")
            );
            DbModel dbModel = objectMapper.readValue(dbModelJsonStr, DbModel.class);
            log.info("Loaded DbModel from data file with {} tables", dbModel.getTables().size());
            return dbModel;
        } catch (IOException e) {
            throw Exceptions.server("io-exception-reading-db-model").withCause(e).get();
        }
    }
    
    private void executeStatements(DDLService ddlService, List<String> sqlStatements, FlowContext context) {
        boolean dryRun = config != null && Boolean.TRUE.equals(config.getDryRun());
        boolean continueOnError = config == null || Boolean.TRUE.equals(config.getContinueOnError());
        
        int successCount = 0;
        int errorCount = 0;
        
        for (int i = 0; i < sqlStatements.size(); i++) {
            String sql = sqlStatements.get(i);
            log.info("Executing statement {}/{}: {}", i + 1, sqlStatements.size(), sql);
            
            if (dryRun) {
                log.info("DRY RUN: Would execute: {}", sql);
                successCount++;
                continue;
            }
            
            try {
                ddlService.executeSql(sql);
                successCount++;
                log.debug("Successfully executed statement {}/{}", i + 1, sqlStatements.size());
            } catch (SQLException e) {
                errorCount++;
                String errorMessage = String.format("Failed to execute SQL statement %d/%d: %s", 
                    i + 1, sqlStatements.size(), e.getMessage());
                log.error(errorMessage, e);
                log.error("Failed SQL: {}", sql);
                
                // Send error to API
                context.logError("schema-update-sql-error", 
                    Exceptions.server("sql-execution-failed")
                        .withCause(e)
                        .withExtra("statementNumber", i + 1)
                        .withExtra("totalStatements", sqlStatements.size())
                        .withExtra("sql", sql)
                        .get());
                
                if (!continueOnError) {
                    throw Exceptions.server("schema-update-failed-on-statement")
                        .withCause(e)
                        .withExtra("statementNumber", i + 1)
                        .withExtra("totalStatements", sqlStatements.size())
                        .withExtra("sql", sql)
                        .get();
                }
            }
        }
        
        log.info("Schema update execution summary: {} successful, {} failed out of {} total statements", 
            successCount, errorCount, sqlStatements.size());
        
        if (errorCount > 0 && !continueOnError) {
            throw Exceptions.server("schema-update-completed-with-errors")
                .withExtra("successCount", successCount)
                .withExtra("errorCount", errorCount)
                .withExtra("totalStatements", sqlStatements.size())
                .get();
        }
    }

    @Override
    public void fillDetails(List<Map<String, Object>> steps) {
        Map<String, Object> props = new HashMap<>();
        props.put("type", SchemaUpdate.class.getSimpleName());
        props.put("datasource", datasourceFactory.getName());
        if (config != null) {
            props.put("config", objectMapper.convertValue(config, new TypeReference<Map<String, Object>>() {}));
        }
        steps.add(props);
    }
}

