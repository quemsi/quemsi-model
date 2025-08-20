package com.quemsi.model.dto;

import lombok.Getter;

public enum DatasourceType implements KeyValuePair{
	MYSQL("MySql", "mysql", 3306),
	POSTGRES("Postgres", "postgresql", 5432),
	SQLSERVER("Sqlserver", "sqlserver", 1433)
	;
	private String val;
	@Getter
	private String jdbcName;
	@Getter
	private int defaultPort;

	private DatasourceType(String val, String jdbcName, int defaultPort){
		this.val = val;
		this.jdbcName = jdbcName;
		this.defaultPort = defaultPort;
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
