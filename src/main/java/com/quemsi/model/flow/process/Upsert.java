package com.quemsi.model.flow.process;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.commons.util.LogMessage;
import com.quemsi.model.dto.DatasourceType;
import com.quemsi.model.dto.UpsertConfig;
import com.quemsi.model.flow.AbstractStep;
import com.quemsi.model.flow.DataPackage;
import com.quemsi.model.flow.FlowContext;
import com.quemsi.model.flow.db.DataSourceFactory;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.file.BackupArchive;
import com.quemsi.model.flow.file.DirectoryBackupArchive;
import com.quemsi.model.flow.in.TableData.DataPage;
import com.quemsi.model.flow.upsert.SqlUpsertSupport;
import com.quemsi.model.flow.upsert.UpsertFailure;
import com.quemsi.model.flow.upsert.UpsertPlan;
import com.quemsi.model.flow.upsert.UpsertPlanner;
import com.quemsi.model.flow.upsert.UpsertRowSource;
import com.quemsi.model.flow.upsert.UpsertTablePlan;
import com.quemsi.model.flow.upsert.UpsertTargetLookup;
import com.quemsi.model.util.CommonConstants;
import com.quemsi.model.util.CommonHelpers;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Upsert extends AbstractStep {
    @Setter
    private ObjectMapper objectMapper;
    @Setter
    private UpsertConfig config;
    @Setter
    private DataSourceFactory datasourceFactory;

    private final UpsertPlanner planner = new UpsertPlanner();

    @Override
    public void execute(FlowContext context) {
        try {
            datasourceFactory.assertWritable();
            if (datasourceFactory.type() == DatasourceType.MONGODB) {
                throw Exceptions.badRequest("upsert-unsupported-datasource")
                    .withExtra("type", datasourceFactory.type())
                    .get();
            }
            UpsertConfig effective = config != null ? config : new UpsertConfig();
            BackupArchive archive = openArchive(context);
            DbModel sourceModel = getDbModelFromDataFile(context, archive);
            if (context.getDbModelProcessors() != null) {
                context.getDbModelProcessors().forEach(p -> p.process(sourceModel));
            }
            sourceModel.build();

            if (sourceModel.getSourceType() != null
                    && !datasourceFactory.type().name().equals(sourceModel.getSourceType())) {
                throw Exceptions.badRequest("upsert-source-type-mismatch")
                    .withExtra("sourceType", sourceModel.getSourceType())
                    .withExtra("targetType", datasourceFactory.type().name())
                    .get();
            }

            DbModel targetModel = datasourceFactory.getDbModel(msg -> context.logStep(context.getCurrentStep(), msg));
            UpsertRowSource rowSource = new ArchiveRowSource(archive, objectMapper);

            try (Connection conn = datasourceFactory.getDataSource().getConnection()) {
                var tableQuoter = SqlUpsertSupport.tableQuoter(datasourceFactory.type());
                var columnQuoter = SqlUpsertSupport.columnQuoter(datasourceFactory.type());
                UpsertTargetLookup lookup = SqlUpsertSupport.lookup(conn, tableQuoter, columnQuoter);
                UpsertPlan plan = planner.plan(sourceModel, targetModel, effective, rowSource, lookup);
                logPlan(context, plan, effective.isDryRun());
                if (!plan.isUpsertable()) {
                    throw Exceptions.badRequest("upsert-not-upsertable")
                        .withExtra("failures", plan.getFailures().stream()
                            .map(f -> f.getTable() + "[" + f.getKey() + "]: " + f.getReason())
                            .toList())
                        .get();
                }
                if (effective.isDryRun()) {
                    context.logStepInfo(context.getCurrentStep(),
                        LogMessage.info("DRY RUN: upsert plan is valid, no rows written"));
                    return;
                }
                SqlUpsertSupport.runInTransaction(conn,
                    () -> SqlUpsertSupport.applyPlan(conn, plan, tableQuoter, columnQuoter));
                context.logStepInfo(context.getCurrentStep(),
                    LogMessage.info("Upsert applied successfully"));
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw Exceptions.server("exception-in-upsert").withCause(e).get();
        }
    }

    private void logPlan(FlowContext context, UpsertPlan plan, boolean dryRun) {
        String prefix = dryRun ? "DRY RUN: " : "";
        if (plan.getTables() != null) {
            for (UpsertTablePlan tablePlan : plan.getTables()) {
                context.logStepInfo(context.getCurrentStep(), LogMessage.info(
                    prefix + "{} match={} inserts={} updates={} skips={}",
                    tablePlan.getQualifiedName(),
                    tablePlan.getMatchKey().getColumns(),
                    tablePlan.getInserts().size(),
                    tablePlan.getUpdates().size(),
                    tablePlan.getSkips().size()));
            }
        }
        if (plan.getFailures() != null) {
            for (UpsertFailure failure : plan.getFailures()) {
                context.logStepError(context.getCurrentStep(),
                    prefix + failure.getTable() + "[" + failure.getKey() + "]: " + failure.getReason());
            }
        }
    }

    private BackupArchive openArchive(FlowContext context) {
        if (context.getBackupArchive() != null) {
            return context.getBackupArchive();
        }
        if (context.getStagingDir() != null) {
            BackupArchive archive = DirectoryBackupArchive.open(context.getStagingDir());
            context.setBackupArchive(archive);
            return archive;
        }
        throw Exceptions.badRequest("backup-archive-required")
            .withExtra("hint", "Unzip step must open the backup zip before Upsert")
            .get();
    }

    private DbModel getDbModelFromDataFile(FlowContext context, BackupArchive archive) {
        try {
            if (archive != null && archive.exists(CommonConstants.DB_MODEL_FILE_NAME)) {
                try (InputStream in = archive.open(CommonConstants.DB_MODEL_FILE_NAME)) {
                    DbModel dbModel = objectMapper.readValue(in, DbModel.class);
                    context.logStepInfo(context.getCurrentStep(),
                        LogMessage.info("Loaded DbModel from archive with {} tables", dbModel.getTables().size()));
                    return dbModel;
                }
            }
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
            String dbModelJsonStr = IOUtils.toString(dbModelPackage.getInputStream(), Charset.forName("UTF-8"));
            return objectMapper.readValue(dbModelJsonStr, DbModel.class);
        } catch (IOException e) {
            throw Exceptions.server("io-exception-reading-db-model").withCause(e).get();
        }
    }

    @Override
    public void fillDetails(List<Map<String, Object>> steps) {
        Map<String, Object> props = new HashMap<>();
        props.put("type", Upsert.class.getSimpleName());
        props.put("datasource", datasourceFactory.getName());
        if (config != null) {
            props.put("config", objectMapper.convertValue(config, new TypeReference<Map<String, Object>>() {}));
        }
        steps.add(props);
    }

    static final class ArchiveRowSource implements UpsertRowSource {
        private final BackupArchive archive;
        private final ObjectMapper objectMapper;

        ArchiveRowSource(BackupArchive archive, ObjectMapper objectMapper) {
            this.archive = archive;
            this.objectMapper = objectMapper;
        }

        @Override
        public List<Object[]> loadRows(String qualifiedTableName) {
            String metaEntry = CommonHelpers.tableMetaEntryName(qualifiedTableName);
            if (!archive.exists(metaEntry)) {
                throw Exceptions.badRequest("upsert-table-not-in-archive")
                    .withExtra("table", qualifiedTableName)
                    .get();
            }
            List<Object[]> rows = new ArrayList<>();
            try {
                for (String pageEntry : archive.listPageEntries(qualifiedTableName)) {
                    try (InputStream in = archive.open(pageEntry)) {
                        DataPage page = objectMapper.readValue(in, DataPage.class);
                        if (page.getData() != null) {
                            rows.addAll(page.getData().values());
                        }
                    }
                }
            } catch (IOException e) {
                throw Exceptions.server("upsert-unable-to-read-pages")
                    .withExtra("table", qualifiedTableName)
                    .withCause(e)
                    .get();
            }
            return rows;
        }
    }
}
