package com.quemsi.model.flow.db;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.quemsi.commons.util.CommonOps;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.dto.DatasourceType;
import com.quemsi.model.flow.AbstractStep;
import com.quemsi.model.flow.FlowContext;
import com.quemsi.model.flow.db.sql.DbDomainType;
import com.quemsi.model.flow.db.sql.DbEnumType;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbSequence;
import com.quemsi.model.flow.db.sql.DbView;
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
	private DataSourceFactory datasource;
	@Setter
    private boolean all;
	@Setter
	private LinkedList<String> tables;
	@Setter
	private LinkedList<String> sequences;
	@Setter
    private SqlParser sqlParser;


    @Override
	public void execute(FlowContext context) {
		datasource.assertWritable();
		try (DDLService ddlService = datasource.ddlService()){
            DbModel dbModel = datasource.getDbModel(msg -> context.logStep(context.getCurrentStep(), msg));
			if(all){
				tables = CommonOps.reverse(dbModel.orderedTableNames());
				sequences = dbModel.getSequences().stream().map(DbSequence::qualifiedName).collect(Collectors.toCollection(LinkedList::new));
				LinkedList<String> views = dbModel.orderedViews().stream()
					.map(DbView::qualifiedName)
					.collect(Collectors.toCollection(LinkedList::new));
				if (!views.isEmpty()) {
					ddlService.dropViews(CommonOps.reverse(views).toArray(new String[0]));
				}
			}
			if(tables != null && !tables.isEmpty()){
				/* MySQL/Postgres/Oracle drop with FK checks off or CASCADE — per-FK ALTERs are redundant.
				 * SQL Server still needs DROP CONSTRAINT before DROP TABLE. */
				DatasourceType type = datasource.type();
				if (DatasourceType.SQLSERVER.equals(type)) {
					if(dbModel.getReferenceInfos() != null && !dbModel.getReferenceInfos().isEmpty()){
						ddlService.disableConstraints(new HashSet<>(dbModel.getReferenceInfos()));
					} else if(dbModel.getCircularIgnore() != null && !dbModel.getCircularIgnore().isEmpty()){
						ddlService.disableConstraints(dbModel.getCircularIgnore());
					}
				}
				ddlService.dropTables(tables.toArray(new String[tables.size()]));
            }
			if(sequences != null && !sequences.isEmpty()){
				ddlService.dropSequences(sequences.toArray(new String[sequences.size()]));
			}
			if (all) {
				if (dbModel.getDomainTypes() != null && !dbModel.getDomainTypes().isEmpty()) {
					ddlService.dropDomains(dbModel.getDomainTypes().stream()
						.map(DbDomainType::qualifiedName)
						.toArray(String[]::new));
				}
				if (dbModel.getEnumTypes() != null && !dbModel.getEnumTypes().isEmpty()) {
					ddlService.dropEnumTypes(dbModel.getEnumTypes().stream()
						.map(DbEnumType::qualifiedName)
						.toArray(String[]::new));
				}
			}
        } catch (Exception e) {
			throw Exceptions.server("script-exception").withExtra("datasource", datasource.getName()).withCause(e).get();
        }
	}
	
	@Override
	public void fillDetails(List<Map<String, Object>> steps) {
		Map<String, Object> props = new HashMap<>();
		props.put("datasource", datasource.getName());
        props.put("all", all);
		props.put("type", DropTables.class.getSimpleName());
		steps.add(props);
	}
}

