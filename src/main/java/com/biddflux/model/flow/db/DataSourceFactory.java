package com.biddflux.model.flow.db;

import javax.sql.DataSource;

import com.biddflux.model.flow.db.sql.DbModel;

public interface DataSourceFactory {
	String getName();
	DataSource getDataSource();
	DbModel getDbModel();
}
