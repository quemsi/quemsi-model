package com.quemsi.model.dto;

public enum TagType implements KeyValuePair{
	DATAVERSION("Data Group");
	private String val;

	private TagType(String val){
		this.val = val;
	}

	@Override
	public String getKey() {
		return name();
	}

	@Override
	public String getValue() {
		return val;
	}
}
