package com.quemsi.model.flow.db;

import javax.sql.DataSource;

import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.in.TableDataPage;

public interface DataSourceFactory {
	String PK_VALUES_SEPERATOR = "|-|";

	String getName();
	DataSource getDataSource();
	DbModel getDbModel();

	TableDataPage getTableDataPage(TableDataPage.Request request);
}
