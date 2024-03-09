package com.biddflux.model.flow.db.mysql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.biddflux.model.flow.AbstractStep;
import com.biddflux.model.flow.Flow;
import com.biddflux.model.flow.FlowContext;
import com.biddflux.model.flow.db.DataSourceFactory;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Setter;

@Data
@EqualsAndHashCode(callSuper = false)
public class StartReplica extends AbstractStep{
	private static final String SQL_START_REPLICA = "START REPLICA;";
	@Setter
	private DataSourceFactory datasource;
	
	@Override
	public void execute(FlowContext context) {
		if(!context.inError()) {
			try(Connection conn = datasource.getDataSource().getConnection();
					PreparedStatement ps = conn.prepareStatement(SQL_START_REPLICA);){
				ps.executeUpdate();
				executeNext(context);
			} catch(Exception e) {
				context.logError("unable to start replica", e);
			}
		}
	}
	
	@Override
	public void init(Flow f) {
		super.init(f);
		super.initNext(f);
	}
	
	@Override
	public void fillDetails(List<Map<String, Object>> steps) {
		Map<String, Object> props = new HashMap<>();
		props.put("datasource", datasource.getName());
		props.put("type", StartReplica.class.getSimpleName());
		steps.add(props);
		super.fillDetails(steps);
	}
}
