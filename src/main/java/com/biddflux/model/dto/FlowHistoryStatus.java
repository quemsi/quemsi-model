package com.biddflux.model.dto;


public enum FlowHistoryStatus implements KeyValuePair{
	INITIALIZED("Initialized"), SKIPPED("Skipped"), SUCCESS("Completed"), ERROR("In Error");
	
	private String title;
	private FlowHistoryStatus(String title) {
		this.title = title;
	}
	@Override
	public String getKey() {
		return this.name();
	}
	@Override
	public String getValue() {
		return this.title;
	}	
}
