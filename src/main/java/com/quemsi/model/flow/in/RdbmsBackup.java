package com.quemsi.model.flow.in;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quemsi.commons.util.BaseRuntimeException;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.commons.util.LogMessage;
import com.quemsi.model.dto.DataVersion;
import com.quemsi.model.dto.Tag;
import com.quemsi.model.flow.FlowContext;
import com.quemsi.model.flow.db.DMLService;
import com.quemsi.model.flow.db.DataSourceFactory;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.ReferenceInfo;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.file.StagingBackupWriter;
import com.quemsi.model.flow.in.TableDataPage.Request;
import com.quemsi.model.flow.subset.SubsetConfig;
import com.quemsi.model.flow.subset.SubsetPlan;
import com.quemsi.model.flow.subset.SubsetPlanner;
import com.quemsi.model.util.QuemsiTemp;

import lombok.Getter;
import lombok.Setter;

public class RdbmsBackup implements Source {
    @Autowired
    @Setter
    private ObjectMapper dataMapper;
    @Setter
    private DataSourceFactory datasource;
    @Setter
    private String format = "json";
    @Setter
    private int batchSize = 10_000;
    @Setter
    private int parallelism;
    @Setter
    @Getter
    private SubsetConfig subset;
    private volatile SubsetPlan activeSubsetPlan;
    private Map<String, ForkJoinTask<Boolean>> taskRegistry = new HashMap<>();
    private AtomicBoolean globalCancellationFlag = new AtomicBoolean(false);
    private AtomicReference<Exception> firstFailure = new AtomicReference<>();
    private Semaphore inFlightRows;
    private int rowBudget;
    private ExecutorService pagePool;

    /** Row budget = batchSize (page shape) × backup parallelism. */
    public static int deriveRowBudget(int batchSize, int parallelism) {
        return Math.max(1, parallelism) * Math.max(1, batchSize);
    }

    /** Pages to fan out for a table given row count and page size. */
    public static int totalPages(long totalRows, int pageSize) {
        if (totalRows <= 0 || pageSize <= 0) {
            return 0;
        }
        return (int) ((totalRows + pageSize - 1L) / pageSize);
    }

