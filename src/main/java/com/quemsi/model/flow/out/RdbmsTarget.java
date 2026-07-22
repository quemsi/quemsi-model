package com.quemsi.model.flow.out;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.commons.util.LogMessage;
import com.quemsi.model.dto.DataFile;
import com.quemsi.model.dto.DatasourceType;
import com.quemsi.model.flow.DataPackage;
import com.quemsi.model.flow.Flow;
import com.quemsi.model.flow.FlowContext;
import com.quemsi.model.flow.db.DDLService;
import com.quemsi.model.flow.db.DMLService;
import com.quemsi.model.flow.db.DataSourceFactory;
import com.quemsi.model.flow.db.postgres.PostgresEnumSupport;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.ReferenceInfo;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.file.BackupArchive;
import com.quemsi.model.flow.file.DirectoryBackupArchive;
import com.quemsi.model.flow.in.TableData.DataPage;
import com.quemsi.model.flow.in.TableDataMeta;
import com.quemsi.model.util.CommonConstants;
import com.quemsi.model.util.CommonHelpers;

import lombok.Getter;
import lombok.Setter;

public class RdbmsTarget extends AbstractStorage {
    @Setter
    private DataSourceFactory datasourceFactory;
    @Setter
    private ObjectMapper objectMapper;
    @Setter
    private int parallelism;
    @Setter
    private int maxInFlightPages;
    private Map<String, CompletableFuture<Object>> taskRegistry = new HashMap<>();
    private AtomicBoolean globalCancellationFlag = new AtomicBoolean(false);
    private AtomicReference<Exception> firstFailure = new AtomicReference<>();
    private Semaphore inFlightPages;
    private ExecutorService pageWritePool;

    @Override
    public boolean recordFiles() {
        return false;
    }

    @Override
    public void init(Flow f) {
    }

    @Override
    public String getName() {
        return datasourceFactory.getName();
    }

    private int effectiveMaxInFlightPages() {
        return maxInFlightPages > 0 ? maxInFlightPages : Math.max(1, parallelism);
    }

