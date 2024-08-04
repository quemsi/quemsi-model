package com.biddflux.model.dto;

public enum DatasourceType implements KeyValuePair{
	MYSQL("MySql");
	private String val;

	private DatasourceType(String val){
		this.val = val;
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
