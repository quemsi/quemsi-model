package com.quemsi.model.dto;

public enum StorageType implements KeyValuePair{
	AZUREBLOB("Azure Blob Storage"), LOCAL("Local Disk");
	private String val;

	private StorageType(String val){
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
