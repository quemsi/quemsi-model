package com.quemsi.model.dto;

public enum AgentStatus implements KeyValuePair {
    ONLINE("Online"), OFFLINE("Offline"), UNKNOWN("Unknown"), ERROR("Error");
	
	private String title;
	private AgentStatus(String title) {
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
