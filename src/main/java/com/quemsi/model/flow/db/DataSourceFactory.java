package com.quemsi.model.flow.db;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import com.quemsi.model.flow.db.sql.DbModel;

public interface DataSourceFactory {
	String PK_VALUES_SEPERATOR = "|-|";

	String getName();
	void setName(String name);
	String getDbName();
	void setDbName(String dbName);
	String getUrl();
	void setUrl(String url);
	String getUsername();
	void setUsername(String username);
	String getPassword();
	void setPassword(String password);
	
	DataSource getDataSource();
	DbModel getDbModel();
	DDLService ddlService() throws SQLException;
	DDLService ddlService(Connection conn);
	DMLService dmlService() throws SQLException;
	DMLService dmlService(Connection conn);
}