    @Override
    public void execute(FlowContext context) {
        globalCancellationFlag.set(false);
        firstFailure.set(null);
        taskRegistry.clear();
        Path stagingDir = QuemsiTemp.createStagingDir("backup");
        context.setStagingDir(stagingDir);
        context.logStepInfo(context.getCurrentStep(), LogMessage.info("Backup staging dir {}", stagingDir));

        StagingBackupWriter stagingWriter = new StagingBackupWriter(stagingDir);
        stagingWriter.setObjectMapper(dataMapper);

        rowBudget = deriveRowBudget(batchSize, parallelism);
        inFlightRows = new Semaphore(rowBudget);
        pagePool = Executors.newFixedThreadPool(Math.max(1, parallelism));

        try (ForkJoinPool pool = new ForkJoinPool(Math.max(1, parallelism))) {
            context.logStepInfo(context.getCurrentStep(), LogMessage.info("creating db model from datasource"));
            DbModel dbModel = datasource.getDbModel(msg -> context.logStep(context.getCurrentStep(), msg));
            context.logStepInfo(context.getCurrentStep(), LogMessage.info("db model created from datasource"));
            context.recordDatasourceType(datasource.type());
            dbModel.setFormat(format);
            dbModel.setBatchSize(batchSize);
            dbModel.setParallelism(parallelism);
            dbModel.setAgentVersion(context.getDataVersion().getAgentVersion());
            String dbModelJson = dataMapper.writeValueAsString(dbModel);
            stagingWriter.writeDbModel(dbModelJson);
            context.logStepInfo(context.getCurrentStep(), LogMessage.info("Wrote db-model.json to staging"));

            activeSubsetPlan = null;
            if (subset != null && subset.isActive()) {
                context.logStepInfo(context.getCurrentStep(), LogMessage.info("Planning subset backup"));
                try (DMLService dmlService = datasource.dmlService()) {
                    activeSubsetPlan = new SubsetPlanner().plan(dbModel, dmlService, subset);
                }
                for (SubsetPlan.SubsetTableSummary summary : activeSubsetPlan.summaries()) {
                    context.logStepInfo(context.getCurrentStep(), LogMessage.info(
                        "subset table {} count={} driver={} requiredByFk={} requiredBy={}",
                        summary.getTable(), summary.getCount(), summary.getDriverCount(),
                        summary.getRequiredByFkCount(), summary.getRequiredBy()));
                }
            }
            putSubsetTag(context, activeSubsetPlan != null);

            Set<String> ignoreConstraints = dbModel.getCircularIgnore().stream()
                .map(ci -> ci.getConstraintName())
                .collect(Collectors.toSet());
            List<DbTable> tables = dbModel.orderedTables();
            context.logStepInfo(context.getCurrentStep(),
                LogMessage.info("{} tables will be backed up", tables.size()));
            context.logStepInfo(context.getCurrentStep(),
                LogMessage.info("backing up with parallelism={} batchSize={} rowBudget={} subset={}",
                    parallelism, batchSize, rowBudget, activeSubsetPlan != null));

            final SubsetPlan subsetPlan = activeSubsetPlan;
            List<ForkJoinTask<Boolean>> tasks = tables.stream()
                .map(table -> new RdmsBackupTask(table, stagingWriter, context, ignoreConstraints, subsetPlan))
                .map(t -> {
                    ForkJoinTask<Boolean> task = pool.submit(t);
                    taskRegistry.put(t.getTable().getName(), task);
                    return task;
                })
                .toList();

            boolean result = tasks.stream()
                .map(Exceptions.wrapFunction(t -> t.get()))
                .reduce(Boolean.TRUE, (f, s) -> f && s);
            if (!result) {
                throw Exceptions.server("backup-failed").withCause(firstFailure.get()).get();
            }
            context.logStepInfo(context.getCurrentStep(),
                LogMessage.info("Backup staging complete under {}", stagingDir));
        } catch (BaseRuntimeException e) {
            context.clearStagingDirQuietly();
            throw e;
        } catch (JsonProcessingException e) {
            context.clearStagingDirQuietly();
            throw Exceptions.server("json-serialization-error").withCause(e).get();
        } catch (Exception e) {
            context.clearStagingDirQuietly();
            // Flow boundary logs the structured ERROR + stack; avoid a second copy here.
            throw Exceptions.server("error-in-backup")
                .withCause(e)
                .withExtra("flowName", context.getFlow().getName())
                .get();
        } finally {
            if (pagePool != null) {
                pagePool.shutdown();
                try {
                    pagePool.awaitTermination(1, TimeUnit.HOURS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void putSubsetTag(FlowContext context, boolean subsetActive) {
        if (context.getTags() == null) {
            return;
        }
        context.getTags().put(DataVersion.SUBSET_TAG, subsetActive ? "true" : "false");
        if (context.getDataVersion() != null) {
            context.getDataVersion().setTags(context.getTags().entrySet().stream()
                .map(e -> Tag.builder().name(e.getKey()).val(e.getValue()).build())
                .toList());
        }
    }

    @Override
    public void fillDetails(Map<String, Object> steps) {
        steps.put("datasource", this.datasource.getName());
        steps.put("type", RdbmsBackup.class.getSimpleName());
        if (subset != null) {
            steps.put("subset", subset);
        }
    }

    public class RdmsBackupTask implements Callable<Boolean> {
        @Getter
        private DbTable table;
        private StagingBackupWriter stagingWriter;
        private FlowContext context;
        Set<String> ignoreConstraints;
        private SubsetPlan subsetPlan;

        public RdmsBackupTask(DbTable table, StagingBackupWriter stagingWriter, FlowContext context,
                Set<String> ignoreConstraints, SubsetPlan subsetPlan) {
            this.table = table;
            this.stagingWriter = stagingWriter;
            this.context = context;
            this.ignoreConstraints = ignoreConstraints;
            this.subsetPlan = subsetPlan;
        }

        @Override
        public Boolean call() throws Exception {
            try {
                context.logStepInfo(context.getCurrentStep(), LogMessage.info("{} will wait for [{}] {}",
                    table.getName(), table.getReferences().size(),
                    table.getReferences().stream().map(t -> t.getRefTableName()).toList()));
                for (ReferenceInfo tr : table.getReferences()) {
                    if (ignoreConstraints.contains(tr.getConstraintName())) {
                        continue;
                    }
                    if (!tr.refQualifiedName().equals(table.qualifiedName())) {
                        boolean dependency = false;
                        while (!dependency) {
                            try {
                                dependency = taskRegistry.get(tr.getRefTableName()).get(1, TimeUnit.SECONDS);
                                context.logStepInfo(context.getCurrentStep(),
                                    LogMessage.info("future of {} completed for {} result {}",
                                        tr.getRefTableName(), table.getName(), dependency));
                                if (!dependency || globalCancellationFlag.get()) {
                                    return false;
                                }
                            } catch (TimeoutException e) {
                                if (globalCancellationFlag.get()) {
                                    return false;
                                }
                            }
                        }
                    }
                }
                context.logStepInfo(context.getCurrentStep(),
                    LogMessage.info("all dependencies are processed for {}", table.getName()));

                int pageSize;
                long totalRows;
                List<String> orderedKeys = null;
                try (DMLService dmlService = datasource.dmlService()) {
                    pageSize = dmlService.getTablePageSize(batchSize, table);
                    if (subsetPlan != null) {
                        orderedKeys = new ArrayList<>(subsetPlan.keysFor(table.qualifiedName()));
                        totalRows = orderedKeys.size();
                    } else {
                        totalRows = dmlService.countRows(table);
                    }
                }
                int pages = totalPages(totalRows, pageSize);
                context.logStepInfo(context.getCurrentStep(),
                    LogMessage.info("backing up {} with {} pages pageSize={} parallelism={} subsetRows={}",
                        table.getName(), pages, pageSize, parallelism, totalRows));

                if (pages == 0) {
                    stagingWriter.finishTable(table.qualifiedName(), pageSize);
                    context.logStepInfo(context.getCurrentStep(),
                        LogMessage.info("{} empty (0 pages)", table.getName()));
                    return true;
                }

                AtomicLong seqGenerator = new AtomicLong(1);
                List<Future<?>> pageFutures = new ArrayList<>(pages);
                final List<String> keysForPaging = orderedKeys;
                for (int pageNum = 0; pageNum < pages; pageNum++) {
                    if (globalCancellationFlag.get()) {
                        return false;
                    }
                    final int pn = pageNum;
                    int cost = Math.max(1, pageSize);
                    if (cost > rowBudget) {
                        throw Exceptions.badRequest("page-exceeds-max-in-flight-rows")
                            .withExtra("table", table.qualifiedName())
                            .withExtra("pageNum", pn)
                            .withExtra("pageRows", cost)
                            .withExtra("rowBudget", rowBudget)
                            .get();
                    }
                    inFlightRows.acquire(cost);
                    pageFutures.add(pagePool.submit((Callable<Void>) () -> {
                        try {
                            if (globalCancellationFlag.get()) {
                                return null;
                            }
                            try (DMLService dml = datasource.dmlService()) {
                                context.logStepInfo(context.getCurrentStep(),
                                    LogMessage.info("page {} of {} fetch+persist start for {}", pn + 1, pages, table.getName()));
                                Request.RequestBuilder reqBuilder = Request.builder()
                                    .table(table)
                                    .pageNum(pn)
                                    .pageSize(pageSize)
                                    .seqGenerator(seqGenerator);
                                if (keysForPaging != null) {
                                    int from = pn * pageSize;
                                    int to = Math.min(from + pageSize, keysForPaging.size());
                                    reqBuilder.primaryKeys(keysForPaging.subList(from, to));
                                }
                                TableDataPage dataPage = dml.getTableDataPage(reqBuilder.build());
                                stagingWriter.persist(dataPage);
                                int rowCount = dataPage.getDocuments() != null
                                    ? dataPage.getDocuments().size()
                                    : (dataPage.getTableData() != null ? dataPage.getTableData().size() : 0);
                                context.logStepInfo(context.getCurrentStep(),
                                    LogMessage.info("page {} of {} fetch+persist done for {} with {} rows",
                                        pn + 1, pages, table.getName(), rowCount));
                            }
                            return null;
                        } catch (Exception e) {
                            // Short breadcrumb only — Flow logs full structured error once.
                            context.logStepError(context.getCurrentStep(),
                                "failed page " + (pn + 1) + " of " + pages + " for " + table.getName());
                            firstFailure.compareAndSet(null, e);
                            globalCancellationFlag.set(true);
                            throw e;
                        } finally {
                            inFlightRows.release(cost);
                        }
                    }));
                }

                for (Future<?> pf : pageFutures) {
                    pf.get();
                }
                if (globalCancellationFlag.get()) {
                    return false;
                }
                stagingWriter.finishTable(table.qualifiedName(), pageSize);
                context.logStepInfo(context.getCurrentStep(),
                    LogMessage.info("{} pages completed for {}", pages, table.getName()));
                return true;
            } catch (Exception e) {
                context.logStepError(context.getCurrentStep(), "failed process " + table.getName());
                firstFailure.compareAndSet(null, e);
                globalCancellationFlag.set(true);
            }
            return false;
        }
    }
}
