package com.quemsi.model.flow.db;

import javax.sql.DataSource;

import com.quemsi.model.flow.db.sql.DbModel;

public interface DataSourceFactory {
	String getName();
	DataSource getDataSource();
	DbModel getDbModel();
}
