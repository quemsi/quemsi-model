package com.biddflux.model.dto;


public enum FlowExecutionStatus implements KeyValuePair{
	SCHEDULED("Scheduled"), RUNNING("Running"), SKIPPED("Skipped"), SUCCESS("Completed"), FAILED("In Error");
	
	private String title;
	private FlowExecutionStatus(String title) {
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
