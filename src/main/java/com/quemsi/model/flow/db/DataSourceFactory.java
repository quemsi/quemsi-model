package com.quemsi.model.flow.db;

import java.util.Set;

import javax.sql.DataSource;

import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.DbTable;
import com.quemsi.model.flow.db.sql.DbModel.ReferenceInfo;
import com.quemsi.model.flow.in.TableData.DataPage;
import com.quemsi.model.flow.in.TableDataPage;

public interface DataSourceFactory {
	String PK_VALUES_SEPERATOR = "|-|";

	String getName();
	DataSource getDataSource();
	DbModel getDbModel();

	TableDataPage getTableDataPage(TableDataPage.Request request);
	int writePageData(DbTable table, DataPage dataPage);
	boolean clearTables(String... tableNames);
	boolean dropTables(String...  tableNames);
	void disableConstraints(Set<ReferenceInfo> constraints);
	void enableContraints(Set<ReferenceInfo> constraints);
	void createTables(DbModel dbModel);
}
