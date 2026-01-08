package com.quemsi.model.dto;

import java.util.Arrays;

import lombok.Getter;

public enum DatasourceType implements KeyValuePair{
	MYSQL("MySql", "mysql", 3306, ""),
	POSTGRES("Postgres", "postgresql", 5432, "public"),
	SQLSERVER("Sqlserver", "sqlserver", 1433, "dbo", "SQLSERVERWIN"),
	SQLSERVERWIN("Sqlserver Windows Auth", "jtds:sqlserver", null, "dbo", "SQLSERVER")
	;
	private String val;
	@Getter
	private String jdbcName;
	@Getter
	private Integer defaultPort;
	@Getter
	private String defaultSchema;
	private String[] compatibleTypes;

	private DatasourceType(String val, String jdbcName, Integer defaultPort, String defaultSchema, String... compatibleTypes){
		this.val = val;
		this.jdbcName = jdbcName;
		this.defaultPort = defaultPort;
		this.defaultSchema = defaultSchema;
		this.compatibleTypes = compatibleTypes;
	}
	
	public boolean isCompatible(String type){
		if(this.name().equals(type)){
			return true;
		}
		if(compatibleTypes == null || compatibleTypes.length == 0){
			return false;
		}
		return Arrays.stream(compatibleTypes).anyMatch(t -> t.equals(type));
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
