package com.biddflux.model.dto;

public enum DataType {
	DB("zip"), FILE("*");
	private final String ext;
	private DataType(String ext){
		this.ext = ext;
	}
	public String getExt(){
		return ext;
	}
}
