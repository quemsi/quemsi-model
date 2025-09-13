package com.quemsi.model.flow.in;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quemsi.commons.util.BaseRuntimeException;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.commons.util.FileResource;
import com.quemsi.model.flow.DataPackageFileResource;
import com.quemsi.model.flow.FlowContext;
import com.quemsi.model.flow.db.DMLService;
import com.quemsi.model.flow.db.DataSourceFactory;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.TableReference;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.in.TableDataPage.Request;
import com.quemsi.model.service.TableDataPersister;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RdbmsBackup implements Source{
    @Autowired
    @Setter
    private ObjectMapper dataMapper;
    @Setter
	private DataSourceFactory datasource;
    @Setter
    private String format = "json";
    @Setter
    private int batchSize = 100;
    @Setter
    private int parallelism;
    private Map<String, ForkJoinTask<Boolean>> taskRegistry = new HashMap<>();
    private AtomicBoolean globalCancellationFlag = new AtomicBoolean(false);
    private AtomicReference<Exception> firstFailure = new AtomicReference<>();
	
	
    @Override
    public void execute(FlowContext context) {
        globalCancellationFlag.set(false);
        firstFailure.set(null);
        taskRegistry.clear();
        try(ForkJoinPool pool = new ForkJoinPool(parallelism)) {
            DbModel dbModel = datasource.getDbModel();
            dbModel.setFormat(format);
            String dbModelJson = dataMapper.writeValueAsString(dbModel);
            byte[] bytes = dbModelJson.getBytes();
            FileResource modelFile = FileResource.builder()
                .name("db-model.json")
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .size(Long.valueOf(bytes.length))
                .data(bytes)
                .build();
            
            context.getDataPackages().add(new DataPackageFileResource(modelFile));

            TableDataPersister tableDataPersister = new TableDataPersister();
            tableDataPersister.setObjectMapper(dataMapper);

            List<DbTable> tables = dbModel.orderedTables();
            List<ForkJoinTask<Boolean>> tasks = tables.stream().map(table -> new RdmsBackupTask(table, tableDataPersister)).map(t -> {
                ForkJoinTask<Boolean> task = pool.submit(t);
                taskRegistry.put(t.getTable().getName(), task);
                return task;
            })
            .toList();
            boolean result = tasks.stream().map(Exceptions.wrapFunction(t -> t.get())).reduce(Boolean.TRUE, (f, s) -> f && s);
            if(!result){
                throw Exceptions.server("backup-failed").withCause(firstFailure.get()).get();
            }else{
                context.getDataPackages().addAll(tableDataPersister.getDataPackages());
                log.info("{} data packages created", context.getDataPackages().size());
            }
        } catch (BaseRuntimeException e) {
            throw e;
        } catch (JsonProcessingException e) {
            throw Exceptions.server("json-serialization-error").withCause(e).get();
        } catch(Exception e){
            context.logError("error in backup", e);
            throw Exceptions.server("error-in-backup").withCause(e).withExtra("flowName", context.getFlow().getName()).get();
        }
    }

    @Override
    public void fillDetails(Map<String, Object> steps) {
        steps.put("datasource", this.datasource.getName());
		steps.put("type", RdbmsBackup.class.getSimpleName());
    }

    public class RdmsBackupTask implements Callable<Boolean>{
        @Getter
        private DbTable table;
        private TableDataPersister tableDataPersister;
        public RdmsBackupTask(DbTable table, TableDataPersister tableDataPersister){
            this.table = table;
            this.tableDataPersister = tableDataPersister;
        }
        
        @Override
        public Boolean call() throws Exception {
            try(DMLService dmlService = datasource.dmlService()){
                log.info("{} will wait for [{}] {}", table.getName(), table.getReferences().size(), table.getReferences().stream().map(t -> t.getName()).toList());
                for(TableReference tr : table.getReferences()){
                    if(!tr.getName().equals(table.getName())){
                        boolean dependency = false;
                        while(!dependency){
                            try{
                                dependency = taskRegistry.get(tr.getName()).get(1, TimeUnit.SECONDS);
                                log.info("future of {} completed for {} result {}", tr.getName(), table.getName(), dependency);
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
                }
                log.info("all dependencies are processed for {}", table.getName());
                Request request = new Request();
                request.setPageNum(0);
                request.setPageSize(batchSize);
                request.setTable(table);
                TableDataPage dataPage = null;
                AtomicInteger counter = new AtomicInteger(0);
                while(dataPage == null || dataPage.isHasMorePage()){
                    if(globalCancellationFlag.get()){
                        return false;
                    }
                    dataPage = dmlService.getTableDataPage(request);
                    counter.incrementAndGet();
                    tableDataPersister.persist(dataPage);
                    request = request.toBuilder().pageNum(request.getPageNum() + 1).build();
                }
                log.info("{} pages are completed for {}", counter.get(), table.getName());
                return true;
            }catch(Exception e){
                log.error("failed process " + table.getName(), e);
                firstFailure.compareAndSet(null, e);
                globalCancellationFlag.set(true);
            }
            return false;
        }
    }
}
