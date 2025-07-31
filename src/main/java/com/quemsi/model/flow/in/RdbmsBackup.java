package com.quemsi.model.flow.in;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.atomic.AtomicInteger;

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
	
    @Override
    public void execute(FlowContext context) {
        try(ForkJoinPool pool = new ForkJoinPool(parallelism)) {
            DbModel dbModel = datasource.getDbModel();
            dbModel.setFormat(format);
            String dbModelJson = dataMapper.writeValueAsString(dbModel);
            FileResource modelFile = new FileResource();
            modelFile.setName("db-model.json");
            modelFile.setContentType(MediaType.APPLICATION_JSON_VALUE);
            byte[] bytes = dbModelJson.getBytes();
            modelFile.setInputStream(new ByteArrayInputStream(bytes));
            modelFile.setSize(bytes.length);
            
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
                context.logError("Backup failed", Exceptions.server("backup-failed").get());
            }else{
                context.getDataPackages().addAll(tableDataPersister.getDataPackages());
                log.info("{} data packages created", context.getDataPackages().size());
            }
        } catch (BaseRuntimeException e) {
            context.logError("error-in-backup", e);
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
            ForkJoinTask<Boolean> future = taskRegistry.get(table.getName());
            try(DMLService dmlService = datasource.dmlService()){
                log.info("{} will wait for [{}] {}", table.getName(), table.getReferences().size(), table.getReferences().stream().map(t -> t.getName()).toList());
                for(TableReference tr : table.getReferences()){
                    if(!tr.getName().equals(table.getName())){
                        boolean dependency = taskRegistry.get(tr.getName()).join();
                        log.info("future of {} completed for {} result {}", tr.getName(), table.getName(), dependency);
                        if(!dependency){
                            log.error("failed to backup dependency {} for {}", tr.getName(), table.getName());
                            return false;
                        }
                    }
                }
                log.info("{} done waiting", table.getName());
                Request request = new Request();
                request.setPageNum(0);
                request.setPageSize(batchSize);
                request.setTable(table);
                TableDataPage dataPage = null;
                AtomicInteger counter = new AtomicInteger(0);
                while(dataPage == null || dataPage.isHasMorePage()){
                    dataPage = dmlService.getTableDataPage(request);
                    counter.incrementAndGet();
                    tableDataPersister.persist(dataPage);
                    request = request.toBuilder().pageNum(request.getPageNum() + 1).build();
                }
                log.info("{} pages are completed for {}", counter.get(), table.getName());
                return true;
            }catch(Exception e){
                log.error("ignored for " + table.getName(), e);
                future.completeExceptionally(e);
            }
            return false;
        }
    }
}
