package com.quemsi.model.dto;

import lombok.Getter;

public enum StorageType implements KeyValuePair{
	AZUREBLOB("Azure Blob Storage", true), AWSS3("AWS S3 Storage", true), LOCAL("Local Disk", false);
	@Getter
	private boolean global;
	private String val;

	private StorageType(String val, boolean global){
		this.val = val;
		this.global = global;
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
