package com.quemsi.model.dto;

import lombok.Getter;

public enum DatasourceType implements KeyValuePair{
	MYSQL("MySql", "mysql", 3306, ""),
	POSTGRES("Postgres", "postgresql", 5432, "public"),
	SQLSERVER("Sqlserver", "sqlserver", 1433, "dbo"),
	MONGODB("MongoDB", "mongodb", 27017, ""),
	ORACLE("Oracle", "oracle", 1521, "")
	;
	private String val;
	@Getter
	private String jdbcName;
	@Getter
	private int defaultPort;
	@Getter
	private String defaultSchema;

	private DatasourceType(String val, String jdbcName, int defaultPort, String defaultSchema){
		this.val = val;
		this.jdbcName = jdbcName;
		this.defaultPort = defaultPort;
		this.defaultSchema = defaultSchema;
	}

	@Override
	public String getKey() {
		return this.name();
	}

	@Override
	public String getValue() {
		return this.val;
	}
	
}
