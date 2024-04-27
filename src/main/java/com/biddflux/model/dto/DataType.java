package com.biddflux.model.dto;

public enum DataType {
	DB("sql"), FILE("*");
	private final String ext;
	private DataType(String ext){
		this.ext = ext;
	}
	public String getExt(){
		return ext;
	}
}
