package com.biddflux.model.flow.db.mysql;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;

import com.biddflux.EnvironmentVars;
import com.biddflux.commons.util.Exceptions;
import com.biddflux.model.flow.AbstractStep;
import com.biddflux.model.flow.Flow;
import com.biddflux.model.flow.FlowContext;
import com.biddflux.model.flow.db.DataSourceFactory;
import com.biddflux.model.flow.db.sql.SqlParser;
import com.biddflux.model.flow.db.sql.SqlToken;

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
	@Autowired
    private SqlParser sqlParser;


    @Override
	public void execute(FlowContext context) {
		try (Connection conn = datasourceFactory.getDataSource().getConnection()){
            List<SqlToken> tokens = sqlParser.split(script);
            Statement statement = conn.createStatement();
            tokens.forEach(Exceptions.wrapEx(st -> { statement.addBatch(st.getValue());}));
            int[] results = statement.executeBatch();
            String resultsStr = Arrays.stream(results).mapToObj(Integer::toString).collect(Collectors.joining(", "));
            log.info("executeUpdate results {}", resultsStr);
        } catch (Exception e) {
            throw Exceptions.server("script-exception").withExtra("datasource", datasourceFactory.getName()).withCause(e).get();
        }
        executeNext(context);
	}
	
	@Override
	public void init(Flow f, EnvironmentVars env) {
		super.init(f, env);
		super.initNext(f, env);
	}
	
	@Override
	public void fillDetails(List<Map<String, Object>> steps) {
		Map<String, Object> props = new HashMap<>();
		props.put("datasource", datasourceFactory.getName());
        props.put("script", script);
		props.put("type", MySqlScript.class.getSimpleName());
		steps.add(props);
		super.fillDetails(steps);
	}
}
