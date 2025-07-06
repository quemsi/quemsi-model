package com.quemsi.model.flow.in;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.commons.util.FileResource;
import com.quemsi.model.flow.DataPackageFileResource;
import com.quemsi.model.flow.FlowContext;
import com.quemsi.model.flow.db.DataSourceFactory;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.DbTable;
import com.quemsi.model.flow.in.TableDataPage.Request;
import com.quemsi.model.service.TableDataPersister;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RdbmsBackup implements Source{
    @Autowired
    @Setter
    private ObjectMapper objectMapper;
    @Setter
	private DataSourceFactory datasource;
    @Setter
    private String format = "json";
    @Setter
    private int batchSize = 100;
    @Setter
    private int parallelism;
    private Map<DbTable, CompletableFuture<Object>> taskRegistry = new HashMap<>();
	
    @Override
    public void execute(FlowContext context) {
        try(ForkJoinPool pool = new ForkJoinPool(parallelism)) {
            DbModel dbModel = datasource.getDbModel();
            dbModel.setFormat(format);
            String dbModelJson = objectMapper.writeValueAsString(dbModel);
            FileResource modelFile = new FileResource();
            modelFile.setName("db-model.json");
            modelFile.setContentType(MediaType.APPLICATION_JSON_VALUE);
            byte[] bytes = dbModelJson.getBytes();
            modelFile.setInputStream(new ByteArrayInputStream(bytes));
            modelFile.setSize(bytes.length);
            
            context.getDataPackages().add(new DataPackageFileResource(modelFile));

            TableDataPersister tableDataPersister = new TableDataPersister();
            tableDataPersister.setObjectMapper(objectMapper);

            List<DbTable> tables = dbModel.sortedTableList();
            tables.stream().map(table -> new RdmsBackupTask(table, tableDataPersister)).map(t -> {
                taskRegistry.put(t.getTable(), new CompletableFuture<>());
                ForkJoinTask<Boolean> task = pool.submit(t);
                return task;
            })
            .toList();
            CompletableFuture.allOf(taskRegistry.values().toArray(new CompletableFuture[taskRegistry.size()])).get();
            context.getDataPackages().addAll(tableDataPersister.getDataPackages());
            log.info("{} data packages created", context.getDataPackages().size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            throw Exceptions.server("failed-to-execute").withCause(e).get();
        } catch (JsonProcessingException e) {
            throw Exceptions.server("json-serialization-error").withCause(e).get();
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
            CompletableFuture<Object> future = taskRegistry.get(table);
            log.info("{} will wait for [{}] {}", table.getName(), table.getReferences().size(), table.getReferences().stream().map(t -> t.getName()).toList());
            table.getReferences().stream().forEach(tr -> {
                log.info("{} waiting     for {}", table.getName(), tr.getName());
                taskRegistry.get(tr).join();
                log.info("future of {} completed for {}", tr.getName(), table.getName());
            });
            log.info("{} done waiting", table.getName());
            Request request = new Request();
            request.setPageNum(0);
            request.setPageSize(batchSize);
            request.setTable(table);
            TableDataPage dataPage = null;
            AtomicInteger counter = new AtomicInteger(0);
            while(dataPage == null || dataPage.isHasMorePage()){
                dataPage = datasource.getTableDataPage(request);
                counter.incrementAndGet();
                tableDataPersister.persist(dataPage);
                request.setPageNum(request.getPageNum() + 1);
            }
            log.info("{} pages are completed for {}", counter.get(), table.getName());
            future.complete(table);
            return false;
        }
    }
}
