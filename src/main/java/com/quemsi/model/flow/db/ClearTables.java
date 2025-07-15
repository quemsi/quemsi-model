package com.quemsi.model.flow.db;

import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.LinkedList;

import com.quemsi.commons.util.CommonOps;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.dto.FlowExecution.FlowExecutionStep;
import com.quemsi.model.dto.FlowExecutionStatus;
import com.quemsi.model.flow.AbstractStep;
import com.quemsi.model.flow.Flow;
import com.quemsi.model.flow.FlowContext;
import com.quemsi.model.flow.db.sql.DbModel;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ClearTables extends AbstractStep {
    @Setter
	private DataSourceFactory datasource;
    @Setter
    private boolean all;
	@Setter
	private LinkedList<String> tables;
	
    @Override
    public void execute(FlowContext context) {
        FlowExecutionStep fes = null;
        try(
            Connection conn = datasource.getDataSource().getConnection();
            DMLService dmlService = datasource.dmlService(conn);
            DDLService ddlService = datasource.ddlService(conn);
            ){
            fes = flow.sendStepStarted(context.getExecution().getId(), ClearTables.class.getSimpleName(), this.ord , LocalDateTime.now());
            DbModel dbModel = datasource.getDbModel();
            if(all){
                tables = CommonOps.reverse(dbModel.orderedTableNames());
            }
            if(tables != null && !tables.isEmpty()){
                if(dbModel.getCircularIgnore() != null && !dbModel.getCircularIgnore().isEmpty()){
					ddlService.disableConstraints(dbModel.getCircularIgnore());
				}
				dmlService.clearTables(tables.toArray(new String[tables.size()]));   
            }
            flow.sendStepFinished(fes, FlowExecutionStatus.SUCCESS);
        }catch(Exception e) {
			context.logError(fes, "error in ClearTables step", e);
			flow.sendStepFinished(fes, FlowExecutionStatus.FAILED);
			throw Exceptions.server("error-clearing-tables").withCause(e).get();
		}
		executeNext(context);
    }

    @Override
	public void init(Flow f) {
		super.init(f);
		super.initNext(f);
	}
}
