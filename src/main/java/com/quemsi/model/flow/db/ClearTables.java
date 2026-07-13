package com.quemsi.model.flow.db;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.quemsi.commons.util.CommonOps;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.flow.AbstractStep;
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
        datasource.assertWritable();
        try(
            DMLService dmlService = datasource.dmlService();
            DDLService ddlService = datasource.ddlService();
            ){
            DbModel dbModel = datasource.getDbModel();
            if(all){
                tables = CommonOps.reverse(dbModel.orderedTableNames());
            }
            if(tables != null && !tables.isEmpty()){
                if(dbModel.getCircularIgnore() != null && !dbModel.getCircularIgnore().isEmpty()){
					ddlService.disableConstraints(dbModel.getCircularIgnore());
				}
				dmlService.clearTables(tables.toArray(new String[tables.size()]));
				if(dbModel.getCircularIgnore() != null && !dbModel.getCircularIgnore().isEmpty()){
					ddlService.enableContraints(dbModel.getCircularIgnore());
				}
            }
        }catch(Exception e) {
			throw Exceptions.server("error-clearing-tables").withCause(e).get();
		}
    }

    @Override
    public void fillDetails(List<Map<String, Object>> steps) {
        Map<String, Object> props = new HashMap<>();
        props.put("type", ClearTables.class.getSimpleName());
        props.put("datasource", datasource.getName());
        props.put("all", all);
        props.put("tables", tables);
        steps.add(props);
    }
}
