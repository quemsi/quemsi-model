package com.biddflux.model.flow.out;

import java.io.IOException;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;

import com.biddflux.commons.util.Exceptions;
import com.biddflux.model.dto.DataType;
import com.biddflux.model.flow.DataPackage;
import com.biddflux.model.flow.Flow;
import com.biddflux.model.flow.db.DataSourceFactory;
import com.biddflux.model.flow.db.sql.SqlParser;
import com.biddflux.model.flow.db.sql.SqlToken;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MySqlDb extends AbstractStorage{
    @Setter
    private DataSourceFactory datasourceFactory;
    @Autowired
    private SqlParser sqlParser;

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
            if(!"text/sql".equals(dataPackages.get(0).getContentType())){
                throw Exceptions.badRequest("unsupported-content-type").withExtra("contentType", dataPackages.get(0).getContentType())
                    .withExtra("supported", "text/sql").get();
            }
        }
        dataPackages.forEach(dp -> {
            try (Connection conn = datasourceFactory.getDataSource().getConnection()){
                List<SqlToken> tokens = sqlParser.split(dp.getInputStream());
                Statement statement = conn.createStatement();
                tokens.forEach(Exceptions.wrapConsumer(st -> { statement.addBatch(st.getValue());}));
                int[] results = statement.executeBatch();
                String resultsStr = Arrays.stream(results).mapToObj(Integer::toString).collect(Collectors.joining(", "));
                log.info("executeUpdate results {}", resultsStr);
            } catch (Exception e) {
                throw Exceptions.server("io-exception").withExtra("datapackageName", dp.getName()).withCause(e).get();
            }
        });
    }

    @Override
    public List<DataPackage> getDataPackage(String dataName, DataType type, Long version) throws IOException {
        throw new UnsupportedOperationException("Unimplemented method 'MySqlDb.getDataPackage'");
    }
    
    @Override
    public void deleteFile(String dir, String fileName) throws IOException {
        throw new UnsupportedOperationException("Unimplemented method 'MySqlDb.deleteFile'");
    }
    
    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public void fillDetails(Map<String, Object> props) {
        props.put("type", MySqlDb.class.getSimpleName());
		props.put("datasource", datasourceFactory.getName());
    }

}
