package com.quemsi.model.flow.out;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.dto.DataFile;
import com.quemsi.model.dto.DataType;
import com.quemsi.model.flow.DataPackage;
import com.quemsi.model.flow.Flow;
import com.quemsi.model.flow.db.DataSourceFactory;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.in.TableData;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RdbmsTarget extends AbstractStorage{
    public static final String DB_MODEL_FILE_NAME = "db-model.json";
    @Setter
    private DataSourceFactory datasourceFactory;
    @Setter
    private ObjectMapper objectMapper;

    @Override
    public boolean recordFiles() {
        return false;
    }

    @Override
    public void init(Flow f) {
    }

    @Override
    public void store(String dataName, List<DataPackage> dataPackages, Long version) {
        if(!dataPackages.isEmpty()){
            Map<String, DataPackage> namedPackages = dataPackages.stream().collect(Collectors.toMap(dp -> dp.getName(), dp -> dp));
            if(!namedPackages.containsKey(DB_MODEL_FILE_NAME)){
                throw Exceptions.notFound("unable-to-find-db-model").get();
            }
            if(!"application/json".equals(namedPackages.get(DB_MODEL_FILE_NAME).getContentType())){
                throw Exceptions.badRequest("unsupported-content-type-for-db-model").withExtra("contentType", namedPackages.get(DB_MODEL_FILE_NAME).getContentType())
                    .withExtra("supported", "application/json").get();
            }
            try{
                String dbModelJsonStr = IOUtils.toString(namedPackages.get(DB_MODEL_FILE_NAME).getInputStream(), Charset.forName("UTF-8"));
                DbModel dbModel = objectMapper.readValue(dbModelJsonStr, DbModel.class);
                log.info("dbModel {}", dbModel);

                for(String tableName : dbModel.orderedTableNames()){
                    String fileName = "data-" + tableName + ".json";
                    if(!namedPackages.containsKey(fileName)){
                        log.error("unable to find data file {}", fileName);
                    }else{
                        String tableDataStr = IOUtils.toString(namedPackages.get(fileName).getInputStream(), Charset.forName("UTF-8"));
                        TableData tableData = objectMapper.readValue(tableDataStr, TableData.class);
                        log.info("{} pages for {}", tableData.getDataPages().size(), tableData.getTableName());
                        tableData.getDataPages().forEach(dataPage -> {
                            datasourceFactory.writePageData(dbModel.findTable(tableName).orElseThrow(Exceptions.notFound("invalid-table-name-in-restore").withExtra("tableName", tableName).supplier()), dataPage);
                        });
                    }
                }
            }catch(IOException e){
                throw Exceptions.server("io-exception-in-rdbms-restore").withCause(e).get();
            }
        }
        
    }

    @Override
    public List<DataPackage> getDataPackage(String dataName, DataType type, Long version) throws IOException {
        throw new UnsupportedOperationException("Unimplemented method 'RdbmsTarget.getDataPackage'");
    }
    
    @Override
    public List<DataPackage> getFiles(List<DataFile> files) throws IOException {
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
}
