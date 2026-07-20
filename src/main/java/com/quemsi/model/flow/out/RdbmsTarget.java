package com.quemsi.model.flow.out;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;

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
import com.quemsi.model.flow.in.TableData;
import com.quemsi.model.util.CommonConstants;
import com.quemsi.model.util.CommonHelpers;

import lombok.Getter;
import lombok.Setter;

public class RdbmsTarget extends AbstractStorage{
    @Setter
    private DataSourceFactory datasourceFactory;
    @Setter
    private ObjectMapper objectMapper;
    @Setter
    private int parallelism;
    private Map<String, CompletableFuture<Object>> taskRegistry = new HashMap<>();
    private AtomicBoolean globalCancellationFlag = new AtomicBoolean(false);
    private AtomicReference<Exception> firstFailure = new AtomicReference<>();

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

    @Override
    public void store(FlowContext context, String dataName, List<DataPackage> dataPackages, Long version) {
        if(!dataPackages.isEmpty()){
            datasourceFactory.assertWritable();
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
                DDLService ddlService = datasourceFactory.ddlService();
                ){
                String dbModelJsonStr = IOUtils.toString(namedPackages.get(CommonConstants.DB_MODEL_FILE_NAME).getInputStream(), Charset.forName("UTF-8"));
                DbModel dbModel = objectMapper.readValue(dbModelJsonStr, DbModel.class);
                
                if(!datasourceFactory.type().equals(DatasourceType.valueOf(dbModel.getSourceType()))){
                    throw Exceptions.badRequest("unsupported-source-type-for-rdbms-target").withExtra("sourceType", dbModel.getSourceType()).withExtra("targetType", datasourceFactory.getName()).get();
                }

                context.getDbModelProcessors().forEach(p -> p.process(dbModel));

                if (DatasourceType.POSTGRES.name().equals(dbModel.getSourceType())) {
                    PostgresEnumSupport.ensureEnumTypes(dbModel, namedPackages, objectMapper);
                }

                /* createTables omits all FKs; enableContraints adds them after data load */
                context.logStepInfo(context.getCurrentStep(), LogMessage.info("schema will be created with {} tables", dbModel.getTables().size()));
                ddlService.createTables(dbModel);
                context.logStepInfo(context.getCurrentStep(), LogMessage.info("schema is created with {} tables", dbModel.getTables().size()));

                List<ForkJoinTask<Boolean>> taskList = dbModel.orderedTables().stream().map(table -> new RdmsRestoreTask(table, namedPackages, pool, context, dbModel.getCircularIgnore()))
                    .map(t -> {
                        taskRegistry.put(t.getTable().qualifiedName(), new CompletableFuture<>());
                        ForkJoinTask<Boolean> task = pool.submit(t);
                        return task;
                    }).toList();
                boolean result = taskList.stream().map(Exceptions.wrapFunction(t -> t.get())).reduce(Boolean.valueOf(true), (a, n) -> a && n);
                Set<ReferenceInfo> allFks = new LinkedHashSet<>();
                if (dbModel.getReferenceInfos() != null) {
                    allFks.addAll(dbModel.getReferenceInfos());
                }
                if (dbModel.getCircularIgnore() != null) {
                    allFks.addAll(dbModel.getCircularIgnore());
                }
                ddlService.enableContraints(allFks);

                if(result){
                    ddlService.createFunctions(dbModel);
                    ddlService.createViews(dbModel);
                    ddlService.createTriggers(dbModel);
                    context.logStepInfo(context.getCurrentStep(), LogMessage.info("all data is restored successfully"));
                } else {
                    Exception failure = firstFailure.get();
                    String errorMessage = failure != null ? 
                        "Restore failed due to: " + failure.getMessage() : 
                        "Restore failed - one or more restore table tasks failed";
                    context.logStepError(context.getCurrentStep(), errorMessage);
                    throw Exceptions.server("restore-failed").withExtra("errorMessage", errorMessage).withCause(failure).get();
                }
            } catch(IOException e){
                throw Exceptions.server("io-exception-in-rdbms-restore").withCause(e).get();
            } catch(Exception e){
                throw Exceptions.server("exception-in-rdbms-restore").withCause(e).get();
            }
        }
        
    }

    @Override
    public List<DataPackage> getFiles(FlowContext context, List<DataFile> files) throws IOException {
        throw new UnsupportedOperationException("Unimplemented method 'RdbmsTarget.getFiles'");
    }

    @Override
    public void deleteFile(String dir, String fileName) throws IOException {
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


    public class RdmsRestoreTask implements Callable<Boolean>{
        @Getter
        private DbTable table;
        private Map<String, DataPackage> namedPackages;
        private ForkJoinPool forkJoinPool;
        private FlowContext context;
        private Set<ReferenceInfo> circularIgnore;

        public RdmsRestoreTask(DbTable table, Map<String, DataPackage> namedPackages, ForkJoinPool forkJoinPool, FlowContext context, Set<ReferenceInfo> circularIgnore){
            this.table = table;
            this.namedPackages = namedPackages;
            this.forkJoinPool = forkJoinPool;
            this.context = context;
            this.circularIgnore = circularIgnore != null ? circularIgnore : Set.of();
        }

        @Override
        public Boolean call() throws Exception {
            try{
                CompletableFuture<Object> future = taskRegistry.get(table.qualifiedName());
                List<ReferenceInfo> restoreDeps = table.getReferences().stream()
                    .filter(r -> !table.qualifiedName().equals(r.refQualifiedName()))
                    .filter(r -> !circularIgnore.contains(r))
                    .toList();
                context.logStepInfo(context.getCurrentStep(), LogMessage.info("{} will wait for [{}] {}", table.qualifiedName(), restoreDeps.size(), restoreDeps.stream().map(t -> t.refQualifiedName()).toList()));
                String fileName = CommonHelpers.dataFileName(table.qualifiedName());
                if(!namedPackages.containsKey(fileName)){
                    context.logStepError(context.getCurrentStep(), "unable to find data file " + fileName);
                    return false;
                }
                
                /* Wait for dependencies with timeout and cancellation support */
                for(var tr : restoreDeps) {
                    context.logStepInfo(context.getCurrentStep(), LogMessage.info("{} waiting for {}", table.qualifiedName(), tr.refQualifiedName()));
                    boolean dependency = false;
                    while(!dependency){
                        try{
                            dependency = (Boolean) taskRegistry.get(tr.refQualifiedName()).get(1, TimeUnit.SECONDS);
                            context.logStepInfo(context.getCurrentStep(), LogMessage.info("future of {} completed for {} result {}", tr.refQualifiedName(), table.qualifiedName(), dependency));
                            if(!dependency || globalCancellationFlag.get()){
                                return false;
                            }
                        }catch(TimeoutException e){
                            if(globalCancellationFlag.get()){
                                return false;
                            }
                        }
                    }
                }
                context.logStepInfo(context.getCurrentStep(), LogMessage.info("all dependencies are processed for {}", table.getName()));
                
                String tableDataStr = IOUtils.toString(namedPackages.get(fileName).getInputStream(), Charset.forName("UTF-8"));
                TableData tableData = objectMapper.readValue(tableDataStr, TableData.class);
                context.logStepInfo(context.getCurrentStep(), LogMessage.info("there are {} pages for {}", tableData.getDataPages().size(), tableData.getTableName()));
                
                List<ForkJoinTask<Boolean>> pageTaskList = tableData.getDataPages().stream().map(dataPage -> new PageRestoreTask(table, dataPage, context))
                    .map(t -> forkJoinPool.submit(t)).toList();
                boolean allSucceded = pageTaskList.stream().map(Exceptions.wrapFunction(t -> t.get())).reduce(Boolean.valueOf(true), (b, n) -> b && n);
                future.complete(allSucceded);
                return allSucceded;
            }catch(Exception e){
                context.logStepError(context.getCurrentStep(), "failed to process " + table.getName(), e);
                firstFailure.compareAndSet(null, e);
                globalCancellationFlag.set(true);
            }
            return false;
        }
    }
    public class PageRestoreTask implements Callable<Boolean>{
        @Getter
        private DbTable table;
        @Getter
        private TableData.DataPage dataPage;
        private FlowContext context;
        
        public PageRestoreTask(DbTable table, TableData.DataPage dataPage, FlowContext context){
            this.table = table;
            this.dataPage = dataPage;
            this.context = context;
        }

        @Override
        public Boolean call() throws Exception {
            /* Check for global cancellation before starting */
            if (globalCancellationFlag.get()) {
                context.logStepInfo(context.getCurrentStep(), LogMessage.info("Page restore task for table {} cancelled before execution", table.getName()));
                return false;
            }
            
            try(DMLService dmlService = datasourceFactory.dmlService()){
                context.logStepInfo(context.getCurrentStep(), LogMessage.info("restoring page {} of {} records for {}", dataPage.getPageNum(), dataPage.getSize(), table.getName()));
                dmlService.writePageData(table, dataPage);
                context.logStepInfo(context.getCurrentStep(), LogMessage.info("restored page {} of {} records for {}", dataPage.getPageNum(), dataPage.getSize(), table.getName()));
                /* Check for global cancellation after processing */
                if (globalCancellationFlag.get()) {
                    context.logStepInfo(context.getCurrentStep(), LogMessage.info("Page restore task for table {} cancelled after processing", table.getName()));
                    return false;
                }
            } catch(Exception e) {
                context.logStepError(context.getCurrentStep(), "Failed to restore page for table " + table.getName() + ": " + e.getMessage(), e);
                firstFailure.compareAndSet(null, e);
                globalCancellationFlag.set(true);
                return false;
            }
            return true;
        }
    }
}
