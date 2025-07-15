package com.quemsi.model.flow.db;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.quemsi.commons.util.CommonOps;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.dto.FlowExecution.FlowExecutionStep;
import com.quemsi.model.dto.FlowExecutionStatus;
import com.quemsi.model.flow.AbstractStep;
import com.quemsi.model.flow.Flow;
import com.quemsi.model.flow.FlowContext;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.SqlParser;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Data
@EqualsAndHashCode(callSuper = false)
@Slf4j
public class DropTables extends AbstractStep{
	@Setter
	private DataSourceFactory datasourceFactory;
	@Setter
    private boolean all;
	@Setter
	private Set<String> tables;
	@Setter
    private SqlParser sqlParser;


    @Override
	public void execute(FlowContext context) {
		FlowExecutionStep fes = null;
        try {
            fes = flow.sendStepStarted(context.getExecution().getId(), DropTables.class.getSimpleName(), this.ord , LocalDateTime.now());
            DbModel dbModel = datasourceFactory.getDbModel();
			if(all){
				tables = new HashSet<>(CommonOps.reverse(dbModel.orderedTableNames()));
			}
			if(tables != null && !tables.isEmpty()){
                if(dbModel.getCircularIgnore() != null && !dbModel.getCircularIgnore().isEmpty()){
					datasourceFactory.disableConstraints(dbModel.getCircularIgnore());
				}
				datasourceFactory.dropTables(tables.toArray(new String[tables.size()]));
            }
            flow.sendStepFinished(fes, FlowExecutionStatus.SUCCESS);
        } catch (Exception e) {
            context.logError(fes, "error in ClearTables step", e);
			flow.sendStepFinished(fes, FlowExecutionStatus.FAILED);
			throw Exceptions.server("script-exception").withExtra("datasource", datasourceFactory.getName()).withCause(e).get();
        }
		executeNext(context);
	}
	
	@Override
	public void init(Flow f) {
		super.init(f);
		super.initNext(f);
	}
	
	@Override
	public void fillDetails(List<Map<String, Object>> steps) {
		Map<String, Object> props = new HashMap<>();
		props.put("datasource", datasourceFactory.getName());
        props.put("all", all);
		props.put("type", DropTables.class.getSimpleName());
		steps.add(props);
		super.fillDetails(steps);
	}
}

