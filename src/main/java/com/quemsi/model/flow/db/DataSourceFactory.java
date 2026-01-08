package com.quemsi.model.flow.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.dto.DatasourceType;
import com.quemsi.model.flow.db.mysql.DataSourceFactoryMySql;
import com.quemsi.model.flow.db.postgres.DatasourceFactoryPostgres;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sqlserver.DatasourceFactorySqlserver;

public interface DataSourceFactory {
	String PK_VALUES_SEPERATOR = "|-|";

	String getName();
	void setName(String name);
	String getDbName();
	void setDbName(String dbName);
	String getSchema();
	void setSchema(String schema);
	String getUrl();
	void setUrl(String url);
	String getUsername();
	void setUsername(String username);
	String getPassword();
	void setPassword(String password);
	
	DataSource getDataSource();
	DbModel getDbModel();
	DDLService ddlService() throws SQLException;
	DMLService dmlService() throws SQLException;
	DatasourceType type();
	static DataSourceFactory create(DatasourceType type){
		if(DatasourceType.MYSQL.equals(type)){
			return new DataSourceFactoryMySql();
		} else if(DatasourceType.POSTGRES.equals(type)) {
			return new DatasourceFactoryPostgres();
		} else if(DatasourceType.SQLSERVER.equals(type) || DatasourceType.SQLSERVERWIN.equals(type)) {
			return new DatasourceFactorySqlserver(type);
		} else{
			throw Exceptions.server("invalid-datasource-type").withExtra("type", type).get();
		}
	}
	default String connectionHealthCheckQuery(){
		return "select 1;";
	}
	default boolean healthCheck() throws Exception{
		try(
			Connection conn = getDataSource().getConnection();
			PreparedStatement st = conn.prepareStatement(connectionHealthCheckQuery())
		){
			ResultSet rs = st.executeQuery();
			rs.close();
			return true;
		}
	}
}
