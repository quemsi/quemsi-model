package com.quemsi.model.flow.db.mysql;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.flow.AbstractStep;
import com.quemsi.model.flow.FlowContext;
import com.quemsi.model.flow.db.DataSourceFactory;
import com.quemsi.model.flow.db.sql.SqlParser;
import com.quemsi.model.flow.db.sql.SqlToken;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Data
@EqualsAndHashCode(callSuper = false)
@Slf4j
public class MySqlScript extends AbstractStep{
	@Setter
	private DataSourceFactory datasourceFactory;
	@Setter
    private String script;
	@Setter
    private SqlParser sqlParser;


    @Override
	public void execute(FlowContext context) {
		datasourceFactory.assertWritable();
		try (Connection conn = datasourceFactory.getDataSource().getConnection()){
            List<SqlToken> tokens = sqlParser.split(script);
            Statement statement = conn.createStatement();
            tokens.forEach(Exceptions.wrapConsumer(st -> { statement.addBatch(st.getValue());}));
            int[] results = statement.executeBatch();
            String resultsStr = Arrays.stream(results).mapToObj(Integer::toString).collect(Collectors.joining(", "));
            log.info("executeUpdate results {}", resultsStr);
        } catch (Exception e) {
            throw Exceptions.server("script-exception").withExtra("datasource", datasourceFactory.getName()).withCause(e).get();
        }
	}
	
	@Override
	public void fillDetails(List<Map<String, Object>> steps) {
		Map<String, Object> props = new HashMap<>();
		props.put("datasource", datasourceFactory.getName());
        props.put("script", script);
		props.put("type", MySqlScript.class.getSimpleName());
		steps.add(props);
	}
}
