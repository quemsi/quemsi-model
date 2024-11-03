package com.quemsi.model.dto;

public enum DataType implements KeyValuePair{
	DB("DB", "sql"), FILE("File", "*");
	private final String ext;
	private final String val;
	private DataType(String val, String ext){
		this.val = val;
		this.ext = ext;
	}
	public String getExt(){
		return ext;
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
