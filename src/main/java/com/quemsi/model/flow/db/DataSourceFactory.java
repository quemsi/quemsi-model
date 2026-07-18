package com.quemsi.model.flow.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;
import java.util.function.Consumer;

import javax.sql.DataSource;

import com.quemsi.commons.util.Exceptions;
import com.quemsi.commons.util.LogMessage;
import com.quemsi.model.dto.DatasourceType;
import com.quemsi.model.flow.db.mongodb.DatasourceFactoryMongo;
import com.quemsi.model.flow.db.mysql.DataSourceFactoryMySql;
import com.quemsi.model.flow.db.postgres.DatasourceFactoryPostgres;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.oracle.DatasourceFactoryOracle;
import com.quemsi.model.flow.db.sqlserver.DatasourceFactorySqlserver;

public interface DataSourceFactory {
	String PK_VALUES_SEPERATOR = "|-|";

	String getName();
	void setName(String name);
	String getDbName();
	void setDbName(String dbName);
	Set<String> getSchemas();
	void setSchemas(Set<String> schemas);
	String getUrl();
	void setUrl(String url);
	String getUsername();
	void setUsername(String username);
	String getPassword();
	void setPassword(String password);
	boolean isReadOnly();
	void setReadOnly(boolean readOnly);

	default void assertWritable() {
		if (isReadOnly()) {
			throw Exceptions.badRequest("datasource-read-only").withExtra("name", getName()).get();
		}
	}

	DataSource getDataSource();
	default DbModel getDbModel() {
		return getDbModel(msg -> {});
	}
	DbModel getDbModel(Consumer<LogMessage> progress);
	DDLService ddlService() throws SQLException;
	DMLService dmlService() throws SQLException;
	DatasourceType type();
	static DataSourceFactory create(DatasourceType type){
		if(DatasourceType.MYSQL.equals(type)){
			return new DataSourceFactoryMySql();
		} else if(DatasourceType.POSTGRES.equals(type)) {
			return new DatasourceFactoryPostgres();
		} else if(DatasourceType.SQLSERVER.equals(type)) {
			return new DatasourceFactorySqlserver();
		} else if(DatasourceType.MONGODB.equals(type)) {
			return new DatasourceFactoryMongo();
		} else if(DatasourceType.ORACLE.equals(type)) {
			return new DatasourceFactoryOracle();
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