    @Override
    public void store(FlowContext context, String dataName, List<DataPackage> dataPackages, Long version) {
        BackupArchive archive = context.getBackupArchive();
        if (archive == null && context.getStagingDir() != null) {
            archive = DirectoryBackupArchive.open(context.getStagingDir());
            context.setBackupArchive(archive);
        }
        if (archive == null) {
            throw Exceptions.badRequest("backup-archive-required")
                .withExtra("hint", "Unzip step must open the backup zip before RdbmsTarget")
                .get();
        }
        datasourceFactory.assertWritable();
        globalCancellationFlag.set(false);
        firstFailure.set(null);
        taskRegistry.clear();
        inFlightPages = new Semaphore(effectiveMaxInFlightPages());
        pageWritePool = Executors.newFixedThreadPool(Math.max(1, parallelism));

        try (DDLService ddlService = datasourceFactory.ddlService()) {
            DbModel dbModel;
            try (InputStream in = archive.open(CommonConstants.DB_MODEL_FILE_NAME)) {
                dbModel = objectMapper.readValue(in, DbModel.class);
            }

            if (!datasourceFactory.type().equals(DatasourceType.valueOf(dbModel.getSourceType()))) {
                throw Exceptions.badRequest("unsupported-source-type-for-rdbms-target")
                    .withExtra("sourceType", dbModel.getSourceType())
                    .withExtra("targetType", datasourceFactory.getName())
                    .get();
            }

            context.getDbModelProcessors().forEach(p -> p.process(dbModel));

            if (DatasourceType.POSTGRES.name().equals(dbModel.getSourceType())) {
                PostgresEnumSupport.ensureEnumTypes(dbModel);
            }

            context.logStepInfo(context.getCurrentStep(),
                LogMessage.info("schema will be created with {} tables", dbModel.getTables().size()));
            ddlService.createTables(dbModel);
            context.logStepInfo(context.getCurrentStep(),
                LogMessage.info("schema is created with {} tables", dbModel.getTables().size()));
            context.logStepInfo(context.getCurrentStep(),
                LogMessage.info("restoring with parallelism={} maxInFlightPages={}",
                    parallelism, effectiveMaxInFlightPages()));

            ExecutorService tablePool = Executors.newFixedThreadPool(Math.max(1, parallelism));
            try {
                List<Future<Boolean>> taskList = new ArrayList<>();
                for (DbTable table : dbModel.orderedTables()) {
                    RdmsRestoreTask task = new RdmsRestoreTask(table, archive, context, dbModel.getCircularIgnore());
                    taskRegistry.put(table.qualifiedName(), new CompletableFuture<>());
                    taskList.add(tablePool.submit(task));
                }
                boolean result = true;
                for (Future<Boolean> t : taskList) {
                    result = t.get() && result;
                }

                Set<ReferenceInfo> allFks = new LinkedHashSet<>();
                if (dbModel.getReferenceInfos() != null) {
                    allFks.addAll(dbModel.getReferenceInfos());
                }
                if (dbModel.getCircularIgnore() != null) {
                    allFks.addAll(dbModel.getCircularIgnore());
                }
                ddlService.enableContraints(allFks);

                if (result) {
                    ddlService.createFullTextIndexes(dbModel);
                    ddlService.createFunctions(dbModel);
                    ddlService.createViews(dbModel);
                    ddlService.createTriggers(dbModel);
                    context.logStepInfo(context.getCurrentStep(),
                        LogMessage.info("all data is restored successfully"));
                } else {
                    Exception failure = firstFailure.get();
                    String errorMessage = failure != null
                        ? "Restore failed due to: " + failure.getMessage()
                        : "Restore failed - one or more restore table tasks failed";
                    context.logStepError(context.getCurrentStep(), errorMessage);
                    throw Exceptions.server("restore-failed")
                        .withExtra("errorMessage", errorMessage)
                        .withCause(failure)
                        .get();
                }
            } finally {
                tablePool.shutdown();
                try {
                    tablePool.awaitTermination(1, TimeUnit.HOURS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        } catch (Exception e) {
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw Exceptions.server("exception-in-rdbms-restore").withCause(e).get();
        } finally {
            pageWritePool.shutdown();
            try {
                pageWritePool.awaitTermination(1, TimeUnit.HOURS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public List<DataPackage> getFiles(FlowContext context, List<DataFile> files) {
        throw new UnsupportedOperationException("Unimplemented method 'RdbmsTarget.getFiles'");
    }

    @Override
    public void deleteFile(String dir, String fileName) {
        throw new UnsupportedOperationException("Unimplemented method 'RdbmsTarget.deleteFile'");
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public void fillDetails(Map<String, Object> props) {
        props.put("type", RdbmsTarget.class.getSimpleName());
        props.put("datasource", datasourceFactory.getName());
    }

    public class RdmsRestoreTask implements Callable<Boolean> {
        @Getter
        private DbTable table;
        private BackupArchive archive;
        private FlowContext context;
        private Set<ReferenceInfo> circularIgnore;

        public RdmsRestoreTask(DbTable table, BackupArchive archive, FlowContext context,
                Set<ReferenceInfo> circularIgnore) {
            this.table = table;
            this.archive = archive;
            this.context = context;
            this.circularIgnore = circularIgnore != null ? circularIgnore : Set.of();
        }

        @Override
        public Boolean call() {
            CompletableFuture<Object> future = taskRegistry.get(table.qualifiedName());
            try {
                List<ReferenceInfo> restoreDeps = table.getReferences().stream()
                    .filter(r -> !table.qualifiedName().equals(r.refQualifiedName()))
                    .filter(r -> !circularIgnore.contains(r))
                    .toList();
                context.logStepInfo(context.getCurrentStep(),
                    LogMessage.info("{} will wait for [{}] {}", table.qualifiedName(), restoreDeps.size(),
                        restoreDeps.stream().map(ReferenceInfo::refQualifiedName).toList()));

                for (var tr : restoreDeps) {
                    context.logStepInfo(context.getCurrentStep(),
                        LogMessage.info("{} waiting for {}", table.qualifiedName(), tr.refQualifiedName()));
                    boolean dependency = false;
                    while (!dependency) {
                        try {
                            dependency = (Boolean) taskRegistry.get(tr.refQualifiedName()).get(1, TimeUnit.SECONDS);
                            if (!dependency || globalCancellationFlag.get()) {
                                future.complete(false);
                                return false;
                            }
                        } catch (TimeoutException e) {
                            if (globalCancellationFlag.get()) {
                                future.complete(false);
                                return false;
                            }
                        }
                    }
                }

                String metaEntry = CommonHelpers.tableMetaEntryName(table.qualifiedName());
                if (!archive.exists(metaEntry)) {
                    context.logStepError(context.getCurrentStep(), "unable to find table meta " + metaEntry);
                    future.complete(false);
                    return false;
                }

                TableDataMeta meta;
                try (InputStream metaIn = archive.open(metaEntry)) {
                    meta = objectMapper.readValue(metaIn, TableDataMeta.class);
                }
                List<String> pageEntries = archive.listPageEntries(table.qualifiedName());
                context.logStepInfo(context.getCurrentStep(),
                    LogMessage.info("restoring {} pages for {} (meta totalPages={})",
                        pageEntries.size(), table.qualifiedName(), meta.getTotalPages()));

                List<Future<Boolean>> pageFutures = new ArrayList<>();
                for (String pageEntry : pageEntries) {
                    if (globalCancellationFlag.get()) {
                        future.complete(false);
                        return false;
                    }
                    inFlightPages.acquire();
                    DataPage dataPage;
                    try (InputStream pageIn = archive.open(pageEntry)) {
                        dataPage = objectMapper.readValue(pageIn, DataPage.class);
                    } catch (Exception e) {
                        inFlightPages.release();
                        throw e;
                    }
                    int totalPages = meta.getTotalPages() != null ? meta.getTotalPages() : pageEntries.size();
                    PageRestoreTask pageTask = new PageRestoreTask(table, dataPage, totalPages, context);
                    pageFutures.add(pageWritePool.submit(() -> {
                        try {
                            return pageTask.call();
                        } finally {
                            inFlightPages.release();
                        }
                    }));
                }

                boolean allSucceeded = true;
                for (Future<Boolean> pf : pageFutures) {
                    allSucceeded = pf.get() && allSucceeded;
                }
                future.complete(allSucceeded);
                return allSucceeded;
            } catch (Exception e) {
                context.logStepError(context.getCurrentStep(), "failed to process " + table.getName(), e);
                firstFailure.compareAndSet(null, e);
                globalCancellationFlag.set(true);
                future.complete(false);
                return false;
            }
        }
    }

    public class PageRestoreTask implements Callable<Boolean> {
        @Getter
        private DbTable table;
        @Getter
        private DataPage dataPage;
        @Getter
        private int totalPages;
        private FlowContext context;

        public PageRestoreTask(DbTable table, DataPage dataPage, int totalPages, FlowContext context) {
            this.table = table;
            this.dataPage = dataPage;
            this.totalPages = totalPages;
            this.context = context;
        }

        @Override
        public Boolean call() {
            if (globalCancellationFlag.get()) {
                context.logStepInfo(context.getCurrentStep(),
                    LogMessage.info("Page restore task for table {} cancelled before execution", table.getName()));
                return false;
            }
            try (DMLService dmlService = datasourceFactory.dmlService()) {
                context.logStepInfo(context.getCurrentStep(),
                    LogMessage.info("restoring page {} of {} with {} records for {}",
                        dataPage.getPageNum(), totalPages, dataPage.getSize(), table.getName()));
                dmlService.writePageData(table, dataPage);
                context.logStepInfo(context.getCurrentStep(),
                    LogMessage.info("restored page {} of {} records for {}",
                        dataPage.getPageNum(), dataPage.getSize(), table.getName()));
                if (globalCancellationFlag.get()) {
                    return false;
                }
            } catch (Exception e) {
                context.logStepError(context.getCurrentStep(),
                    "Failed to restore page for table " + table.getName() + ": " + e.getMessage(), e);
                firstFailure.compareAndSet(null, e);
                globalCancellationFlag.set(true);
                return false;
            }
            return true;
        }
    }
}
