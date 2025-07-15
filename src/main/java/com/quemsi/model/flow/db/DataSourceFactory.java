package com.quemsi.model.flow.db;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import com.quemsi.model.flow.db.sql.DbModel;

public interface DataSourceFactory {
	String PK_VALUES_SEPERATOR = "|-|";

	String getName();
	DataSource getDataSource();
	DbModel getDbModel();
	DDLService ddlService() throws SQLException;
	DDLService ddlService(Connection conn);
	DMLService dmlService() throws SQLException;
	DMLService dmlService(Connection conn);

	/*TableDataPage getTableDataPage(TableDataPage.Request request);
	int writePageData(DbTable table, DataPage dataPage);
	boolean clearTables(String... tableNames);
	boolean dropTables(String...  tableNames);
	void disableConstraints(Set<ReferenceInfo> constraints);
	void enableContraints(Set<ReferenceInfo> constraints);
	void createTables(DbModel dbModel);*/
}
