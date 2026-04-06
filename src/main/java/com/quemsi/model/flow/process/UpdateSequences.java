package com.quemsi.model.flow.process;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.commons.util.LogMessage;
import com.quemsi.model.dto.DatasourceType;
import com.quemsi.model.flow.AbstractStep;
import com.quemsi.model.flow.FlowContext;
import com.quemsi.model.flow.db.DMLService;
import com.quemsi.model.flow.db.DataSourceFactory;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbSequence;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.util.CommonHelpers;

import lombok.Setter;

public class UpdateSequences extends AbstractStep {
    @Setter
    private ObjectMapper objectMapper;
    @Setter
    private com.quemsi.model.dto.UpdateSequences config;
    @Setter
    private DataSourceFactory datasourceFactory;

    @Override
    public void execute(FlowContext context) {
        try {
            datasourceFactory.assertWritable();
            if (datasourceFactory.type() == DatasourceType.MYSQL) {
                context.logStepWarn(context.getCurrentStep(), "MySQL does not support sequences, skipping sequence updates");
                return;
            }

            DbModel dbModel = datasourceFactory.getDbModel();
            try (DMLService dmlService = datasourceFactory.dmlService()) {
                // Build set of existing sequences in database for quick lookup
                Map<String, DbSequence> existingSequences = dbModel.getSequences().stream().collect(Collectors.toMap(DbSequence::qualifiedName, Function.identity()));
                
                // Process all tables using template
                if (config.getSequenceNameTemplate() != null && config.getColumnName() != null) {
                    processTablesWithTemplate(dmlService, dbModel, existingSequences, context);
                }

                // Process custom mappings
                if (config.getCustomMappings() != null && !config.getCustomMappings().isEmpty()) {
                    processCustomMappings(dmlService, dbModel, existingSequences, context);
                }

                context.logStepInfo(context.getCurrentStep(), LogMessage.info("Sequence updates completed successfully"));
            }
        } catch (Exception e) {
            throw Exceptions.server("exception-in-update-sequences").withCause(e).get();
        }
    }

    private void processTablesWithTemplate(DMLService dmlService, DbModel dbModel, Map<String, DbSequence> existingSequences, FlowContext context) {
        String template = config.getSequenceNameTemplate();
        String defaultColumnName = config.getColumnName();

        for (DbTable table : dbModel.orderedTables()) {
            try {
                // Generate sequence name from template
                String sequenceName = template
                .replace("{tableName}", table.getName())
                .replace("{columnName}", defaultColumnName);
                context.logStepInfo(context.getCurrentStep(), LogMessage.info("Generated sequence name: {}", sequenceName));

                String qualifiedSequenceName = CommonHelpers.qualifiedName(table.getSchema(), sequenceName);
                String qualifiedTableName = table.qualifiedName();
                context.logStepInfo(context.getCurrentStep(), LogMessage.info("Qualified sequence name: {} for table {}", qualifiedSequenceName, qualifiedTableName));
                // Find sequence in DbModel
                DbSequence sequence = existingSequences.get(qualifiedSequenceName);
                if (sequence == null) {
                    context.logStepInfo(context.getCurrentStep(), LogMessage.debug("Sequence {} not found in DbModel for table {}", qualifiedSequenceName, qualifiedTableName));
                    continue;
                }

                // Find column in table
                if (!table.getColumns().containsKey(defaultColumnName)) {
                    context.logStepInfo(context.getCurrentStep(), LogMessage.debug("Column {} not found in table {}, skipping sequence update", defaultColumnName, qualifiedTableName));
                    continue;
                }

                // Get MAX value from column
                Long maxValue = dmlService.getMaxColumnValue(qualifiedTableName, defaultColumnName);
                if (maxValue == null) {
                    context.logStepInfo(context.getCurrentStep(), LogMessage.debug("No max value found for column {} in table {}, setting sequence to 1", defaultColumnName, qualifiedTableName));
                    maxValue = 0L;
                }
                // Update sequence to 1 + max value
                dmlService.updateSequence(qualifiedSequenceName, maxValue + 1);
                context.logStepInfo(context.getCurrentStep(), LogMessage.info("Updated sequence {} to value {} for table {}", qualifiedSequenceName, maxValue + 1, qualifiedTableName));
            } catch (Exception e) {
                context.logStepWarn(context.getCurrentStep(), "Error processing table " + table.qualifiedName() + " for sequence update, continuing with other tables: " + e.getMessage());
            }
        }
    }

    private void processCustomMappings(DMLService dmlService, DbModel dbModel, Map<String, DbSequence> existingSequences, FlowContext context) {
        for (com.quemsi.model.dto.UpdateSequences.SequenceMapping mapping : config.getCustomMappings()) {
            try {
                String schema = mapping.getSchema();
                String sequenceName = mapping.getSequence();
                String qualifiedTableName = CommonHelpers.qualifiedName(schema, mapping.getTable());
                String qualifiedSequenceName = CommonHelpers.qualifiedName(schema, sequenceName);
                // Check if sequence exists in database
                if (!existingSequences.containsKey(qualifiedSequenceName)) {
                    context.logStepInfo(context.getCurrentStep(), LogMessage.debug("Custom mapping: Sequence {} does not exist in database, skipping", qualifiedSequenceName));
                    continue;
                }

                // Find table in DbModel
                DbTable table = dbModel.findTable(qualifiedTableName).orElse(null);
                if (table == null) {
                    context.logStepInfo(context.getCurrentStep(), LogMessage.debug("Custom mapping: Table {} not found in DbModel, skipping", qualifiedTableName));
                    continue;
                }

                // Find column in table
                if (!table.getColumns().containsKey(mapping.getColumn())) {
                    context.logStepInfo(context.getCurrentStep(), LogMessage.debug("Custom mapping: Column {} not found in table {}, skipping", mapping.getColumn(), qualifiedTableName));
                    continue;
                }

                // Get MAX value from column
                Long maxValue = dmlService.getMaxColumnValue(qualifiedTableName, mapping.getColumn());
                if (maxValue == null) {
                    context.logStepInfo(context.getCurrentStep(), LogMessage.debug("Custom mapping: No max value found for column {} in table {}, setting sequence to 1", mapping.getColumn(), qualifiedTableName));
                    maxValue = 0L;
                }

                // Update sequence to 1 + max value
                dmlService.updateSequence(qualifiedSequenceName, maxValue + 1);
                context.logStepInfo(context.getCurrentStep(), LogMessage.info("Updated sequence {} to value {} for custom mapping (table: {}, column: {})", 
                    sequenceName, maxValue + 1, qualifiedTableName, mapping.getColumn()));
            } catch (Exception e) {
                context.logStepWarn(context.getCurrentStep(), "Error processing custom mapping for sequence " + mapping.getSequence() + ", continuing with other mappings: " + e.getMessage());
            }
        }
    }

    @Override
    public void fillDetails(List<Map<String, Object>> steps) {
        Map<String, Object> props = new HashMap<>();
        props.put("type", UpdateSequences.class.getSimpleName());
        props.put("datasource", datasourceFactory.getName());
        props.put("config", objectMapper.convertValue(config, new TypeReference<Map<String, Object>>() {}));
        steps.add(props);
    }
}

